package ch.digitana.dienstplan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.StorageState
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
    val hasTeam: Boolean = false,
    val storageReset: Boolean = false,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<MainUiState> =
        combine(container.storageState, container.teamRepository.team) { storage, team ->
            MainUiState(
                loading = storage == StorageState.LOADING,
                hasTeam = team != null,
                storageReset = storage == StorageState.RESET_AFTER_ERROR,
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

    fun consumeJustCreatedTeam(): Boolean = container.consumeJustCreatedTeam()
}
