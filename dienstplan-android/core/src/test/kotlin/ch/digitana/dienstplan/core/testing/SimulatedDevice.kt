package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.crypto.TeamKeys
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.data.PlanRepository
import ch.digitana.dienstplan.core.data.PlanSnapshot
import ch.digitana.dienstplan.core.data.PlanStore
import ch.digitana.dienstplan.core.sync.OkHttpRelayTransport
import ch.digitana.dienstplan.core.sync.RelayTransport
import ch.digitana.dienstplan.core.sync.SyncConfig
import ch.digitana.dienstplan.core.sync.SyncEngine
import ch.digitana.dienstplan.core.util.Clock
import ch.digitana.dienstplan.core.util.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** Ein simuliertes Handy: eigener Speicher, eigene Geräte-ID, eigene Sync-Engine. */
class SimulatedDevice(
    val name: String,
    secret: TeamSecret,
    relayUrls: List<String>,
    transport: RelayTransport,
    clock: Clock = Clock.System,
    config: SyncConfig = FAST,
) {
    constructor(name: String, secret: TeamSecret, relayUrls: List<String>, client: OkHttpClient, clock: Clock = Clock.System, config: SyncConfig = FAST) :
        this(name, secret, relayUrls, OkHttpRelayTransport(client), clock, config)

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
    val keys: TeamKeys = TeamKeys.derive(secret)
    val repository = PlanRepository(MemoryStore(), scope, clock, saveDelayMillis = 10).apply {
        deviceId = Hex.encode(SecureRandomBytes.next(8))
    }
    val engine = SyncEngine(keys, repository, relayUrls, transport, clock, config)

    val state: PlanState get() = repository.state.value
    val deviceId: String get() = repository.deviceId!!

    fun start() = engine.start()

    suspend fun stop() {
        engine.stop()
        scope.cancel()
    }

    override fun toString() = "Gerät $name"

    companion object {
        /** Schnellere Zeitgrenzen für Tests. */
        val FAST = SyncConfig(
            okTimeoutMillis = 5_000,
            backoffBaseMillis = 100,
            backoffMaxMillis = 1_000,
            rateLimitCooldownMillis = 500,
            maxTickMillis = 100,
        )

        /** Wartet, bis alle Geräte denselben, nicht leeren Stand haben und nichts mehr aussteht. */
        suspend fun awaitConvergence(devices: List<SimulatedDevice>, timeoutMillis: Long): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val states = devices.map { it.state }
                    val idle = devices.all { it.engine.status.value.let { s -> s.isLive && s.pendingBuckets == 0 } }
                    if (idle && states.first().entryCount > 0 && states.all { it == states.first() }) return@withTimeoutOrNull true
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: false

        suspend fun awaitCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                while (!condition()) delay(25)
                true
            } ?: false
    }
}
