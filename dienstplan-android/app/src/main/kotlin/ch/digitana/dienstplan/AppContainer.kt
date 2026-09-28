package ch.digitana.dienstplan

import android.content.Context
import android.os.Build
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.data.EncryptedPlanStore
import ch.digitana.dienstplan.core.data.EncryptedTeamStore
import ch.digitana.dienstplan.core.data.PlanRepository
import ch.digitana.dienstplan.core.data.SecureFileStore
import ch.digitana.dienstplan.core.data.Team
import ch.digitana.dienstplan.core.data.TeamRepository
import ch.digitana.dienstplan.core.sync.OkHttpRelayTransport
import ch.digitana.dienstplan.core.sync.RelayUrls
import ch.digitana.dienstplan.core.sync.SecureHttp
import ch.digitana.dienstplan.core.util.Logger
import ch.digitana.dienstplan.security.AndroidKeystoreKeyWrapper
import ch.digitana.dienstplan.sync.SyncController
import ch.digitana.dienstplan.util.AndroidLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val secureFiles = SecureFileStore(
        File(this.context.noBackupFilesDir, "secure"),
        AndroidKeystoreKeyWrapper(preferStrongBox = hasStrongBox(this.context)),
    )

    val teamRepository = TeamRepository(EncryptedTeamStore(secureFiles))
    val planRepository = PlanRepository(EncryptedPlanStore(secureFiles), scope, logger = logger)

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

    private val teamMutex = Mutex()
    private val justCreatedTeam = AtomicBoolean(false)

    fun initialize() {
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
                withContext(Dispatchers.IO) { secureFiles.wipe() }
                runCatching { loadAll() }
            }
            planRepository.deviceId = teamRepository.team.value?.deviceId
            launch { teamRepository.team.collect { planRepository.deviceId = it?.deviceId } }
            syncController.start()
            _storageState.value = if (readable) StorageState.READY else StorageState.RESET_AFTER_ERROR
        }
    }

    private suspend fun loadAll() {
        teamRepository.load()
        planRepository.load()
    }

    fun acknowledgeStorageReset() {
        _storageState.value = StorageState.READY
    }

    /** true genau einmal nach „Neues Team“ – die App zeigt dann zuerst den Einladungscode. */
    fun consumeJustCreatedTeam(): Boolean = justCreatedTeam.getAndSet(false)

    suspend fun createTeam(): Team = teamMutex.withLock {
        teamRepository.create().also {
            planRepository.deviceId = it.deviceId
            justCreatedTeam.set(true)
        }
    }

    /** @param keepLocalData lokale Einträge ins Team übernehmen (z. B. nach einem Schlüsselwechsel). */
    suspend fun joinTeam(secret: TeamSecret, keepLocalData: Boolean): Team = teamMutex.withLock {
        if (!keepLocalData) planRepository.replaceAll(PlanState.EMPTY)
        teamRepository.join(secret).also { planRepository.deviceId = it.deviceId }
    }

    suspend fun rotateTeamKey(): Team = teamMutex.withLock {
        teamRepository.rotate().also { planRepository.deviceId = it.deviceId }
    }

    /** Löscht Team-Geheimnis, Plan, Datenschlüssel und Keystore-Schlüssel von diesem Gerät. */
    suspend fun leaveTeam() = teamMutex.withLock {
        teamRepository.leave()
        planRepository.deviceId = null
        planRepository.replaceAll(PlanState.EMPTY)
        withContext(Dispatchers.IO) { secureFiles.wipe() }
    }

    private companion object {
        const val TAG = "AppContainer"

        fun hasStrongBox(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
    }
}
