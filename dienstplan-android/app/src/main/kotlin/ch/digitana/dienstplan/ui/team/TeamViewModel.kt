package ch.digitana.dienstplan.ui.team

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.data.DeviceSettings
import ch.digitana.dienstplan.core.group.DeviceKeys
import ch.digitana.dienstplan.core.group.Fingerprint
import ch.digitana.dienstplan.core.group.GroupDiagnostics
import ch.digitana.dienstplan.core.group.JoinCode
import ch.digitana.dienstplan.core.group.TeamOperationException
import ch.digitana.dienstplan.core.group.TeamState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ein Gerät in der Geräteliste. */
data class DeviceItem(
    val publicKey: String,
    /** Name aus dem Plan; null = ohne Namen. */
    val label: String?,
    val fingerprint: String,
    val isAdmin: Boolean,
    val isMe: Boolean,
)

/** Ergebnis der Prüfung eines eingegebenen Beitrittscodes. */
sealed interface JoinCodeCheck {
    data class Error(@StringRes val message: Int) : JoinCodeCheck
    data class Valid(val publicKey: String) : JoinCodeCheck
}

sealed interface TeamEvent {
    data object Created : TeamEvent
    data object Joined : TeamEvent
    data object DeviceAdded : TeamEvent
    data object DeviceRemoved : TeamEvent
    data object AdminChanged : TeamEvent
    data object Left : TeamEvent
    /** Der Austritt ging nicht raus (offline): nur lokal löschen anbieten. */
    data object LeaveNotSent : TeamEvent
    data class Failed(@StringRes val message: Int) : TeamEvent
}

class TeamViewModel(private val container: AppContainer) : ViewModel() {

    val teamState: StateFlow<TeamState> = container.teamRepository.state

    val members: StateFlow<List<Member>> = container.planRepository.state
        .map { it.members() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.planRepository.state.value.members())

    val devices: StateFlow<List<DeviceItem>> =
        combine(container.teamRepository.state, container.planRepository.state) { state, plan ->
            val member = state as? TeamState.Member ?: return@combine emptyList<DeviceItem>()
            val labels = plan.deviceLabels()
            member.team.members.map { publicKey ->
                DeviceItem(
                    publicKey = publicKey,
                    label = labels[DeviceKeys.deviceIdOf(publicKey)],
                    fingerprint = Fingerprint.of(publicKey),
                    isAdmin = publicKey in member.team.admins,
                    isMe = publicKey == member.me,
                )
            }.sortedWith(compareByDescending<DeviceItem> { it.isMe }.thenBy { it.label?.lowercase() ?: "￿" }.thenBy { it.publicKey })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupDiagnostics: StateFlow<GroupDiagnostics> = container.syncController.groupDiagnostics

    val settings: StateFlow<DeviceSettings> = container.settingsRepository.settings

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<TeamEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TeamEvent> = _events.asSharedFlow()

    /** Liegt auf diesem Gerät schon ein Plan (beim Annehmen einer Einladung)? */
    fun hasLocalPlan(): Boolean = !container.planRepository.state.value.isEmpty()

    fun nameProblem(input: String): NameProblem? = Names.checkLocalInput(input)

    /** Freiwilliger Gerätename: leer ist erlaubt, sonst gelten die Namensregeln. */
    fun labelProblem(input: String): NameProblem? = if (input.isBlank()) null else Names.checkLocalInput(input)

    fun checkJoinCode(input: String): JoinCodeCheck = when (val result = JoinCode.parse(input)) {
        is JoinCode.ParseResult.Invalid -> JoinCodeCheck.Error(result.problem.messageRes())
        is JoinCode.ParseResult.Valid -> when {
            result.publicKey == container.publicKey -> JoinCodeCheck.Error(R.string.join_error_self)
            result.publicKey in (teamState.value as? TeamState.Member)?.team?.members.orEmpty() ->
                JoinCodeCheck.Error(R.string.error_already_member)
            else -> JoinCodeCheck.Valid(result.publicKey)
        }
    }

    fun createTeam(name: String, deviceLabel: String) = perform(TeamEvent.Created) { container.createTeam(name, deviceLabel) }

    fun startJoining() = perform(null) { container.startJoining() }

    fun cancelJoining() = perform(null) { container.cancelJoining() }

    fun acceptInvite(inviteId: String, keepLocalData: Boolean, deviceLabel: String) =
        perform(TeamEvent.Joined) { container.acceptInvite(inviteId, keepLocalData, deviceLabel) }

    fun declineInvite(inviteId: String) = perform(null) { container.declineInvite(inviteId) }

    fun addDevice(publicKey: String, label: String) = perform(TeamEvent.DeviceAdded) { container.addDevice(publicKey, label) }

    fun removeDevice(publicKey: String) = perform(TeamEvent.DeviceRemoved) { container.removeDevice(publicKey) }

    fun setAdmin(publicKey: String, admin: Boolean) = perform(TeamEvent.AdminChanged) { container.setAdmin(publicKey, admin) }

    fun renameDevice(publicKey: String, label: String) = perform(null) { container.renameDevice(publicKey, label) }

    fun leave() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                container.leaveTeam()
                _events.emit(TeamEvent.Left)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TeamOperationException) {
                when (e.reason) {
                    TeamOperationException.Reason.NOT_CONNECTED, TeamOperationException.Reason.PUBLISH_FAILED ->
                        _events.emit(TeamEvent.LeaveNotSent)
                    else -> _events.emit(TeamEvent.Failed(e.reason.messageRes()))
                }
            } catch (e: Exception) {
                container.logger.warn(TAG, "Austritt fehlgeschlagen", e)
                _events.emit(TeamEvent.Failed(R.string.error_generic))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Nur auf diesem Gerät löschen (offline, entfernt oder Anschluss verloren). */
    fun deleteLocalData() = perform(TeamEvent.Left) { container.deleteLocalData() }

    /** „Das bin ich“; null = niemand. */
    fun setMyMember(memberId: String?) {
        viewModelScope.launch { container.shiftAlerts.configure(memberId, settings.value.notifyOnChanges) }
    }

    fun setNotifyOnChanges(enabled: Boolean) {
        viewModelScope.launch { container.shiftAlerts.configure(settings.value.myMemberId, enabled) }
    }

    private fun perform(success: TeamEvent?, action: suspend () -> Any?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
                success?.let { _events.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TeamOperationException) {
                _events.emit(TeamEvent.Failed(e.reason.messageRes()))
            } catch (e: Exception) {
                container.logger.warn(TAG, "Teamaktion fehlgeschlagen", e)
                _events.emit(TeamEvent.Failed(R.string.error_generic))
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
fun JoinCode.Problem.messageRes(): Int = when (this) {
    JoinCode.Problem.EMPTY -> R.string.join_error_empty
    JoinCode.Problem.OLD_VERSION -> R.string.join_error_old
    JoinCode.Problem.MISSING_PREFIX -> R.string.join_error_prefix
    JoinCode.Problem.INVALID_CHARACTERS -> R.string.join_error_characters
    JoinCode.Problem.WRONG_LENGTH -> R.string.join_error_length
    JoinCode.Problem.CHECKSUM_MISMATCH -> R.string.join_error_checksum
}

@StringRes
fun TeamOperationException.Reason.messageRes(): Int = when (this) {
    TeamOperationException.Reason.NOT_CONNECTED -> R.string.error_not_connected
    TeamOperationException.Reason.NOT_MEMBER -> R.string.error_not_member
    TeamOperationException.Reason.NOT_ADMIN -> R.string.error_not_admin
    TeamOperationException.Reason.LAST_ADMIN -> R.string.error_last_admin
    TeamOperationException.Reason.ALREADY_MEMBER -> R.string.error_already_member
    TeamOperationException.Reason.KEY_PACKAGE_NOT_FOUND -> R.string.error_key_package
    TeamOperationException.Reason.PUBLISH_FAILED -> R.string.error_publish
    TeamOperationException.Reason.CONFLICT -> R.string.error_conflict
    TeamOperationException.Reason.INVITE_NOT_DELIVERED -> R.string.error_invite_not_delivered
    TeamOperationException.Reason.FAILED -> R.string.error_generic
}
