package ch.digitana.dienstplan.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.core.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PlanUiState(
    val week: WeekModel,
    val sync: SyncStatus,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /** Person, die dieses Gerät benutzt („Das bin ich“). */
    val myMemberId: String? = null,
)

sealed interface PlanMessage {
    data class WeekCopied(val target: WeekId, val changedFields: Int) : PlanMessage
    data object Failed : PlanMessage
}

class PlanViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.planRepository
    private val today = MutableStateFlow(LocalDate.now())
    private val selectedWeek = MutableStateFlow(clamp(WeekId.of(LocalDate.now())))

    private val _messages = MutableSharedFlow<PlanMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<PlanMessage> = _messages.asSharedFlow()

    val uiState: StateFlow<PlanUiState> =
        combine(
            repository.state,
            selectedWeek,
            today,
            container.syncController.status,
            container.settingsRepository.settings,
        ) { state, week, day, sync, settings ->
            PlanUiState(
                week = WeekModel.build(state, week, day),
                sync = sync,
                canGoBack = week > WeekModel.FIRST_WEEK,
                canGoForward = week < WeekModel.LAST_WEEK,
                myMemberId = settings.myMemberId,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = PlanUiState(
                    week = WeekModel.build(repository.state.value, selectedWeek.value, today.value),
                    sync = container.syncController.status.value,
                    canGoBack = selectedWeek.value > WeekModel.FIRST_WEEK,
                    canGoForward = selectedWeek.value < WeekModel.LAST_WEEK,
                    myMemberId = container.settingsRepository.settings.value.myMemberId,
                ),
            )

    fun previousWeek() = selectedWeek.update { if (it > WeekModel.FIRST_WEEK) it.previous() else it }

    fun nextWeek() = selectedWeek.update { if (it < WeekModel.LAST_WEEK) it.next() else it }

    /** Tipp auf die Kalenderwoche: zurück zu heute. */
    fun goToToday() {
        refreshToday()
        selectedWeek.value = clamp(WeekId.of(today.value))
    }

    /** Beim Fortsetzen der App und jede Minute – damit „heute“ auch nach Mitternacht stimmt. */
    fun refreshToday() {
        today.value = LocalDate.now()
    }

    fun cycleShift(memberId: String, date: LocalDate) = launchWrite { repository.cycleShift(memberId, date) }

    fun clearShift(memberId: String, date: LocalDate) = launchWrite { repository.setShift(memberId, date, null) }

    fun nameProblem(input: String): NameProblem? = Names.checkLocalInput(input)

    fun addMember(name: String) = launchWrite { repository.addMember(name) }

    fun renameMember(id: String, name: String) = launchWrite { repository.renameMember(id, name) }

    fun deleteMember(id: String) = launchWrite { repository.deleteMember(id) }

    fun nextWeekHasEntries(): Boolean = repository.hasEntries(selectedWeek.value.next())

    fun nextWeekId(): WeekId = selectedWeek.value.next()

    /** „Woche kopieren“: Die Folgewoche wird identisch mit der angezeigten Woche. */
    fun copyWeekToNext() = launchWrite {
        val source = selectedWeek.value
        val changed = repository.copyWeekToNext(source)
        _messages.emit(PlanMessage.WeekCopied(source.next(), changed))
    }

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

    private companion object {
        const val TAG = "PlanViewModel"

        fun clamp(week: WeekId): WeekId = when {
            week < WeekModel.FIRST_WEEK -> WeekModel.FIRST_WEEK
            week > WeekModel.LAST_WEEK -> WeekModel.LAST_WEEK
            else -> week
        }
    }
}
