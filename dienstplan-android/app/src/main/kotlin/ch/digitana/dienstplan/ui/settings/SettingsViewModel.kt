package ch.digitana.dienstplan.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.BuildConfig
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.data.DeviceSettings
import ch.digitana.dienstplan.core.plan.ReminderMode
import ch.digitana.dienstplan.ui.GridDensity
import ch.digitana.dienstplan.ui.UiPrefs
import ch.digitana.dienstplan.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Einstellungen dieses Geräts: Darstellung, Benachrichtigungen, Erinnerungen, Kalender. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val ui: StateFlow<UiPrefs> = container.uiPreferences.state

    val settings: StateFlow<DeviceSettings> = container.settingsRepository.settings

    /** Person, die dieses Gerät benutzt („Das bin ich“); null = noch nicht gewählt. */
    val me: StateFlow<Member?> = combine(container.planRepository.state, container.settingsRepository.settings) { plan, current ->
        plan.members().firstOrNull { it.id == current.myMemberId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val versionName: String = BuildConfig.VERSION_NAME

    fun setThemeMode(mode: ThemeMode) = container.uiPreferences.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = container.uiPreferences.setDynamicColor(enabled)

    fun setDensity(density: GridDensity) = container.uiPreferences.setDensity(density)

    fun setNotifyOnChanges(enabled: Boolean) {
        viewModelScope.launch { container.shiftAlerts.configure(settings.value.myMemberId, enabled) }
    }

    fun setReminderMode(mode: ReminderMode) = update { it.copy(reminderMode = mode) }

    fun setDeadlineReminders(enabled: Boolean) = update { it.copy(deadlineReminders = enabled) }

    fun setTradeAlerts(enabled: Boolean) = update { it.copy(tradeAlerts = enabled) }

    fun setPublishAlerts(enabled: Boolean) = update { it.copy(publishAlerts = enabled) }

    fun setCalendarSync(enabled: Boolean) = update { it.copy(calendarSync = enabled) }

    /** Tipps beim nächsten Öffnen wieder zeigen. */
    fun resetTips() = update { it.copy(tipsSeen = 0) }

    private fun update(transform: (DeviceSettings) -> DeviceSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }
}
