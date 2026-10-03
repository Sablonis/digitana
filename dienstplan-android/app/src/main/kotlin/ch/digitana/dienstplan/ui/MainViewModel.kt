package ch.digitana.dienstplan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.StorageState
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.crash.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val loading: Boolean = true,
    val team: TeamState = TeamState.Loading,
    val storageReset: Boolean = false,
    /** Einmaliger Hinweis nach dem Wechsel von der alten Teamversion (DP2). */
    val upgraded: Boolean = false,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<MainUiState> =
        combine(container.storageState, container.teamRepository.state, container.upgradedFromDp2) { storage, team, upgraded ->
            MainUiState(
                loading = storage == StorageState.LOADING || team == TeamState.Loading,
                team = team,
                storageReset = storage == StorageState.RESET_AFTER_ERROR,
                upgraded = upgraded,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    private val _crashReport = MutableStateFlow<String?>(null)
    val crashReport: StateFlow<String?> = _crashReport.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _crashReport.value = CrashReporter.pendingReport(container.context)
        }
    }

    fun dismissCrashReport() {
        _crashReport.value = null
        viewModelScope.launch(Dispatchers.IO) { CrashReporter.clear(container.context) }
    }

    fun acknowledgeStorageReset() = container.acknowledgeStorageReset()

    fun acknowledgeUpgrade() = container.acknowledgeUpgrade()

    fun consumeJustCreatedTeam(): Boolean = container.consumeJustCreatedTeam()
}
