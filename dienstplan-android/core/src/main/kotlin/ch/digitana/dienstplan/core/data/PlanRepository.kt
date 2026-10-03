package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Canton
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.HybridClock
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.Notes
import ch.digitana.dienstplan.core.crdt.OfferEntry
import ch.digitana.dienstplan.core.crdt.PlanAccess
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftOffer
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import ch.digitana.dienstplan.core.crdt.ShiftPatterns
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.SwapEntry
import ch.digitana.dienstplan.core.crdt.SwapRequest
import ch.digitana.dienstplan.core.crdt.SwapStatus
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.group.PlanSync
import ch.digitana.dienstplan.core.group.RemoteMerge
import ch.digitana.dienstplan.core.plan.PatternPlanner
import ch.digitana.dienstplan.core.plan.ShiftTrades
import ch.digitana.dienstplan.core.plan.Suggestion
import ch.digitana.dienstplan.core.plan.TradeProblem
import ch.digitana.dienstplan.core.plan.WishRights
import ch.digitana.dienstplan.core.util.Clock
import ch.digitana.dienstplan.core.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** Ungültige lokale Eingabe (z. B. leerer oder zu langer Name oder Notiz). */
class InvalidInputException(val problem: NameProblem) : IllegalArgumentException("Ungültige Eingabe: $problem")

/** Der Plan ist gesperrt: Diese Änderung dürfen nur Admins machen. */
class PlanLockedException : IllegalStateException("Plan gesperrt")

/** Wunsch einer anderen Person, die selbst ein Gerät im Team hat (siehe [WishRights]). */
class WishNotAllowedException : IllegalStateException("Wunsch einer anderen Person")

/** Angebot oder Tausch für eine andere Person, die selbst ein Gerät im Team hat (gleiche Regel wie bei Wünschen). */
class TradeNotAllowedException : IllegalStateException("Nur die Person selbst oder ein Admin")

/** Übernahme oder Tausch geht nicht (mehr), siehe [TradeProblem]. */
class TradeException(val problem: TradeProblem) : IllegalStateException("Tausch nicht möglich: $problem")

/** Wie eine Übernahme oder ein angenommener Tausch ausgegangen ist. */
enum class TradeOutcome {
    /** Die Schichten sind getauscht bzw. übergeben. */
    DONE,

    /** Ein Tag ist gesperrt: angemeldet bzw. angenommen, ein Admin führt ihn aus. */
    AWAITING_ADMIN,
}

/**
 * Ergebnis einer Änderung (ein Feld oder viele, z. B. Woche kopieren): wie viele Felder sich
 * geändert haben und wie es vorher war – für „Rückgängig“.
 */
class Batch internal constructor(
    val changed: Int,
    /** Schlüssel, Wert davor, Wert danach. */
    internal val changes: List<Triple<String, String, String>>,
) {
    /** Geänderte Schlüssel. */
    val keys: Set<String> get() = changes.mapTo(LinkedHashSet()) { it.first }

    companion object {
        val EMPTY = Batch(0, emptyList())

        /**
         * Fasst nacheinander geschriebene Änderungen zu einer zusammen (z. B. alle Felder einer
         * Wischbewegung): pro Schlüssel der erste Wert davor und der letzte danach.
         */
        fun combine(batches: List<Batch>): Batch {
            val before = LinkedHashMap<String, String>()
            val after = HashMap<String, String>()
            for (batch in batches) {
                for ((key, old, new) in batch.changes) {
                    before.putIfAbsent(key, old)
                    after[key] = new
                }
            }
            val changes = before.mapNotNull { (key, old) -> after.getValue(key).let { if (it == old) null else Triple(key, old, it) } }
            return Batch(changes.size, changes)
        }
    }
}

/**
 * CRDT-Speicher des Plans. Lokale Änderungen bekommen einen Zeitstempel der hybriden Uhr
 * und die Geräte-ID; Einträge von Relays werden per LWW zusammengeführt. Gespeichert wird
 * entprellt im Hintergrund. Eigene Änderungen bleiben als „ausstehend“ vermerkt, bis ein
 * Relay sie bestätigt hat – auch über einen Neustart hinweg.
 */
class PlanRepository(
    private val store: PlanStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val saveDelayMillis: Long = 300,
    private val logger: Logger = Logger.None,
) : PlanSync {

    private val mutex = Mutex()
    private val hybridClock = HybridClock(clock)
    /** Eigene, noch nicht bestätigte Änderungen: Schlüssel → Zeitstempel des Eintrags. */
    private val pending = HashMap<String, Long>()
    /** Änderungen anderer Geräte, die hier noch niemand angesehen hat: Schlüssel → Empfang (ms). */
    private val unseen = HashMap<String, Long>()
    private val _state = MutableStateFlow(PlanState.EMPTY)
    override val state: StateFlow<PlanState> = _state.asStateFlow()

    private val _pendingKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Schlüssel eigener Änderungen, die noch kein Relay bestätigt hat (für „wird gesendet“ im Raster). */
    val pendingKeys: StateFlow<Set<String>> = _pendingKeys.asStateFlow()

    private val _unseenKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Schlüssel, die andere Geräte geändert haben, seit hier jemand hingeschaut hat. */
    val unseenKeys: StateFlow<Set<String>> = _unseenKeys.asStateFlow()

    private val _localChanges = MutableSharedFlow<Any>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val localChanges: Flow<Any> = _localChanges.asSharedFlow()

    private val _access = MutableStateFlow(PlanAccess.OPEN)

    /** Sperre und Admin-Geräte des Teams (siehe [setAccess]). */
    val access: StateFlow<PlanAccess> = _access.asStateFlow()

    private val _discarded = MutableSharedFlow<Int>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Anzahl eigener Änderungen, die eine neue Sperre ungültig gemacht hat. */
    val discarded: Flow<Int> = _discarded.asSharedFlow()

    private val _purged = MutableSharedFlow<Set<String>>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val purged: Flow<Set<String>> = _purged.asSharedFlow()

    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)

    /** Geräte-ID des aktuellen Teams (als Flow, z. B. um auf den Beitritt zu warten). */
    val deviceIdFlow: StateFlow<String?> = _deviceId.asStateFlow()

    /** Geräte-ID des aktuellen Teams; ohne Team sind keine Änderungen möglich. */
    var deviceId: String?
        get() = _deviceId.value
        set(value) {
            _deviceId.value = value
        }

    init {
        scope.launch {
            for (request in saveRequests) {
                delay(saveDelayMillis)
                saveNow()
            }
        }
    }

    suspend fun load() {
        val snapshot = withContext(ioDispatcher) { store.load() }
        mutex.withLock {
            val state = snapshot?.state ?: PlanState.EMPTY
            _state.value = state
            hybridClock.observe(maxOf(snapshot?.clock ?: 0L, state.maxTimestamp))
            pending.clear()
            snapshot?.pending?.let { pending.putAll(it) }
            unseen.clear()
            val oldest = clock.nowMillis() - UNSEEN_MAX_AGE_MILLIS
            snapshot?.unseen?.forEach { (key, received) -> if (received >= oldest && state.entry(key) != null) unseen[key] = received }
            publishPending()
            publishUnseen()
        }
        _loaded.value = true
    }

    /** Trägt eine Schichtart ein; null leert das Feld. */
    suspend fun setShift(memberId: String, date: LocalDate, typeId: String?): Batch {
        require(typeId == null || ShiftTypes.isValidId(typeId)) { "Ungültige Schichtart" }
        return write { listOf(PlanKeys.shift(memberId, date) to (typeId ?: "")) }
    }

    /** Notiz zum Tag; leer löscht sie. */
    suspend fun setDayNote(date: LocalDate, note: String): Batch {
        val value = validateNote(note)
        return write { listOf(PlanKeys.dayNote(date) to value) }
    }

    /** Notiz zum Dienst einer Person; leer löscht sie. */
    suspend fun setMemberNote(memberId: String, date: LocalDate, note: String): Batch {
        val value = validateNote(note)
        return write { listOf(PlanKeys.memberNote(memberId, date) to value) }
    }

    /**
     * Wunsch einer Person; null löscht ihn. Erlaubt für die eigene Person, für Personen ohne
     * eigenes Gerät und für Admins ([WishRights]); Sperren gelten für Wünsche nicht.
     */
    suspend fun setWish(memberId: String, date: LocalDate, wish: Wish?): Batch {
        val device = deviceId
        return write { state ->
            if (!WishRights.mayEdit(state.deviceOwners(), memberId, device, _access.value.isAdmin(device))) {
                throw WishNotAllowedException()
            }
            listOf(PlanKeys.wish(memberId, date) to (wish?.code ?: ""))
        }
    }

    /** „Das bin ich“ für alle sichtbar: ordnet dieses Gerät einer Person zu (null hebt sie auf). */
    suspend fun setDeviceOwner(memberId: String?) {
        require(memberId == null || PlanKeys.isValidId(memberId)) { "Ungültige ID" }
        val device = deviceId ?: throw IllegalStateException("Kein Team aktiv")
        write { listOf(PlanKeys.deviceOwner(device) to (memberId ?: "")) }
    }

    /** Freie ID für eine neue Schichtart. */
    fun newShiftTypeId(): String = ShiftTypes.newId(_state.value.shiftTypes.ids)

    /** Legt eine Schichtart an oder ändert sie (auch Archivieren). */
    suspend fun saveShiftType(type: ShiftType) {
        write { listOf(PlanKeys.shiftType(type.id) to ShiftTypes.encode(type)) }
    }

    /** Setzt eine Standardart (F, S, N, X, U) auf ihre ursprünglichen Werte zurück. */
    suspend fun resetShiftType(typeId: String) {
        require(typeId in ShiftTypes.BUILT_IN_IDS) { "Nur Standardarten lassen sich zurücksetzen" }
        write { listOf(PlanKeys.shiftType(typeId) to "") }
    }

    /** Mindestruhezeit zwischen zwei Diensten in Minuten (0 = keine Warnung); null = Standard (11 h). */
    suspend fun setRestMinutes(minutes: Int?) {
        require(minutes == null || minutes in 0..PlanRules.MAX_REST_MINUTES) { "Ungültige Ruhezeit" }
        write { listOf(PlanKeys.setting(PlanRules.REST) to (minutes?.toString() ?: "")) }
    }

    /** Soll-Besetzung einer Schichtart pro Wochentag (Montag zuerst); null oder nur Nullen löscht sie. */
    suspend fun setTargets(typeId: String, targets: List<Int>?) {
        val value = if (targets == null || targets.all { it == 0 }) "" else PlanRules.encodeTargets(targets)
        write { listOf(PlanKeys.target(typeId) to value) }
    }

    /** Pensum der Person in Prozent (1–100); null = nicht angegeben. */
    suspend fun setPensum(memberId: String, pensum: Int?): Batch {
        require(pensum == null || pensum in PlanRules.MIN_PENSUM..PlanRules.MAX_PENSUM) { "Ungültiges Pensum" }
        return write { listOf(PlanKeys.pensum(memberId) to (pensum?.toString() ?: "")) }
    }

    /** Wochenstunden bei 100 % in Minuten; null = Standard (42 h). */
    suspend fun setWeekMinutes(minutes: Int?): Batch {
        require(minutes == null || minutes in PlanRules.MIN_WEEK_MINUTES..PlanRules.MAX_WEEK_MINUTES) { "Ungültige Wochenstunden" }
        return write { listOf(PlanKeys.setting(PlanRules.HOURS) to (minutes?.toString() ?: "")) }
    }

    /** Kanton für die Feiertage; null = keiner. */
    suspend fun setCanton(canton: Canton?): Batch = write { listOf(PlanKeys.setting(PlanRules.CANTON) to (canton?.code ?: "")) }

    /** Letzter Tag für Wünsche im Monat [month]; null entfernt die Frist. */
    suspend fun setWishDeadline(month: YearMonth, deadline: LocalDate?): Batch {
        require(deadline == null || PlanKeys.isValidDate(deadline)) { "Datum ausserhalb 2000–2100" }
        return write { listOf(PlanKeys.setting(PlanRules.wishDeadline(month)) to (deadline?.toString() ?: "")) }
    }

    /**
     * Offenen Dienst übernehmen: [memberId] arbeitet an [date] in der Schicht [typeId]. Die
     * Person muss an dem Tag frei sein; gesperrte Tage ändern nur Admins.
     */
    suspend fun claimOpenShift(memberId: String, date: LocalDate, typeId: String): Batch {
        require(ShiftTypes.isValidId(typeId)) { "Ungültige Schichtart" }
        return write { state ->
            ShiftTrades.claimProblem(state, memberId, date)?.let { throw TradeException(it) }
            listOf(PlanKeys.shift(memberId, date) to typeId)
        }
    }

    /** Dienst abgeben: Die Person bietet ihren Arbeitsdienst an [date] an. */
    suspend fun offerShift(memberId: String, date: LocalDate): Batch = write { state ->
        requirePerson(state, memberId)
        val typeId = state.shift(memberId, date)?.takeIf { state.shiftTypes[it]?.kind == ShiftKind.WORK }
            ?: throw TradeException(TradeProblem.STALE)
        listOf(PlanKeys.offer(memberId, date) to typeId)
    }

    /** Angebot zurückziehen. */
    suspend fun withdrawOffer(memberId: String, date: LocalDate): Batch = write { state ->
        requirePerson(state, memberId)
        listOf(PlanKeys.offer(memberId, date) to "")
    }

    /**
     * Angebotenen Dienst übernehmen. Darf dieses Gerät die Schichten schreiben, ist die Übergabe
     * sofort erledigt; ist der Tag gesperrt, wird sie angemeldet und ein Admin bestätigt sie.
     */
    suspend fun takeOffer(giverId: String, date: LocalDate, takerId: String): TradeOutcome {
        var outcome = TradeOutcome.DONE
        write { state ->
            requirePerson(state, takerId)
            val offer = offerEntry(state, giverId, date) ?: throw TradeException(TradeProblem.STALE)
            ShiftTrades.takeProblem(state, offer, takerId)?.let { throw TradeException(it) }
            val changes = ShiftTrades.takeChanges(offer, takerId)
            if (mayWriteAll(state, changes)) {
                changes
            } else {
                outcome = TradeOutcome.AWAITING_ADMIN
                listOf(ShiftTrades.claimChange(offer, takerId))
            }
        }
        return outcome
    }

    /** Angemeldete Übernahme ausführen (Admin). */
    suspend fun approveClaim(giverId: String, date: LocalDate): Batch = write { state ->
        if (!_access.value.isAdmin(deviceId)) throw TradeNotAllowedException()
        val offer = offerEntry(state, giverId, date) ?: throw TradeException(TradeProblem.STALE)
        val taker = offer.offer.claimedBy ?: throw TradeException(TradeProblem.STALE)
        ShiftTrades.takeProblem(state, offer, taker)?.let { throw TradeException(it) }
        ShiftTrades.takeChanges(offer, taker)
    }

    /** Angemeldete Übernahme ablehnen (Admin oder die abgebende Person): Das Angebot ist wieder offen. */
    suspend fun rejectClaim(giverId: String, date: LocalDate): Batch = write { state ->
        val offer = offerEntry(state, giverId, date) ?: throw TradeException(TradeProblem.STALE)
        if (!_access.value.isAdmin(deviceId)) requirePerson(state, giverId)
        listOf(offer.key to offer.offer.copy(claimedBy = null).encode())
    }

    /** Tausch vorschlagen: A gibt den Dienst an [fromDate] an B und übernimmt ggf. den Dienst von B an [toDate]. */
    suspend fun proposeSwap(fromMember: String, fromDate: LocalDate, toMember: String, toDate: LocalDate): Batch = write { state ->
        requirePerson(state, fromMember)
        val proposal = ShiftTrades.proposal(state, fromMember, fromDate, toMember, toDate) ?: throw TradeException(TradeProblem.STALE)
        listOf(proposal)
    }

    /** Tauschvorschlag zurückziehen (die vorschlagende Person). */
    suspend fun withdrawSwap(swap: PlanKey.Swap): Batch = write { state ->
        requirePerson(state, swap.fromMember)
        listOf(swapKey(swap) to "")
    }

    /**
     * Auf einen Tauschvorschlag antworten (die angefragte Person oder ein Admin). Annehmen
     * tauscht sofort, wenn dieses Gerät die Schichten schreiben darf, sonst führt ihn ein Admin
     * aus. Ablehnen beendet ihn.
     */
    suspend fun answerSwap(swap: PlanKey.Swap, accept: Boolean): TradeOutcome {
        var outcome = TradeOutcome.DONE
        write { state ->
            if (!_access.value.isAdmin(deviceId)) requirePerson(state, swap.toMember)
            val entry = swapEntry(state, swap) ?: throw TradeException(TradeProblem.STALE)
            if (!entry.request.isOpen) throw TradeException(TradeProblem.STALE)
            if (!accept) return@write listOf(entry.key to entry.request.with(SwapStatus.DECLINED).encode())
            ShiftTrades.swapProblem(state, entry)?.let { throw TradeException(it) }
            val changes = ShiftTrades.swapChanges(entry)
            if (mayWriteAll(state, changes)) {
                changes
            } else {
                outcome = TradeOutcome.AWAITING_ADMIN
                listOf(entry.key to entry.request.with(SwapStatus.ACCEPTED).encode())
            }
        }
        return outcome
    }

    /** Angenommenen Tausch ausführen (Admin, z. B. weil ein Tag gesperrt ist). */
    suspend fun executeSwap(swap: PlanKey.Swap): Batch = write { state ->
        if (!_access.value.isAdmin(deviceId)) throw TradeNotAllowedException()
        val entry = swapEntry(state, swap) ?: throw TradeException(TradeProblem.STALE)
        if (!entry.request.isOpen) throw TradeException(TradeProblem.STALE)
        ShiftTrades.swapProblem(state, entry)?.let { throw TradeException(it) }
        ShiftTrades.swapChanges(entry)
    }

    /** Plan-Vorschlag übernehmen; Felder, die inzwischen jemand belegt hat, bleiben unverändert. */
    suspend fun applySuggestion(suggestion: Suggestion): Batch = writeBatch { state ->
        suggestion.changes().filter { (key, _) -> state.value(key).isEmpty() }
    }

    fun newPatternId(): String = ShiftPatterns.newId(_state.value.patterns().map { it.id }.toSet())

    suspend fun savePattern(pattern: ShiftPattern) {
        write { listOf(PlanKeys.pattern(pattern.id) to ShiftPatterns.encode(pattern)) }
    }

    suspend fun deletePattern(patternId: String) {
        write { listOf(PlanKeys.pattern(patternId) to "") }
    }

    /**
     * Wendet einen Rhythmus ab dem Montag [start] für [weeks] Wochen auf Personen an.
     * Mit [overwrite] wird der Zeitraum genau wie der Rhythmus (leere Tage leeren das Feld),
     * sonst werden nur leere Felder gefüllt.
     */
    suspend fun applyPattern(pattern: ShiftPattern, memberIds: List<String>, start: LocalDate, weeks: Int, overwrite: Boolean): Batch =
        writeBatch { state -> PatternPlanner.changes(state, pattern, memberIds, start, weeks, overwrite) }

    /** @return die ID der neuen Person. */
    suspend fun addMember(name: String): String {
        val id = PlanKeys.newId()
        val validName = validateName(name)
        write { listOf(PlanKeys.member(id) to validName) }
        return id
    }

    suspend fun renameMember(id: String, name: String) {
        val validName = validateName(name)
        write { listOf(PlanKeys.member(id) to validName) }
    }

    /** Name eines Geräts in der Geräteliste; leer = Name entfernen. */
    suspend fun setDeviceLabel(deviceId: String, label: String) {
        val value = if (label.isBlank()) "" else validateName(label)
        write { listOf(PlanKeys.device(deviceId) to value) }
    }

    /** Löschen = leerer Name (Tombstone). Schichten bleiben erhalten, werden aber nicht angezeigt. */
    suspend fun deleteMember(id: String): Batch = write { listOf(PlanKeys.member(id) to "") }

    /** true, wenn in der Woche für aktive Personen mindestens ein Feld belegt ist. */
    fun hasEntries(week: WeekId): Boolean {
        val state = _state.value
        return state.members().any { member ->
            week.days.any { date -> PlanKeys.isValidDate(date) && state.shift(member.id, date) != null }
        }
    }

    /**
     * Macht die Folgewoche identisch mit [source] (auch leere Felder) – für alle aktiven
     * Personen. Geschrieben werden nur Felder, die sich tatsächlich ändern.
     */
    suspend fun copyWeekToNext(source: WeekId): Batch {
        val target = source.next()
        return writeBatch { state ->
            val changes = ArrayList<Pair<String, String>>()
            for (member in state.members()) {
                source.days.zip(target.days).forEach { (from, to) ->
                    if (!PlanKeys.isValidDate(from) || !PlanKeys.isValidDate(to)) return@forEach
                    val value = state.shift(member.id, from) ?: ""
                    changes += PlanKeys.shift(member.id, to) to value
                }
            }
            changes
        }
    }

    /**
     * Macht eine Sammeländerung rückgängig – nur für Felder, die seither niemand geändert hat.
     * @return Anzahl zurückgesetzter Felder.
     */
    suspend fun undo(batch: Batch): Int = write { state ->
        batch.changes.filter { (key, _, after) -> state.value(key) == after }.map { (key, before, _) -> key to before }
    }.changed

    /** Diese Änderungen anderer Geräte hat jemand angesehen (z. B. das Feld geöffnet). */
    suspend fun markSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val removed = mutex.withLock {
            var any = false
            for (key in keys) if (unseen.remove(key) != null) any = true
            if (any) publishUnseen()
            any
        }
        if (removed) saveRequests.trySend(Unit)
    }

    /** Alle Änderungen anderer Geräte gelten als gesehen. */
    suspend fun markAllSeen() {
        val removed = mutex.withLock {
            val any = unseen.isNotEmpty()
            unseen.clear()
            if (any) publishUnseen()
            any
        }
        if (removed) saveRequests.trySend(Unit)
    }

    override suspend fun mergeRemote(bucket: String, entries: Map<String, Entry>): RemoteMerge {
        val outcome = mutex.withLock {
            val access = _access.value
            val accepted = if (access.lock == null) entries else entries.filter { (key, entry) -> access.allows(key, entry) }
            val rejected = entries.size - accepted.size
            val before = _state.value
            val result = before.merge(bucket, accepted)
            if (result.changedKeys.isEmpty()) return@withLock RemoteMerge(false, rejected)
            _state.value = result.state
            // Neu für dieses Gerät – ausser beim ersten Abgleich oder bei einer grossen Reparatur.
            val markUnseen = !before.isEmpty() && result.changedKeys.size <= MAX_UNSEEN_PER_MERGE
            val now = clock.nowMillis()
            val device = deviceId
            var maxTimestamp = 0L
            for (key in result.changedKeys) {
                val entry = accepted.getValue(key)
                if (entry.timestamp > maxTimestamp) maxTimestamp = entry.timestamp
                // Ein neuerer Eintrag von aussen hat die eigene Änderung überholt.
                pending.remove(key)
                if (markUnseen && entry.device != device) unseen[key] = now
            }
            trimUnseen()
            hybridClock.observe(maxTimestamp)
            publishPending()
            publishUnseen()
            RemoteMerge(true, rejected)
        }
        if (outcome.changed) saveRequests.trySend(Unit)
        return outcome
    }

    /**
     * Neue Sperre oder neue Admins aus der MLS-Gruppe. Einträge, die danach nicht mehr gelten,
     * werden entfernt – auf jedem Gerät nach derselben Regel. Den vorherigen Wert eines
     * Feldes liefert der Abgleich mit den anderen Geräten zurück.
     * @return Anzahl entfernter Einträge.
     */
    suspend fun setAccess(access: PlanAccess): Int {
        var removed = 0
        var own = 0
        var buckets: Set<String> = emptySet()
        mutex.withLock {
            if (_access.value == access) return@withLock
            _access.value = access
            val lock = access.lock ?: return@withLock
            // Eigene Einträge nach dem Sperren müssen jünger sein als jeder Abschnitt.
            hybridClock.observe(lock.changedAt)
            val result = _state.value.filter { key, entry -> access.allows(key, entry) }
            if (result.removed.isEmpty()) return@withLock
            _state.value = result.state
            removed = result.removed.size
            val device = deviceId
            own = result.removed.values.count { it.device == device }
            for (key in result.removed.keys) {
                pending.remove(key)
                unseen.remove(key)
            }
            publishPending()
            publishUnseen()
            buckets = result.removed.keys.mapNotNullTo(HashSet()) { key -> PlanKeys.parse(key)?.let(Buckets::forKey) }
        }
        if (removed > 0) {
            logger.debug(TAG, "Sperre: $removed Einträge entfernt")
            _purged.tryEmit(buckets)
            if (own > 0) _discarded.tryEmit(own)
            saveRequests.trySend(Unit)
        }
        return removed
    }

    override suspend fun lockTimestamp(): Long = mutex.withLock { hybridClock.next() }

    /** Aktuelle Einträge der eigenen, noch nicht bestätigten Änderungen. */
    override suspend fun pendingEntries(): Map<String, Entry> = mutex.withLock {
        val state = _state.value
        val result = HashMap<String, Entry>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (key, timestamp) = iterator.next()
            val entry = state.entry(key)
            if (entry == null || entry.timestamp != timestamp) {
                iterator.remove()
                continue
            }
            result[key] = entry
        }
        publishPending()
        result
    }

    /** Ein Relay hat diese Einträge bestätigt (Schlüssel → Zeitstempel). */
    override suspend fun markSent(sent: Map<String, Long>) {
        var removed = false
        mutex.withLock {
            for ((key, timestamp) in sent) {
                if (pending[key] == timestamp) {
                    pending.remove(key)
                    removed = true
                }
            }
            if (removed) publishPending()
        }
        if (removed) saveRequests.trySend(Unit)
    }

    /** Alle Einträge als ausstehend markieren (lokalen Plan in ein Team übernehmen). */
    suspend fun markAllPending() {
        mutex.withLock {
            for (map in _state.value.buckets.values) {
                for ((key, entry) in map.entries) pending[key] = entry.timestamp
            }
            publishPending()
        }
        _localChanges.tryEmit(Unit)
        saveRequests.trySend(Unit)
    }

    /** Ersetzt den gesamten Stand (Beitreten ohne Übernahme, Team verlassen). */
    suspend fun replaceAll(state: PlanState) {
        mutex.withLock {
            _state.value = state
            hybridClock.observe(state.maxTimestamp)
            pending.clear()
            unseen.clear()
            publishPending()
            publishUnseen()
        }
        saveNow()
    }

    /** Sofort speichern (z. B. wenn die App in den Hintergrund geht). */
    suspend fun flush() = saveNow()

    private suspend fun write(compute: (PlanState) -> List<Pair<String, String>>): Batch = writeBatch(compute)

    /** Berechnet die Änderungen aus dem aktuellen Stand und schreibt sie – alles unter der Sperre. */
    private suspend fun writeBatch(compute: (PlanState) -> List<Pair<String, String>>): Batch {
        val device = deviceId ?: throw IllegalStateException("Kein Team aktiv")
        val written = ArrayList<Triple<String, String, String>>()
        mutex.withLock {
            var state = _state.value
            val changes = compute(state)
            // Alles oder nichts: Ist ein Feld gesperrt, unterbleibt die ganze Änderung.
            val access = _access.value
            if (changes.any { (key, value) -> state.value(key) != value && !access.mayWrite(device, key, value) }) {
                throw PlanLockedException()
            }
            for ((key, value) in changes) {
                // Unveränderte Felder nicht neu schreiben (spart Sync-Verkehr, vermeidet unnötige Konflikte).
                val before = state.value(key)
                if (before == value) continue
                val entry = Entry(value, hybridClock.next(), device)
                state = state.withEntry(key, entry)
                pending[key] = entry.timestamp
                // Wer ein Feld selbst ändert, hat die Änderung davor gesehen.
                unseen.remove(key)
                written += Triple(key, before, value)
            }
            _state.value = state
            if (written.isNotEmpty()) {
                publishPending()
                publishUnseen()
            }
        }
        if (written.isNotEmpty()) {
            _localChanges.tryEmit(Unit)
            saveRequests.trySend(Unit)
        }
        return Batch(written.size, written)
    }

    /** Darf dieses Gerät alle [changes] schreiben (Sperre)? Unveränderte Felder zählen nicht. */
    private fun mayWriteAll(state: PlanState, changes: List<Pair<String, String>>): Boolean {
        val access = _access.value
        val device = deviceId
        return changes.all { (key, value) -> state.value(key) == value || access.mayWrite(device, key, value) }
    }

    /** Angebote und Tausch für [memberId]: die Person selbst, Admins oder jede, wenn die Person kein Gerät hat. */
    private fun requirePerson(state: PlanState, memberId: String) {
        val device = deviceId
        if (!WishRights.mayEdit(state.deviceOwners(), memberId, device, _access.value.isAdmin(device))) throw TradeNotAllowedException()
    }

    private fun offerEntry(state: PlanState, memberId: String, date: LocalDate): OfferEntry? {
        val key = PlanKeys.offer(memberId, date)
        val entry = state.entry(key) ?: return null
        val offer = ShiftOffer.decode(entry.value) ?: return null
        return OfferEntry(memberId, date, offer, entry)
    }

    private fun swapKey(swap: PlanKey.Swap): String = PlanKeys.swap(swap.fromMember, swap.fromDate, swap.toMember, swap.toDate)

    private fun swapEntry(state: PlanState, swap: PlanKey.Swap): SwapEntry? {
        val entry = state.entry(swapKey(swap)) ?: return null
        val request = SwapRequest.decode(entry.value) ?: return null
        return SwapEntry(swap, request, entry)
    }

    /** Unter [mutex]: nur die jüngsten [MAX_UNSEEN] behalten. */
    private fun trimUnseen() {
        if (unseen.size <= MAX_UNSEEN) return
        val keep = unseen.entries.sortedByDescending { it.value }.take(MAX_UNSEEN).map { it.key }.toSet()
        unseen.keys.retainAll(keep)
    }

    /** Unter [mutex] aufrufen. */
    private fun publishPending() {
        _pendingKeys.value = HashSet(pending.keys)
    }

    /** Unter [mutex] aufrufen. */
    private fun publishUnseen() {
        _unseenKeys.value = HashSet(unseen.keys)
    }

    private suspend fun saveNow() {
        val snapshot = mutex.withLock { PlanSnapshot(_state.value, hybridClock.current(), HashMap(pending), HashMap(unseen)) }
        try {
            withContext(ioDispatcher) { store.save(snapshot) }
        } catch (e: Exception) {
            logger.warn(TAG, "Plan konnte nicht gespeichert werden", e)
        }
    }

    private fun validateName(input: String): String {
        Names.checkLocalInput(input)?.let { throw InvalidInputException(it) }
        return Names.normalizeInput(input)
    }

    private fun validateNote(input: String): String {
        Notes.checkLocalInput(input)?.let { throw InvalidInputException(it) }
        return Notes.normalizeInput(input)
    }

    companion object {
        private const val TAG = "PlanRepository"

        /** Grössere Abgleiche (erster Abgleich, Reparatur) markieren nichts als neu. */
        const val MAX_UNSEEN_PER_MERGE = 500
        const val MAX_UNSEEN = 2000

        /** Nach zwei Wochen gilt eine Änderung als gesehen. */
        const val UNSEEN_MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
