package ch.digitana.dienstplan

import android.content.Context
import android.os.Build
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.data.EncryptedPlanStore
import ch.digitana.dienstplan.core.data.EncryptedSettingsStore
import ch.digitana.dienstplan.core.data.PlanRepository
import ch.digitana.dienstplan.core.data.SecureFileStore
import ch.digitana.dienstplan.core.data.SettingsRepository
import ch.digitana.dienstplan.core.group.DeviceKeys
import ch.digitana.dienstplan.core.group.EncryptedDeviceKeyStore
import ch.digitana.dienstplan.core.group.EncryptedGroupRecordStore
import ch.digitana.dienstplan.core.group.Team
import ch.digitana.dienstplan.core.group.TeamRepository
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.sync.OkHttpRelayTransport
import ch.digitana.dienstplan.core.sync.RelayUrls
import ch.digitana.dienstplan.core.sync.SecureHttp
import ch.digitana.dienstplan.core.util.Logger
import ch.digitana.dienstplan.notify.ShiftAlerts
import ch.digitana.dienstplan.notify.ShiftNotifications
import ch.digitana.dienstplan.security.AndroidKeystoreKeyWrapper
import ch.digitana.dienstplan.sync.BackgroundSyncScheduler
import ch.digitana.dienstplan.sync.SyncController
import ch.digitana.dienstplan.util.AndroidLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class StorageState { LOADING, READY, RESET_AFTER_ERROR }

/**
 * Handverdrahtete Abhängigkeiten (kein DI-Framework): Speicher, Repositories, Sync.
 * Lebt so lange wie der Prozess.
 */
class AppContainer(context: Context) {

    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logger: Logger = if (BuildConfig.DEBUG) AndroidLogger else Logger.None

    // noBackupFilesDir ist per Definition von Auto Backup ausgenommen.
    private val secureDir = File(this.context.noBackupFilesDir, "secure")
    private val mlsDir = File(this.context.noBackupFilesDir, "mls")
    private val secureFiles = SecureFileStore(
        secureDir,
        AndroidKeystoreKeyWrapper(preferStrongBox = hasStrongBox(this.context)),
    )

    val teamRepository = TeamRepository(
        keyStore = EncryptedDeviceKeyStore(secureFiles),
        recordStore = EncryptedGroupRecordStore(secureFiles),
        databaseDir = mlsDir,
        relays = RelayUrls.DEFAULT,
        logger = logger,
    )
    val planRepository = PlanRepository(EncryptedPlanStore(secureFiles), scope, logger = logger)
    val settingsRepository = SettingsRepository(EncryptedSettingsStore(secureFiles))

    private val notifications = ShiftNotifications(this.context)
    val shiftAlerts = ShiftAlerts(settingsRepository, planRepository, notifications)

    private val httpClient by lazy { SecureHttp.newClient() }

    val syncController = SyncController(
        scope = scope,
        teamRepository = teamRepository,
        planRepository = planRepository,
        relayUrls = RelayUrls.DEFAULT,
        transportFactory = { OkHttpRelayTransport(httpClient) },
        logger = logger,
    )

    private val _storageState = MutableStateFlow(StorageState.LOADING)
    val storageState: StateFlow<StorageState> = _storageState.asStateFlow()

    /** true nach dem Wechsel von der alten Teamversion (DP2): einmaliger Hinweis. */
    private val _upgradedFromDp2 = MutableStateFlow(false)
    val upgradedFromDp2: StateFlow<Boolean> = _upgradedFromDp2.asStateFlow()

    private val teamMutex = Mutex()
    private val justCreatedTeam = AtomicBoolean(false)

    fun initialize() {
        notifications.createChannel()
        scope.launch {
            val readable = try {
                loadAll()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keystore-Schlüssel verloren oder Datei beschädigt: nicht mehr lesbar.
                logger.warn(TAG, "Lokale Daten nicht lesbar – werden zurückgesetzt", e)
                false
            }
            if (!readable) {
                wipeEverything()
                runCatching { loadAll() }
            }
            // Planeinträge schreibt nur ein Mitglied; nach dem Entfernen bleibt der Plan lesbar.
            launch {
                teamRepository.state.collect { state ->
                    planRepository.deviceId = if (state is TeamState.Member) teamRepository.deviceId else null
                }
            }
            // Hintergrund-Abgleich nur, solange das Gerät einem Team angehört oder beitritt.
            launch {
                teamRepository.state
                    .map { it is TeamState.Member || it is TeamState.Joining }
                    .distinctUntilChanged()
                    .collect { BackgroundSyncScheduler.update(context, enabled = it) }
            }
            syncController.start()
            _storageState.value = if (readable) StorageState.READY else StorageState.RESET_AFTER_ERROR
        }
    }

    private suspend fun loadAll() {
        // Teams der alten Version (gemeinsames Geheimnis, DP2) gibt es nicht mehr. Der Plan
        // bleibt erhalten und lässt sich in ein neues Team übernehmen.
        val legacy = withContext(Dispatchers.IO) { File(secureDir, LEGACY_TEAM_FILE).exists() }
        if (legacy) {
            withContext(Dispatchers.IO) { secureFiles.delete(LEGACY_TEAM_FILE) }
            _upgradedFromDp2.value = true
        }
        teamRepository.load()
        planRepository.load()
        settingsRepository.load()
    }

    /** Wartet, bis die lokalen Daten geladen sind (oder nach einem Fehler zurückgesetzt wurden). */
    suspend fun awaitReady(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { storageState.first { it != StorageState.LOADING } } != null

    fun acknowledgeStorageReset() {
        _storageState.value = StorageState.READY
    }

    fun acknowledgeUpgrade() {
        _upgradedFromDp2.value = false
    }

    /** true genau einmal nach „Neues Team“ – die App zeigt dann zuerst die Teamseite. */
    fun consumeJustCreatedTeam(): Boolean = justCreatedTeam.getAndSet(false)

    /** Eigener öffentlicher Schlüssel (für Fingerabdruck und Geräteliste). */
    val publicKey: String? get() = teamRepository.publicKey

    /**
     * „Neues Team“: Dieses Gerät ist einziges Mitglied und Admin. Ein vorhandener Plan
     * (z. B. aus der alten Version) bleibt und wird ins Team übernommen.
     */
    suspend fun createTeam(name: String, deviceLabel: String): Team = teamMutex.withLock {
        val team = teamRepository.createTeam(name.trim())
        planRepository.deviceId = teamRepository.deviceId
        if (!planRepository.state.value.isEmpty()) planRepository.markAllPending()
        labelOwnDevice(deviceLabel)
        justCreatedTeam.set(true)
        team
    }

    /** Beitritt beginnen: Code anzeigen, auf eine Einladung warten. */
    suspend fun startJoining() = teamMutex.withLock { teamRepository.startJoining() }

    /** Beitritt abbrechen; ohne laufende Engine nur lokal. */
    suspend fun cancelJoining() = teamMutex.withLock {
        try {
            syncController.cancelJoining()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            teamRepository.reset()
        }
    }

    /**
     * Einladung annehmen. [keepLocalData]: einen vorhandenen Plan ins Team übernehmen
     * (sonst wird er verworfen).
     */
    suspend fun acceptInvite(inviteId: String, keepLocalData: Boolean, deviceLabel: String): Team = teamMutex.withLock {
        if (!keepLocalData) {
            planRepository.replaceAll(PlanState.EMPTY)
            // „Das bin ich“ bezieht sich auf Personen des bisherigen Plans.
            settingsRepository.clear()
            notifications.cancel()
        }
        val team = teamRepository.acceptInvite(inviteId)
        planRepository.deviceId = teamRepository.deviceId
        if (keepLocalData && !planRepository.state.value.isEmpty()) planRepository.markAllPending()
        labelOwnDevice(deviceLabel)
        team
    }

    suspend fun declineInvite(inviteId: String) = teamMutex.withLock { teamRepository.declineInvite(inviteId) }

    /** Gerät hinzufügen (nur Admins); der Name erscheint in der Geräteliste aller Geräte. */
    suspend fun addDevice(publicKey: String, label: String) {
        if (label.isNotBlank()) planRepository.setDeviceLabel(DeviceKeys.deviceIdOf(publicKey), label)
        syncController.addDevice(publicKey)
    }

    suspend fun removeDevice(publicKey: String) = syncController.removeDevice(publicKey)

    suspend fun setAdmin(publicKey: String, admin: Boolean) = syncController.setAdmin(publicKey, admin)

    suspend fun renameDevice(publicKey: String, label: String) =
        planRepository.setDeviceLabel(DeviceKeys.deviceIdOf(publicKey), label)

    /**
     * Team verlassen: Die Admins werden gebeten, dieses Gerät zu entfernen; danach wird alles
     * Lokale gelöscht. Scheitert die Bitte (z. B. offline), bleibt alles, wie es ist.
     */
    suspend fun leaveTeam() = teamMutex.withLock {
        syncController.leave()
        wipeEverything()
    }

    /** Nur auf diesem Gerät löschen (offline, nach dem Entfernen oder bei verlorenem Anschluss). */
    suspend fun deleteLocalData() = teamMutex.withLock { wipeEverything() }

    /** Löscht Schlüssel, MLS-Zustand, Plan, Einstellungen und den Keystore-Schlüssel. */
    private suspend fun wipeEverything() {
        teamRepository.reset()
        planRepository.deviceId = null
        planRepository.replaceAll(PlanState.EMPTY)
        settingsRepository.clear()
        notifications.cancel()
        withContext(Dispatchers.IO) {
            secureFiles.wipe()
            mlsDir.deleteRecursively()
        }
    }

    private suspend fun labelOwnDevice(label: String) {
        val deviceId = teamRepository.deviceId ?: return
        if (label.isNotBlank()) planRepository.setDeviceLabel(deviceId, label)
    }

    private companion object {
        const val TAG = "AppContainer"
        /** Teamdatei der alten Version (DP2). */
        const val LEGACY_TEAM_FILE = "team.bin"

        fun hasStrongBox(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
    }
}
