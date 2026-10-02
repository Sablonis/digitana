package ch.digitana.dienstplan.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.Notes
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.plan.MonthModel
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.core.sync.SyncStatus
import kotlinx.coroutines.CancellationException
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
import java.time.LocalDate
import java.time.YearMonth

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
) {
    val readOnly: Boolean get() = removedFrom != null
}

sealed interface PlanMessage {
    data class WeekCopied(val target: WeekId, val changedFields: Int) : PlanMessage
    data class PatternApplied(val changedFields: Int) : PlanMessage
    data object Failed : PlanMessage
}

/** Ein angetipptes Feld: Person und Tag. */
data class CellRef(val memberId: String, val date: LocalDate)

class PlanViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.planRepository
    private val today = MutableStateFlow(LocalDate.now())

    private val _selectedWeek = MutableStateFlow(clamp(WeekId.of(LocalDate.now())))
    val selectedWeek: StateFlow<WeekId> = _selectedWeek.asStateFlow()

    private val _selectedMonth = MutableStateFlow(clampMonth(YearMonth.now()))
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

    private val _messages = MutableSharedFlow<PlanMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<PlanMessage> = _messages.asSharedFlow()

    val uiState: StateFlow<PlanUiState> =
        combine(
            repository.state,
            today,
            container.syncController.status,
            container.settingsRepository.settings,
            container.teamRepository.state,
        ) { plan, day, sync, settings, team ->
            PlanUiState(
                plan = plan,
                today = day,
                sync = sync,
                myMemberId = settings.myMemberId,
                notifyOnChanges = settings.notifyOnChanges,
                removedFrom = (team as? TeamState.Removed)?.teamName,
                teamName = (team as? TeamState.Member)?.team?.name,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlanUiState(
                plan = repository.state.value,
                today = today.value,
                sync = container.syncController.status.value,
                myMemberId = container.settingsRepository.settings.value.myMemberId,
                notifyOnChanges = container.settingsRepository.settings.value.notifyOnChanges,
                removedFrom = (container.teamRepository.state.value as? TeamState.Removed)?.teamName,
                teamName = (container.teamRepository.state.value as? TeamState.Member)?.team?.name,
            ),
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

    fun setShift(cell: CellRef, typeId: String?) = launchWrite { repository.setShift(cell.memberId, cell.date, typeId) }

    fun setWish(cell: CellRef, wish: Wish?) = launchWrite { repository.setWish(cell.memberId, cell.date, wish) }

    fun setMemberNote(cell: CellRef, note: String) = launchWrite { repository.setMemberNote(cell.memberId, cell.date, note) }

    fun setDayNote(date: LocalDate, note: String) = launchWrite { repository.setDayNote(date, note) }

    fun nameProblem(input: String): NameProblem? = Names.checkLocalInput(input)

    fun noteProblem(input: String): NameProblem? = Notes.checkLocalInput(input)

    fun addMember(name: String) = launchWrite { repository.addMember(name) }

    fun renameMember(id: String, name: String) = launchWrite { repository.renameMember(id, name) }

    fun deleteMember(id: String) = launchWrite { repository.deleteMember(id) }

    /** „Das bin ich“ (null = niemand); die Benachrichtigung bleibt, wie sie war. */
    fun setMe(memberId: String?) = launchWrite {
        container.shiftAlerts.configure(memberId, container.settingsRepository.settings.value.notifyOnChanges)
    }

    fun nextWeekHasEntries(week: WeekId): Boolean = repository.hasEntries(week.next())

    /** „Woche kopieren“: Die Folgewoche wird identisch mit [week]. */
    fun copyWeekToNext(week: WeekId) = launchWrite {
        val changed = repository.copyWeekToNext(week)
        _messages.emit(PlanMessage.WeekCopied(week.next(), changed))
    }

    fun newShiftTypeId(): String = repository.newShiftTypeId()

    fun saveShiftType(type: ShiftType) = launchWrite { repository.saveShiftType(type) }

    fun resetShiftType(typeId: String) = launchWrite { repository.resetShiftType(typeId) }

    fun newPatternId(): String = repository.newPatternId()

    fun savePattern(pattern: ShiftPattern) = launchWrite { repository.savePattern(pattern) }

    fun deletePattern(patternId: String) = launchWrite { repository.deletePattern(patternId) }

    fun applyPattern(pattern: ShiftPattern, memberIds: List<String>, start: LocalDate, weeks: Int, overwrite: Boolean) = launchWrite {
        val changed = repository.applyPattern(pattern, memberIds, start, weeks, overwrite)
        _messages.emit(PlanMessage.PatternApplied(changed))
    }

    /** Nach dem Entfernen aus dem Team: alles Lokale löschen. */
    fun deleteLocalData() = launchWrite { container.deleteLocalData() }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.logger.warn(TAG, "Änderung fehlgeschlagen", e)
                _messages.emit(PlanMessage.Failed)
            }
        }
    }

    companion object {
        private const val TAG = "PlanViewModel"

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
