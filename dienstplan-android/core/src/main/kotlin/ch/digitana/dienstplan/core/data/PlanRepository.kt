package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.HybridClock
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.Shift
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.group.PlanSync
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

/** Ungültige lokale Eingabe (z. B. leerer oder zu langer Name). */
class InvalidInputException(val problem: NameProblem) : IllegalArgumentException("Ungültiger Name: $problem")

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

    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Geräte-ID des aktuellen Teams; ohne Team sind keine Änderungen möglich. */
    @Volatile
    var deviceId: String? = null

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

    suspend fun setShift(memberId: String, date: LocalDate, shift: Shift?) {
        write { listOf(PlanKeys.shift(memberId, date) to (shift?.code ?: "")) }
    }

    /**
     * Schaltet ein Feld weiter (leer → F → S → N → X → U → leer). Lesen und Schreiben
     * geschehen unter derselben Sperre, damit schnelles Mehrfachtippen keinen Schritt verliert.
     * @return die neue Schicht.
     */
    suspend fun cycleShift(memberId: String, date: LocalDate): Shift? {
        var next: Shift? = null
        write { state ->
            next = Shift.next(state.shift(memberId, date))
            listOf(PlanKeys.shift(memberId, date) to (next?.code ?: ""))
        }
        return next
    }

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
     * @return Anzahl geänderter Felder.
     */
    suspend fun copyWeekToNext(source: WeekId): Int {
        val target = source.next()
        return write { state ->
            val changes = ArrayList<Pair<String, String>>()
            for (member in state.members()) {
                source.days.zip(target.days).forEach { (from, to) ->
                    if (!PlanKeys.isValidDate(from) || !PlanKeys.isValidDate(to)) return@forEach
                    val value = state.shift(member.id, from)?.code ?: ""
                    changes += PlanKeys.shift(member.id, to) to value
                }
            }
            changes
        }
    }

    override suspend fun mergeRemote(bucket: String, entries: Map<String, Entry>): Boolean {
        val changed = mutex.withLock {
            val result = _state.value.merge(bucket, entries)
            if (result.changedKeys.isEmpty()) return@withLock false
            _state.value = result.state
            var maxTimestamp = 0L
            for (key in result.changedKeys) {
                val ts = entries.getValue(key).timestamp
                if (ts > maxTimestamp) maxTimestamp = ts
                // Ein neuerer Eintrag von aussen hat die eigene Änderung überholt.
                pending.remove(key)
            }
            hybridClock.observe(maxTimestamp)
            true
        }
        if (changed) saveRequests.trySend(Unit)
        return changed
    }

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

    /** Berechnet die Änderungen aus dem aktuellen Stand und schreibt sie – alles unter der Sperre. */
    private suspend fun write(compute: (PlanState) -> List<Pair<String, String>>): Int {
        val device = deviceId ?: throw IllegalStateException("Kein Team aktiv")
        var written = 0
        mutex.withLock {
            var state = _state.value
            for ((key, value) in compute(state)) {
                // Unveränderte Felder nicht neu schreiben (spart Sync-Verkehr, vermeidet unnötige Konflikte).
                if (state.value(key) == value) continue
                val entry = Entry(value, hybridClock.next(), device)
                state = state.withEntry(key, entry)
                pending[key] = entry.timestamp
                written++
            }
            _state.value = state
        }
        if (written > 0) {
            _localChanges.tryEmit(Unit)
            saveRequests.trySend(Unit)
        }
        return written
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

    companion object {
        private const val TAG = "PlanRepository"
    }
}
