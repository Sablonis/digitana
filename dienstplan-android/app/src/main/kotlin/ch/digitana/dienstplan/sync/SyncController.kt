package ch.digitana.dienstplan.sync

import ch.digitana.dienstplan.core.data.PlanRepository
import ch.digitana.dienstplan.core.group.GroupDiagnostics
import ch.digitana.dienstplan.core.group.GroupSyncEngine
import ch.digitana.dienstplan.core.group.TeamOperationException
import ch.digitana.dienstplan.core.group.TeamRepository
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.sync.RelayDiagnostics
import ch.digitana.dienstplan.core.sync.RelayTransport
import ch.digitana.dienstplan.core.sync.SyncStatus
import ch.digitana.dienstplan.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Ergebnis eines Abgleichs im Hintergrund. */
enum class BackgroundSyncResult {
    /** Kein Team auf diesem Gerät. */
    NO_TEAM,

    /** Die App ist offen; der Sync im Vordergrund erledigt das. */
    SKIPPED,

    /** Alle erreichbaren Relays abgeglichen, nichts mehr ausstehend. */
    DONE,

    /** Zeit abgelaufen; was angekommen ist, ist trotzdem gespeichert. */
    TIMEOUT,
}

/**
 * Startet und stoppt die [GroupSyncEngine]: Sie läuft, solange das Gerät einem Team angehört
 * oder beitritt und die App im Vordergrund ist. Nach dem Wechsel in den Hintergrund bleibt sie
 * kurz aktiv, damit gerade gemachte Änderungen noch gesendet werden. Zusätzlich gleicht
 * [backgroundSync] in regelmässigen Abständen im Hintergrund ab. Teamaktionen, die Relays
 * brauchen (Gerät hinzufügen, entfernen, Admin-Rechte, Austritt), laufen über die Engine.
 */
class SyncController(
    private val scope: CoroutineScope,
    private val teamRepository: TeamRepository,
    private val planRepository: PlanRepository,
    private val relayUrls: List<String>,
    private val transportFactory: () -> RelayTransport,
    private val logger: Logger,
) {
    private val foreground = MutableStateFlow(false)

    private val _status = MutableStateFlow(SyncStatus.stopped(relayUrls.size))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<RelayDiagnostics>>(emptyList())
    val diagnostics: StateFlow<List<RelayDiagnostics>> = _diagnostics.asStateFlow()

    private val _groupDiagnostics = MutableStateFlow(GroupDiagnostics())
    val groupDiagnostics: StateFlow<GroupDiagnostics> = _groupDiagnostics.asStateFlow()

    private val engine = MutableStateFlow<GroupSyncEngine?>(null)
    private var started = false

    /** Höchstens eine Engine gleichzeitig: Vordergrund oder Hintergrund. */
    private val engineMutex = Mutex()

    /** true, solange es etwas abzugleichen gibt (Beitritt oder Mitgliedschaft). */
    private val hasWork = teamRepository.state
        .map { it is TeamState.Joining || it is TeamState.Member }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val active = foreground.transformLatest { isForeground ->
                if (isForeground) {
                    emit(true)
                } else {
                    delay(MIN_GRACE_MILLIS)
                    engine.value?.awaitIdle(MAX_GRACE_MILLIS - MIN_GRACE_MILLIS)
                    emit(false)
                }
            }
            combine(hasWork, active) { work, isActive -> work && isActive }
                .distinctUntilChanged()
                .collectLatest { run ->
                    if (run) runEngine() else _status.value = SyncStatus.stopped(relayUrls.size)
                }
        }
    }

    fun onForeground() {
        foreground.value = true
    }

    fun onBackground() {
        foreground.value = false
        scope.launch { planRepository.flush() }
    }

    fun reconnectNow() {
        engine.value?.reconnectNow()
    }

    suspend fun addDevice(publicKey: String) = requireEngine().addDevice(publicKey)

    suspend fun removeDevice(publicKey: String) = requireEngine().removeDevice(publicKey)

    suspend fun setAdmin(publicKey: String, admin: Boolean) = requireEngine().setAdmin(publicKey, admin)

    suspend fun leave() = requireEngine().leave()

    suspend fun cancelJoining() = requireEngine().cancelJoining()

    /** Die Engine läuft nur im Vordergrund; kurz nach dem Öffnen kann sie noch starten. */
    private suspend fun requireEngine(): GroupSyncEngine =
        withTimeoutOrNull(ENGINE_WAIT_MILLIS) { engine.first { it != null } }
            ?: throw TeamOperationException(TeamOperationException.Reason.NOT_CONNECTED)

    /**
     * Ein Abgleich, während die App geschlossen ist: verbinden, holen, ausstehende Änderungen
     * senden, trennen. Wartet, bis alle Relays abgeglichen sind, nach [SETTLE_MILLIS] auch mit
     * nur einem Teil davon. Bricht ab, sobald die App in den Vordergrund kommt.
     */
    suspend fun backgroundSync(maxMillis: Long = BACKGROUND_MAX_MILLIS): BackgroundSyncResult {
        val state = teamRepository.state.value
        if (state !is TeamState.Member && state !is TeamState.Joining) return BackgroundSyncResult.NO_TEAM
        if (foreground.value || engine.value != null || !engineMutex.tryLock()) return BackgroundSyncResult.SKIPPED
        try {
            val current = newEngine()
            current.start()
            try {
                val startedAt = System.currentTimeMillis()
                val finished = withTimeoutOrNull(maxMillis) {
                    while (!foreground.value) {
                        val status = current.status.value
                        val settled = status.liveRelays == status.totalRelays ||
                            System.currentTimeMillis() - startedAt >= SETTLE_MILLIS
                        if (status.liveRelays > 0 && status.pendingBuckets == 0 && settled) return@withTimeoutOrNull true
                        delay(POLL_MILLIS)
                    }
                    false
                }
                return when (finished) {
                    true -> BackgroundSyncResult.DONE
                    false -> BackgroundSyncResult.SKIPPED
                    null -> BackgroundSyncResult.TIMEOUT
                }
            } finally {
                withContext(NonCancellable) {
                    current.stop()
                    planRepository.flush()
                }
            }
        } finally {
            engineMutex.unlock()
        }
    }

    private fun newEngine() = GroupSyncEngine(
        team = teamRepository,
        plan = planRepository,
        relayUrls = relayUrls,
        transport = transportFactory(),
        logger = logger,
    )

    private suspend fun runEngine() {
        engineMutex.withLock {
            val current = newEngine()
            current.start()
            engine.value = current
            try {
                coroutineScope {
                    launch { current.status.collect { _status.value = it } }
                    launch { current.diagnostics.collect { _diagnostics.value = it } }
                    launch { current.groupDiagnostics.collect { _groupDiagnostics.value = it } }
                    awaitCancellation()
                }
            } finally {
                if (engine.value === current) engine.value = null
                withContext(NonCancellable) { current.stop() }
                _status.value = SyncStatus.stopped(relayUrls.size)
            }
        }
    }

    private companion object {
        const val MIN_GRACE_MILLIS = 10_000L
        const val MAX_GRACE_MILLIS = 30_000L
        const val BACKGROUND_MAX_MILLIS = 45_000L
        const val SETTLE_MILLIS = 12_000L
        const val POLL_MILLIS = 250L
        const val ENGINE_WAIT_MILLIS = 5_000L
    }
}
