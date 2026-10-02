package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.HybridClock
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.Notes
import ch.digitana.dienstplan.core.crdt.PlanAccess
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import ch.digitana.dienstplan.core.crdt.ShiftPatterns
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.group.PlanSync
import ch.digitana.dienstplan.core.group.RemoteMerge
import ch.digitana.dienstplan.core.plan.PatternPlanner
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

/** Ungültige lokale Eingabe (z. B. leerer oder zu langer Name oder Notiz). */
class InvalidInputException(val problem: NameProblem) : IllegalArgumentException("Ungültige Eingabe: $problem")

/** Der Plan ist gesperrt: Diese Änderung dürfen nur Admins machen. */
class PlanLockedException : IllegalStateException("Plan gesperrt")

/** Wunsch einer anderen Person, die selbst ein Gerät im Team hat (siehe [WishRights]). */
class WishNotAllowedException : IllegalStateException("Wunsch einer anderen Person")

/**
 * Ergebnis einer Sammeländerung (Woche kopieren, Rhythmus anwenden): wie viele Felder sich
 * geändert haben und wie es vorher war – für „Rückgängig“.
 */
class Batch internal constructor(
    val changed: Int,
    /** Schlüssel, Wert davor, Wert danach. */
    internal val changes: List<Triple<String, String, String>>,
)

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
    private val _state = MutableStateFlow(PlanState.EMPTY)
    override val state: StateFlow<PlanState> = _state.asStateFlow()

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
        }
        _loaded.value = true
    }

    /** Trägt eine Schichtart ein; null leert das Feld. */
    suspend fun setShift(memberId: String, date: LocalDate, typeId: String?) {
        require(typeId == null || ShiftTypes.isValidId(typeId)) { "Ungültige Schichtart" }
        write { listOf(PlanKeys.shift(memberId, date) to (typeId ?: "")) }
    }

    /** Notiz zum Tag; leer löscht sie. */
    suspend fun setDayNote(date: LocalDate, note: String) {
        val value = validateNote(note)
        write { listOf(PlanKeys.dayNote(date) to value) }
    }

    /** Notiz zum Dienst einer Person; leer löscht sie. */
    suspend fun setMemberNote(memberId: String, date: LocalDate, note: String) {
        val value = validateNote(note)
        write { listOf(PlanKeys.memberNote(memberId, date) to value) }
    }

    /**
     * Wunsch einer Person; null löscht ihn. Erlaubt für die eigene Person, für Personen ohne
     * eigenes Gerät und für Admins ([WishRights]); Sperren gelten für Wünsche nicht.
     */
    suspend fun setWish(memberId: String, date: LocalDate, wish: Wish?) {
        val device = deviceId
        write { state ->
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
    suspend fun deleteMember(id: String) {
        write { listOf(PlanKeys.member(id) to "") }
    }

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
    }

    override suspend fun mergeRemote(bucket: String, entries: Map<String, Entry>): RemoteMerge {
        val outcome = mutex.withLock {
            val access = _access.value
            val accepted = if (access.lock == null) entries else entries.filter { (key, entry) -> access.allows(key, entry) }
            val rejected = entries.size - accepted.size
            val result = _state.value.merge(bucket, accepted)
            if (result.changedKeys.isEmpty()) return@withLock RemoteMerge(false, rejected)
            _state.value = result.state
            var maxTimestamp = 0L
            for (key in result.changedKeys) {
                val ts = accepted.getValue(key).timestamp
                if (ts > maxTimestamp) maxTimestamp = ts
                // Ein neuerer Eintrag von aussen hat die eigene Änderung überholt.
                pending.remove(key)
            }
            hybridClock.observe(maxTimestamp)
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
            for (key in result.removed.keys) pending.remove(key)
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
        }
        if (removed) saveRequests.trySend(Unit)
    }

    /** Alle Einträge als ausstehend markieren (lokalen Plan in ein Team übernehmen). */
    suspend fun markAllPending() {
        mutex.withLock {
            for (map in _state.value.buckets.values) {
                for ((key, entry) in map.entries) pending[key] = entry.timestamp
            }
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
        }
        saveNow()
    }

    /** Sofort speichern (z. B. wenn die App in den Hintergrund geht). */
    suspend fun flush() = saveNow()

    private suspend fun write(compute: (PlanState) -> List<Pair<String, String>>): Int = writeBatch(compute).changed

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
                written += Triple(key, before, value)
            }
            _state.value = state
        }
        if (written.isNotEmpty()) {
            _localChanges.tryEmit(Unit)
            saveRequests.trySend(Unit)
        }
        return Batch(written.size, written)
    }

    private suspend fun saveNow() {
        val snapshot = mutex.withLock { PlanSnapshot(_state.value, hybridClock.current(), HashMap(pending)) }
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
    }
}
