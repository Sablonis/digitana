package ch.digitana.dienstplan.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.core.crdt.Canton
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.Notes
import ch.digitana.dienstplan.core.crdt.PlanAccess
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanLock
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.data.Batch
import ch.digitana.dienstplan.core.data.PlanLockedException
import ch.digitana.dienstplan.core.data.TradeException
import ch.digitana.dienstplan.core.data.TradeNotAllowedException
import ch.digitana.dienstplan.core.data.TradeOutcome
import ch.digitana.dienstplan.core.data.WishNotAllowedException
import ch.digitana.dienstplan.core.group.TeamOperationException
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.plan.MonthModel
import ch.digitana.dienstplan.core.plan.PlanSuggestion
import ch.digitana.dienstplan.core.plan.Suggestion
import ch.digitana.dienstplan.core.plan.TradeProblem
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.core.plan.WishRights
import ch.digitana.dienstplan.core.sync.SyncStatus
import ch.digitana.dienstplan.ui.GridDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** Markierungen im Raster und Darstellung auf diesem Gerät. */
data class PlanMarkers(
    /** Eigene Änderungen, die noch kein Relay bestätigt hat. */
    val pending: Set<String> = emptySet(),
    /** Änderungen anderer Geräte, die hier noch niemand angesehen hat. */
    val unseen: Set<String> = emptySet(),
    val density: GridDensity = GridDensity.COMFORTABLE,
)

/** Alles, was Woche, Monat und „Ich“ gemeinsam brauchen. */
data class PlanUiState(
    val plan: PlanState,
    val today: LocalDate,
    val sync: SyncStatus,
    /** Person, die dieses Gerät benutzt („Das bin ich“). */
    val myMemberId: String? = null,
    val notifyOnChanges: Boolean = false,
    /** Aus dem Team entfernt: Name des Teams; der Plan ist dann nur noch lesbar. */
    val removedFrom: String? = null,
    val teamName: String? = null,
    /** Sperre und Admins des Teams. */
    val access: PlanAccess = PlanAccess.OPEN,
    /** Geräte-ID dieses Geräts in den Planeinträgen (nur als Mitglied). */
    val deviceId: String? = null,
    val markers: PlanMarkers = PlanMarkers(),
    /** Gesehene Tipps (Bits, siehe [Tips]). */
    val tipsSeen: Int = 0,
) {
    val readOnly: Boolean get() = removedFrom != null

    val lock: PlanLock? get() = access.lock

    val isAdmin: Boolean get() = access.isAdmin(deviceId)

    private val owners: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) { plan.deviceOwners() }

    /** Schicht an diesem Tag ändern (nicht gesperrt oder Admin)? */
    fun canEditShift(date: LocalDate): Boolean = !readOnly && access.mayEditShift(deviceId, date)

    val canEditShiftTypes: Boolean get() = !readOnly && access.mayEditShiftTypes(deviceId)

    val canDeleteMembers: Boolean get() = !readOnly && access.mayDeleteMembers(deviceId)

    /** Planungsregeln und Pensen ändern (nicht gesperrt oder Admin)? */
    val canEditRules: Boolean get() = !readOnly && access.mayEditShiftTypes(deviceId)

    /** Wünsche dieser Person ändern (eigene, Personen ohne Gerät oder als Admin)? */
    fun canEditWishes(memberId: String): Boolean = !readOnly && WishRights.mayEdit(owners, memberId, deviceId, isAdmin)

    /** Für diese Person abgeben oder tauschen (gleiche Regel wie bei Wünschen)? */
    fun canTradeFor(memberId: String): Boolean = canEditWishes(memberId)
}

/** Kurze Hinweise beim ersten Öffnen; Bits in `DeviceSettings.tipsSeen`. */
object Tips {
    const val SWIPE = 1
    const val CELL = 2
    const val BRUSH = 4
    val ALL: List<Int> = listOf(SWIPE, CELL, BRUSH)
}

/** Was eine rückgängig machbare Einzeländerung war (für den Text der Snackbar). */
enum class ChangeKind { SHIFT, WISH, NOTE, MEMBER_DELETED, CLAIMED, OFFERED }

sealed interface PlanMessage {
    data class WeekCopied(val target: WeekId, val batch: Batch) : PlanMessage
    data class PatternApplied(val batch: Batch) : PlanMessage
    /** Einzelne Änderung mit „Rückgängig“. */
    data class Changed(val kind: ChangeKind, val batch: Batch) : PlanMessage
    /** Pinselstrich (ein oder mehrere Felder) mit „Rückgängig“. */
    data class Painted(val batch: Batch) : PlanMessage
    data class SuggestionApplied(val batch: Batch) : PlanMessage
    data class Undone(val changedFields: Int) : PlanMessage
    /** Eigene Änderungen, die eine neue Sperre ungültig gemacht hat. */
    data class Discarded(val count: Int) : PlanMessage
    data class LockChanged(val locked: Boolean) : PlanMessage
    data class Trade(val outcome: TradeOutcome) : PlanMessage
    data class TradeFailed(val problem: TradeProblem) : PlanMessage
    data object SwapProposed : PlanMessage
    data object SwapDeclined : PlanMessage
    data object TradeNotAllowed : PlanMessage
    data object Locked : PlanMessage
    data object WeekLocked : PlanMessage
    data object WishNotAllowed : PlanMessage
    data class TeamFailed(val reason: TeamOperationException.Reason) : PlanMessage
    data object Failed : PlanMessage
}

/** Ein angetipptes Feld: Person und Tag. */
data class CellRef(val memberId: String, val date: LocalDate)

/** Schlüssel eines Feldes, die als „neu“ oder „wird gesendet“ markiert sein können. */
fun CellRef.keys(): List<String> =
    if (!PlanKeys.isValidDate(date)) emptyList() else listOf(
        PlanKeys.shift(memberId, date),
        PlanKeys.wish(memberId, date),
        PlanKeys.memberNote(memberId, date),
        PlanKeys.offer(memberId, date),
    )

class PlanViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.planRepository
    private val today = MutableStateFlow(LocalDate.now())

    private val _selectedWeek = MutableStateFlow(clamp(WeekId.of(LocalDate.now())))
    val selectedWeek: StateFlow<WeekId> = _selectedWeek.asStateFlow()

    private val _selectedMonth = MutableStateFlow(clampMonth(YearMonth.now()))
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

    private val _messages = MutableSharedFlow<PlanMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<PlanMessage> = _messages.asSharedFlow()

    private val _lockBusy = MutableStateFlow(false)

    /** Eine Änderung der Sperre läuft (Commit über die Relays). */
    val lockBusy: StateFlow<Boolean> = _lockBusy.asStateFlow()

    private val _suggestion = MutableStateFlow<Suggestion?>(null)

    /** Plan-Vorschlag zur Vorschau; null = keiner. */
    val suggestion: StateFlow<Suggestion?> = _suggestion.asStateFlow()

    private val markers = combine(repository.pendingKeys, repository.unseenKeys, container.uiPreferences.state) { pending, unseen, ui ->
        PlanMarkers(pending, unseen, ui.density)
    }

    val uiState: StateFlow<PlanUiState> =
        combine(
            combine(repository.state, repository.access, markers) { plan, access, marks -> Triple(plan, access, marks) },
            today,
            container.syncController.status,
            container.settingsRepository.settings,
            container.teamRepository.state,
        ) { (plan, access, marks), day, sync, settings, team ->
            buildState(plan, access, day, sync, settings.myMemberId, settings.notifyOnChanges, team, marks, settings.tipsSeen)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildState(
                repository.state.value,
                repository.access.value,
                today.value,
                container.syncController.status.value,
                container.settingsRepository.settings.value.myMemberId,
                container.settingsRepository.settings.value.notifyOnChanges,
                container.teamRepository.state.value,
                PlanMarkers(repository.pendingKeys.value, repository.unseenKeys.value, container.uiPreferences.state.value.density),
                container.settingsRepository.settings.value.tipsSeen,
            ),
        )

    // Pinselstriche: Jeder Strich (Antippen oder Wischen über eine Zeile) ist ein Schritt zurück.
    private val strokeMutex = Mutex()
    private var stroke: MutableList<Batch>? = null
    private val strokes = ArrayDeque<Batch>()
    private val _canUndoStroke = MutableStateFlow(false)

    /** Gibt es einen Pinselstrich, der sich zurücknehmen lässt? */
    val canUndoStroke: StateFlow<Boolean> = _canUndoStroke.asStateFlow()

    init {
        // Eine neue Sperre hat eigene Änderungen ungültig gemacht: Bescheid geben.
        viewModelScope.launch { repository.discarded.collect { _messages.emit(PlanMessage.Discarded(it)) } }
    }

    private fun buildState(
        plan: PlanState,
        access: PlanAccess,
        day: LocalDate,
        sync: SyncStatus,
        myMemberId: String?,
        notify: Boolean,
        team: TeamState,
        marks: PlanMarkers,
        tipsSeen: Int,
    ) = PlanUiState(
        plan = plan,
        today = day,
        sync = sync,
        myMemberId = myMemberId,
        notifyOnChanges = notify,
        removedFrom = (team as? TeamState.Removed)?.teamName,
        teamName = (team as? TeamState.Member)?.team?.name,
        access = access,
        deviceId = if (team is TeamState.Member) container.teamRepository.deviceId else null,
        markers = marks,
        tipsSeen = tipsSeen,
    )

    fun selectWeek(week: WeekId) {
        _selectedWeek.value = clamp(week)
    }

    fun selectMonth(month: YearMonth) {
        _selectedMonth.value = clampMonth(month)
    }

    fun goToThisWeek() {
        refreshToday()
        _selectedWeek.value = clamp(WeekId.of(today.value))
    }

    fun goToThisMonth() {
        refreshToday()
        _selectedMonth.value = clampMonth(YearMonth.from(today.value))
    }

    /** Beim Fortsetzen der App und jede Minute – damit „heute“ auch nach Mitternacht stimmt. */
    fun refreshToday() {
        today.value = LocalDate.now()
    }

    /** Schicht im Eintragsfenster: mit „Rückgängig“ in der Snackbar. */
    fun setShift(cell: CellRef, typeId: String?) = launchWrite {
        changed(ChangeKind.SHIFT, repository.setShift(cell.memberId, cell.date, typeId))
    }

    /** Langes Drücken leert das Feld; ebenfalls rückgängig machbar. */
    fun clearShift(cell: CellRef) = setShift(cell, null)

    fun setWish(cell: CellRef, wish: Wish?) = launchWrite { changed(ChangeKind.WISH, repository.setWish(cell.memberId, cell.date, wish)) }

    fun setMemberNote(cell: CellRef, note: String) = launchWrite {
        changed(ChangeKind.NOTE, repository.setMemberNote(cell.memberId, cell.date, note))
    }

    fun setDayNote(date: LocalDate, note: String) = launchWrite { changed(ChangeKind.NOTE, repository.setDayNote(date, note)) }

    fun nameProblem(input: String): NameProblem? = Names.checkLocalInput(input)

    fun noteProblem(input: String): NameProblem? = Notes.checkLocalInput(input)

    fun addMember(name: String) = launchWrite { repository.addMember(name) }

    /** Neue Person anlegen und gleich als „Das bin ich“ wählen (beim Einstieg). */
    fun addMemberAsMe(name: String) = launchWrite {
        val id = repository.addMember(name)
        container.shiftAlerts.configure(id, container.settingsRepository.settings.value.notifyOnChanges)
    }

    fun renameMember(id: String, name: String) = launchWrite { repository.renameMember(id, name) }

    fun deleteMember(id: String) = launchWrite { changed(ChangeKind.MEMBER_DELETED, repository.deleteMember(id)) }

    /** „Das bin ich“ (null = niemand); die Benachrichtigung bleibt, wie sie war. */
    fun setMe(memberId: String?) = launchWrite {
        container.shiftAlerts.configure(memberId, container.settingsRepository.settings.value.notifyOnChanges)
    }

    fun nextWeekHasEntries(week: WeekId): Boolean = repository.hasEntries(week.next())

    /** Hinweis aus der Oberfläche (z. B. ein gesperrter Tag beim schnellen Eintragen). */
    fun notify(message: PlanMessage) {
        _messages.tryEmit(message)
    }

    /** „Woche kopieren“: Die Folgewoche wird identisch mit [week]. */
    fun copyWeekToNext(week: WeekId) = launchWrite {
        val batch = repository.copyWeekToNext(week)
        _messages.emit(PlanMessage.WeekCopied(week.next(), batch))
    }

    /** Änderung zurücknehmen (aus der Snackbar). */
    fun undo(batch: Batch) = launchWrite {
        _messages.emit(PlanMessage.Undone(repository.undo(batch)))
    }

    /** Beginn eines Pinselstrichs (Antippen oder Wischen beim schnellen Eintragen). */
    fun beginStroke() {
        viewModelScope.launch { strokeMutex.withLock { stroke = ArrayList() } }
    }

    /** Ein Feld mit dem Pinsel setzen; gehört zum laufenden Strich. */
    fun paint(cell: CellRef, typeId: String?) = launchWrite {
        val batch = repository.setShift(cell.memberId, cell.date, typeId)
        strokeMutex.withLock {
            val current = stroke
            if (current != null) {
                current.add(batch)
            } else if (batch.changed > 0) {
                // Strich schon beendet (Schreiben hat gewartet): als eigener Schritt.
                strokes.addLast(batch)
                _canUndoStroke.value = true
            }
        }
    }

    /** Ende des Strichs: als ein Schritt zum Zurücknehmen merken. */
    fun endStroke() {
        viewModelScope.launch {
            val batch = strokeMutex.withLock {
                val combined = Batch.combine(stroke.orEmpty())
                stroke = null
                if (combined.changed > 0) {
                    strokes.addLast(combined)
                    while (strokes.size > MAX_STROKES) strokes.removeFirst()
                }
                _canUndoStroke.value = strokes.isNotEmpty()
                combined
            }
            if (batch.changed > 1) _messages.emit(PlanMessage.Painted(batch))
        }
    }

    /** Letzten Pinselstrich zurücknehmen (Knopf in der Pinselleiste). */
    fun undoLastStroke() = launchWrite {
        val batch = strokeMutex.withLock {
            val last = strokes.removeLastOrNull()
            _canUndoStroke.value = strokes.isNotEmpty()
            last
        } ?: return@launchWrite
        _messages.emit(PlanMessage.Undone(repository.undo(batch)))
    }

    /** Diese Änderungen anderer Geräte wurden angesehen. */
    fun markSeen(keys: Collection<String>) {
        if (keys.none { it in uiState.value.markers.unseen }) return
        viewModelScope.launch { repository.markSeen(keys) }
    }

    fun markAllSeen() {
        viewModelScope.launch { repository.markAllSeen() }
    }

    /** Tipp ausblenden. */
    fun dismissTip(bit: Int) {
        viewModelScope.launch { container.settingsRepository.update { it.copy(tipsSeen = it.tipsSeen or bit) } }
    }

    // --- Offene Dienste, Abgeben und Tauschen ---

    /** Offenen Dienst für [memberId] übernehmen („Ich übernehme“). */
    fun claimOpenShift(memberId: String, date: LocalDate, typeId: String) = launchWrite {
        changed(ChangeKind.CLAIMED, repository.claimOpenShift(memberId, date, typeId))
    }

    fun offerShift(cell: CellRef) = launchWrite { changed(ChangeKind.OFFERED, repository.offerShift(cell.memberId, cell.date)) }

    fun withdrawOffer(cell: CellRef) = launchWrite { repository.withdrawOffer(cell.memberId, cell.date) }

    fun takeOffer(giverId: String, date: LocalDate, takerId: String) = launchWrite {
        _messages.emit(PlanMessage.Trade(repository.takeOffer(giverId, date, takerId)))
    }

    fun approveClaim(giverId: String, date: LocalDate) = launchWrite {
        repository.approveClaim(giverId, date)
        _messages.emit(PlanMessage.Trade(TradeOutcome.DONE))
    }

    fun rejectClaim(giverId: String, date: LocalDate) = launchWrite { repository.rejectClaim(giverId, date) }

    fun proposeSwap(fromMember: String, fromDate: LocalDate, toMember: String, toDate: LocalDate) = launchWrite {
        repository.proposeSwap(fromMember, fromDate, toMember, toDate)
        _messages.emit(PlanMessage.SwapProposed)
    }

    fun answerSwap(swap: PlanKey.Swap, accept: Boolean) = launchWrite {
        val outcome = repository.answerSwap(swap, accept)
        _messages.emit(if (accept) PlanMessage.Trade(outcome) else PlanMessage.SwapDeclined)
    }

    fun executeSwap(swap: PlanKey.Swap) = launchWrite {
        repository.executeSwap(swap)
        _messages.emit(PlanMessage.Trade(TradeOutcome.DONE))
    }

    fun withdrawSwap(swap: PlanKey.Swap) = launchWrite { repository.withdrawSwap(swap) }

    // --- Plan-Vorschlag ---

    /** Vorschlag für die Tage [dates] berechnen (nur Tage, die dieses Gerät ändern darf). */
    fun suggest(dates: List<LocalDate>) {
        val state = uiState.value
        val device = state.deviceId ?: return
        viewModelScope.launch {
            val suggestion = withContext(Dispatchers.Default) {
                PlanSuggestion.suggest(state.plan, dates, device) { state.canEditShift(it) }
            }
            _suggestion.value = suggestion
        }
    }

    fun discardSuggestion() {
        _suggestion.value = null
    }

    fun applySuggestion() {
        val suggestion = _suggestion.value ?: return
        _suggestion.value = null
        launchWrite { _messages.emit(PlanMessage.SuggestionApplied(repository.applySuggestion(suggestion))) }
    }

    /** Sperren bis [until] (null = ganzer Plan) oder öffnen ([locked] = false); nur Admins. */
    fun setPlanLock(locked: Boolean, until: LocalDate?) {
        if (_lockBusy.value) return
        _lockBusy.value = true
        viewModelScope.launch {
            try {
                container.setPlanLock(locked, until)
                _messages.emit(PlanMessage.LockChanged(locked))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TeamOperationException) {
                _messages.emit(PlanMessage.TeamFailed(e.reason))
            } catch (e: Exception) {
                container.logger.warn(TAG, "Sperre nicht geändert", e)
                _messages.emit(PlanMessage.Failed)
            } finally {
                _lockBusy.value = false
            }
        }
    }

    fun newShiftTypeId(): String = repository.newShiftTypeId()

    fun saveShiftType(type: ShiftType) = launchWrite { repository.saveShiftType(type) }

    fun resetShiftType(typeId: String) = launchWrite { repository.resetShiftType(typeId) }

    /** Mindestruhezeit in Minuten (0 = keine Warnung). */
    fun setRestMinutes(minutes: Int) = launchWrite { repository.setRestMinutes(minutes) }

    /** Soll-Besetzung einer Schichtart pro Wochentag (Montag zuerst). */
    fun setTargets(typeId: String, targets: List<Int>) = launchWrite { repository.setTargets(typeId, targets) }

    /** Wochenstunden bei 100 % in Minuten; null = Standard. */
    fun setWeekMinutes(minutes: Int?) = launchWrite { repository.setWeekMinutes(minutes) }

    fun setCanton(canton: Canton?) = launchWrite { repository.setCanton(canton) }

    fun setWishDeadline(month: YearMonth, deadline: LocalDate?) = launchWrite { repository.setWishDeadline(month, deadline) }

    fun setPensum(memberId: String, pensum: Int?) = launchWrite { repository.setPensum(memberId, pensum) }

    fun newPatternId(): String = repository.newPatternId()

    fun savePattern(pattern: ShiftPattern) = launchWrite { repository.savePattern(pattern) }

    fun deletePattern(patternId: String) = launchWrite { repository.deletePattern(patternId) }

    fun applyPattern(pattern: ShiftPattern, memberIds: List<String>, start: LocalDate, weeks: Int, overwrite: Boolean) = launchWrite {
        val batch = repository.applyPattern(pattern, memberIds, start, weeks, overwrite)
        _messages.emit(PlanMessage.PatternApplied(batch))
    }

    /** Nach dem Entfernen aus dem Team: alles Lokale löschen. */
    fun deleteLocalData() = launchWrite { container.deleteLocalData() }

    private suspend fun changed(kind: ChangeKind, batch: Batch) {
        if (batch.changed > 0) _messages.emit(PlanMessage.Changed(kind, batch))
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlanLockedException) {
                _messages.emit(PlanMessage.Locked)
            } catch (e: WishNotAllowedException) {
                _messages.emit(PlanMessage.WishNotAllowed)
            } catch (e: TradeNotAllowedException) {
                _messages.emit(PlanMessage.TradeNotAllowed)
            } catch (e: TradeException) {
                _messages.emit(PlanMessage.TradeFailed(e.problem))
            } catch (e: Exception) {
                container.logger.warn(TAG, "Änderung fehlgeschlagen", e)
                _messages.emit(PlanMessage.Failed)
            }
        }
    }

    companion object {
        private const val TAG = "PlanViewModel"
        private const val MAX_STROKES = 30

        fun clamp(week: WeekId): WeekId = when {
            week < WeekModel.FIRST_WEEK -> WeekModel.FIRST_WEEK
            week > WeekModel.LAST_WEEK -> WeekModel.LAST_WEEK
            else -> week
        }

        fun clampMonth(month: YearMonth): YearMonth = when {
            month < MonthModel.FIRST_MONTH -> MonthModel.FIRST_MONTH
            month > MonthModel.LAST_MONTH -> MonthModel.LAST_MONTH
            else -> month
        }
    }
}
