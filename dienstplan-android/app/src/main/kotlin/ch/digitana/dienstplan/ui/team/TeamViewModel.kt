package ch.digitana.dienstplan.ui.team

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crypto.InviteCode
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.data.DeviceSettings
import ch.digitana.dienstplan.core.data.Team
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ergebnis der Prüfung eines eingegebenen Einladungscodes. */
sealed interface JoinCheck {
    data class Error(@StringRes val message: Int) : JoinCheck
    /** Gültig, aber auf dem Gerät liegt schon ein Plan: Übernehmen oder Verwerfen? */
    class NeedsDecision(val secret: TeamSecret) : JoinCheck
    class Ready(val secret: TeamSecret) : JoinCheck
}

sealed interface TeamEvent {
    data object Created : TeamEvent
    data object Joined : TeamEvent
    data object Rotated : TeamEvent
    data object Left : TeamEvent
    data object Failed : TeamEvent
}

class TeamViewModel(private val container: AppContainer) : ViewModel() {

    val team: StateFlow<Team?> = container.teamRepository.team

    val members: StateFlow<List<Member>> = container.planRepository.state
        .map { it.members() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.planRepository.state.value.members())

    val settings: StateFlow<DeviceSettings> = container.settingsRepository.settings

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<TeamEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TeamEvent> = _events.asSharedFlow()

    fun checkCode(input: String): JoinCheck = when (val result = InviteCode.parse(input)) {
        is InviteCode.ParseResult.Invalid -> JoinCheck.Error(result.problem.messageRes())
        is InviteCode.ParseResult.Valid -> when {
            result.secret == team.value?.secret -> JoinCheck.Error(R.string.invite_error_same_team)
            !container.planRepository.state.value.isEmpty() -> JoinCheck.NeedsDecision(result.secret)
            else -> JoinCheck.Ready(result.secret)
        }
    }

    fun createTeam() = perform(TeamEvent.Created) { container.createTeam() }

    fun join(secret: TeamSecret, keepLocalData: Boolean) = perform(TeamEvent.Joined) { container.joinTeam(secret, keepLocalData) }

    fun rotate() = perform(TeamEvent.Rotated) { container.rotateTeamKey() }

    fun leave() = perform(TeamEvent.Left) { container.leaveTeam() }

    /** „Das bin ich“; null = niemand. */
    fun setMyMember(memberId: String?) {
        viewModelScope.launch { container.shiftAlerts.configure(memberId, settings.value.notifyOnChanges) }
    }

    fun setNotifyOnChanges(enabled: Boolean) {
        viewModelScope.launch { container.shiftAlerts.configure(settings.value.myMemberId, enabled) }
    }

    private fun perform(success: TeamEvent, action: suspend () -> Any) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
                _events.emit(success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.logger.warn(TAG, "Teamaktion fehlgeschlagen", e)
                _events.emit(TeamEvent.Failed)
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "TeamViewModel"
    }
}

@StringRes
fun InviteCode.Problem.messageRes(): Int = when (this) {
    InviteCode.Problem.EMPTY -> R.string.invite_error_empty
    InviteCode.Problem.MISSING_PREFIX -> R.string.invite_error_prefix
    InviteCode.Problem.INVALID_CHARACTERS -> R.string.invite_error_characters
    InviteCode.Problem.WRONG_LENGTH -> R.string.invite_error_length
    InviteCode.Problem.CHECKSUM_MISMATCH -> R.string.invite_error_checksum
}
