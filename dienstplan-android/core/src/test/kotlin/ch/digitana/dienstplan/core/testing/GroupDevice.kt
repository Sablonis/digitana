package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.data.PlanRepository
import ch.digitana.dienstplan.core.data.PlanSnapshot
import ch.digitana.dienstplan.core.data.PlanStore
import ch.digitana.dienstplan.core.data.SecureFileStore
import ch.digitana.dienstplan.core.group.EncryptedDeviceKeyStore
import ch.digitana.dienstplan.core.group.EncryptedGroupRecordStore
import ch.digitana.dienstplan.core.group.GroupSyncConfig
import ch.digitana.dienstplan.core.group.GroupSyncEngine
import ch.digitana.dienstplan.core.group.Invite
import ch.digitana.dienstplan.core.group.Team
import ch.digitana.dienstplan.core.group.TeamRepository
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.sync.OkHttpRelayTransport
import ch.digitana.dienstplan.core.util.Clock
import ch.digitana.dienstplan.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File

/**
 * Ein simuliertes Handy mit MLS: eigener verschlüsselter Speicher, eigene Geräteschlüssel,
 * eigene SQLCipher-Datenbank und eigene [GroupSyncEngine] über echtes TLS.
 */
class GroupDevice(
    val name: String,
    private val relayUrls: List<String>,
    private val dir: File,
    private val clock: Clock = Clock.System,
    private val config: GroupSyncConfig = FAST,
    /** Standard: vertraut nur der Test-CA der lokalen Relays. */
    private val client: OkHttpClient = TestTls.client(),
) {
    private class MemoryStore : PlanStore {
        @Volatile var snapshot: PlanSnapshot? = null
        override fun load() = snapshot
        override fun save(snapshot: PlanSnapshot) {
            this.snapshot = snapshot
        }
        override fun clear() {
            snapshot = null
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files = SecureFileStore(File(dir, "$name/secure"), SoftwareKeyWrapper())
    private val planStore = MemoryStore()
    val team = TeamRepository(
        EncryptedDeviceKeyStore(files),
        EncryptedGroupRecordStore(files),
        File(dir, "$name/mls"),
        relayUrls,
        clock,
    )
    val plan = PlanRepository(planStore, scope, clock, saveDelayMillis = 10)

    /** Mit `-Ddienstplan.testlog=true` gibt die Engine ihre Meldungen aus. */
    private val logger: Logger = if (System.getProperty("dienstplan.testlog") == "true") {
        object : Logger {
            override fun debug(tag: String, message: String) = println("[$name] $tag: $message")
            override fun warn(tag: String, message: String, error: Throwable?) =
                println("[$name] WARN $tag: $message ${error?.let { it.javaClass.simpleName + ": " + it.message } ?: ""}")
        }
    } else {
        Logger.None
    }

    @Volatile var engine: GroupSyncEngine? = null
        private set

    val state: PlanState get() = plan.state.value
    val publicKey: String get() = team.publicKey!!
    val teamState: TeamState get() = team.state.value

    init {
        // Wie in der App: Sperre und Admins des Teams gelten für den Plan.
        scope.launch { team.state.collect { plan.setAccess(it.planAccess) } }
    }

    suspend fun load() {
        team.load()
        plan.load()
        plan.deviceId = team.deviceId
    }

    fun startEngine(): GroupSyncEngine {
        check(engine == null) { "Engine läuft schon" }
        return GroupSyncEngine(team, plan, relayUrls, OkHttpRelayTransport(client), clock, config, logger)
            .also {
                engine = it
                it.start()
            }
    }

    suspend fun stopEngine() {
        engine?.stop()
        engine = null
        plan.flush()
    }

    /** Wie ein Neustart der App: alles aus dem Speicher neu laden. */
    suspend fun restart(): GroupSyncEngine {
        stopEngine()
        load()
        return startEngine()
    }

    suspend fun createTeam(teamName: String): Team =
        team.createTeam(teamName).also { plan.deviceId = team.deviceId }

    suspend fun startJoining(): String = team.startJoining().code.also { plan.deviceId = team.deviceId }

    suspend fun awaitInvite(timeoutMillis: Long = 20_000): Invite? =
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                (teamState as? TeamState.Joining)?.invites?.firstOrNull()?.let { return@withTimeoutOrNull it }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    suspend fun accept(invite: Invite): Team =
        team.acceptInvite(invite.id).also { plan.deviceId = team.deviceId }

    suspend fun close() {
        stopEngine()
        scope.cancel()
        team.close()
    }

    override fun toString() = "Gerät $name"

    companion object {
        /** Kurze Zeitgrenzen für Tests. */
        val FAST = GroupSyncConfig(
            okTimeoutMillis = 5_000,
            backoffBaseMillis = 100,
            backoffMaxMillis = 1_000,
            rateLimitCooldownMillis = 500,
            maxTickMillis = 100,
            maxEventsPerSecond = 50,
            liveBatchMillis = 30,
            deltaDebounceMillis = 100,
            checkDelayMillis = 300,
            repairJitterMinMillis = 50,
            repairJitterMaxMillis = 300,
            repairCooldownMillis = 500,
            overviewDelayMillis = 200,
            selfUpdateDelayMillis = 300,
            selfUpdateQuietMillis = 500,
            commitSettleMillis = 300,
            leaveRemovalStepMillis = 500,
            removalRetryMillis = 1_000,
            opTimeoutMillis = 15_000,
            keyPackageQueryMillis = 3_000,
        )

        suspend fun awaitCondition(timeoutMillis: Long, condition: suspend () -> Boolean): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                while (!condition()) delay(25)
                true
            } ?: false

        /** Alle Geräte haben denselben, nicht leeren Plan, und nichts steht mehr aus. */
        suspend fun awaitConvergence(devices: List<GroupDevice>, timeoutMillis: Long): Boolean =
            awaitCondition(timeoutMillis) {
                val states = devices.map { it.state }
                val idle = devices.all { device -> device.engine?.status?.value?.let { it.isLive && it.pendingBuckets == 0 } == true }
                idle && states.first().entryCount > 0 && states.all { it == states.first() }
            }
    }
}
