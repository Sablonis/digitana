package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.nostr.NostrEvent
import ch.digitana.dienstplan.core.nostr.RelayMessage
import ch.digitana.dienstplan.core.nostr.RelayMessages
import ch.digitana.dienstplan.core.sync.Backoff
import ch.digitana.dienstplan.core.sync.RelayConnectionState
import ch.digitana.dienstplan.core.sync.RelayDiagnostics
import ch.digitana.dienstplan.core.sync.RelaySocket
import ch.digitana.dienstplan.core.sync.RelayTransport
import ch.digitana.dienstplan.core.sync.RelayUrls
import ch.digitana.dienstplan.core.sync.SyncStatus
import ch.digitana.dienstplan.core.util.Clock
import ch.digitana.dienstplan.core.util.Hex
import ch.digitana.dienstplan.core.util.Logger
import ch.digitana.dienstplan.mls.IngestOutcome
import ch.digitana.dienstplan.mls.Invitation
import ch.digitana.dienstplan.mls.MlsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

data class GroupSyncConfig(
    /** Ohne OK-Antwort innerhalb dieser Zeit gilt ein Versand als gescheitert. */
    val okTimeoutMillis: Long = 15_000,
    /** Höchstens so viele Versuche pro Relay und Event. */
    val maxPublishAttempts: Int = 4,
    /** `limit` pro Abfrage. */
    val pageSize: Int = 500,
    /** Ab so vielen Events pro Seite wird mit `until` weiter zurückgeblättert. */
    val pagingThreshold: Int = 20,
    val maxPages: Int = 40,
    /** Höchstens so viele Events pro Relay beim Nachholen (Speicher). */
    val maxCatchUpEvents: Int = 10_000,
    /** Pause nach `rate-limited:`. */
    val rateLimitCooldownMillis: Long = 10_000,
    val backoffBaseMillis: Long = 1_000,
    val backoffMaxMillis: Long = 60_000,
    /** Nach so langer stabiler Verbindung beginnt der Backoff wieder von vorn. */
    val stableConnectionMillis: Long = 30_000,
    /** Längste Pause zwischen zwei internen Prüfungen. */
    val maxTickMillis: Long = 60_000,
    /**
     * Höchstens so viele eigene Events pro Relay und Sekunde. Hält auch die Reihenfolge der
     * MLS-Nachrichten eines Geräts nahe an der Zeitreihenfolge (Toleranz von MDK: 100).
     */
    val maxEventsPerSecond: Int = 8,
    /** Live-Events so lange sammeln und gemeinsam verarbeiten. */
    val liveBatchMillis: Long = 100,
    /** Eigene Änderungen so lange sammeln, bevor sie gesendet werden. */
    val deltaDebounceMillis: Long = 500,
    /** Nach einem fremden Stand so lange warten, bevor der eigene Digest verglichen wird. */
    val checkDelayMillis: Long = 1_500,
    /** Zufällige Wartezeit vor einer Antwort mit dem eigenen Stand (vermeidet doppelte Antworten). */
    val repairJitterMinMillis: Long = 500,
    val repairJitterMaxMillis: Long = 4_000,
    /** Höchstens eine Antwort pro Bucket in dieser Zeit. */
    val repairCooldownMillis: Long = 10_000,
    /** So oft schickt ein Gerät eine Übersicht seiner Digests. */
    val overviewIntervalMillis: Long = 6 * 60 * 60 * 1000L,
    /** Wartezeit nach dem ersten Abgleich, bevor die Übersicht geht. */
    val overviewDelayMillis: Long = 5_000,
    /** Eigenen MLS-Schlüssel spätestens nach dieser Zeit erneuern. */
    val selfUpdateAfterSeconds: Long = 7 * 24 * 60 * 60L,
    /** Wartezeit nach dem ersten Abgleich (plus Zufall bis zum Doppelten), bevor der Schlüssel erneuert wird. */
    val selfUpdateDelayMillis: Long = 30_000,
    /**
     * Schlüssel nur erneuern, wenn so lange kein Commit kam: Gleichzeitige Commits derselben
     * Epoche löst MLS zwar auf, der unterlegene geht aber verloren.
     */
    val selfUpdateQuietMillis: Long = 60_000,
    /**
     * Ein eigener Commit wird erst so lange nach der Bestätigung durch ein Relay übernommen.
     * Trifft in dieser Zeit ein früherer Commit derselben Epoche ein, gewinnt dieser (MIP-03)
     * und der eigene wird verworfen und neu versucht.
     */
    val commitSettleMillis: Long = 2_000,
    /** Austrittsbitten: Admins warten je nach Rang so lange, damit nicht alle gleichzeitig entfernen. */
    val leaveRemovalStepMillis: Long = 5_000,
    /** Neuer Versuch, wenn eine Entfernung nicht durchging oder rückgängig gemacht wurde. */
    val removalRetryMillis: Long = 10_000,
    /** So lange wird nach einer Entfernung geprüft, ob ein Rücksprung sie aufgehoben hat. */
    val removalWatchMillis: Long = 10 * 60 * 1000L,
    /** Überlappung beim Abrufen ab dem gespeicherten Stand (Uhrabweichungen). */
    val cursorOverlapSeconds: Long = 3 * 60 * 60L,
    val cursorSaveIntervalMillis: Long = 5_000,
    /** So lange bekommen später verbundene Relays eigene Events noch nachgeliefert. */
    val outboxRetentionMillis: Long = 10 * 60 * 1000L,
    /** Höchstdauer, bis ein Commit oder eine Einladung bestätigt sein muss. */
    val opTimeoutMillis: Long = 30_000,
    /** So lange wird auf KeyPackages von den Relays gewartet. */
    val keyPackageQueryMillis: Long = 8_000,
)

/** Zähler für die Diagnose (ohne Inhalte). */
data class GroupDiagnostics(
    val epoch: Long = 0,
    /** Nachrichten aus einer noch unbekannten Epoche, die später erneut versucht werden. */
    val deferred: Int = 0,
    /** Verworfene Gruppen-Events (fremd, doppelt, nicht entschlüsselbar). */
    val ignored: Int = 0,
    /** Konkurrierende Commits, bei denen ein früherer gewonnen hat. */
    val rollbacks: Int = 0,
    /** Ungültige Nachrichten von Mitgliedern (Format) oder einzelne verworfene Einträge. */
    val invalidMessages: Int = 0,
    val droppedEntries: Int = 0,
    /**
     * Ein eigener Commit hat einen Wettlauf verloren, nachdem er schon übernommen war: Dieses
     * Gerät kann die Nachrichten des Teams nicht mehr lesen und muss neu hinzugefügt werden.
     */
    val forked: Boolean = false,
)

/** Eine Teamaktion ist gescheitert. */
class TeamOperationException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Kein Relay erreichbar. */
        NOT_CONNECTED,
        /** Dieses Gerät ist kein Mitglied (mehr). */
        NOT_MEMBER,
        /** Nur Admins dürfen das. */
        NOT_ADMIN,
        /** Das letzte Admin-Gerät muss zuerst ein anderes Gerät zum Admin machen. */
        LAST_ADMIN,
        /** Das Gerät ist schon im Team. */
        ALREADY_MEMBER,
        /** Kein KeyPackage des Geräts auf den Relays (Code falsch oder Gerät offline). */
        KEY_PACKAGE_NOT_FOUND,
        /** Kein Relay hat den Commit bestätigt; nichts wurde geändert. */
        PUBLISH_FAILED,
        /** Gleichzeitige Änderung eines anderen Geräts hatte Vorrang; bitte erneut versuchen. */
        CONFLICT,
        /** Gerät ist im Team, die Einladung kam aber (noch) bei keinem Relay an. */
        INVITE_NOT_DELIVERED,
        FAILED,
    }
}

/**
 * Synchronisiert den Plan eines Teams über Nostr-Relays mit MLS (Marmot, RFC 9420).
 *
 * - Beitritt: veröffentlicht das KeyPackage des Geräts und wartet auf Einladungen (NIP-59).
 * - Mitglied: holt Gruppen-Events (Kind 445, `#h`) ab dem gespeicherten Stand nach, gibt sie
 *   der MLS-Schicht und führt die entschlüsselten Planeinträge zusammen (LWW-CRDT).
 * - Eigene Änderungen gehen als kleine Nachrichten raus und bleiben „ausstehend“, bis ein
 *   Relay sie bestätigt.
 * - Abgleich: Jede Nachricht trägt den Digest des Buckets beim Absender. Weicht der eigene
 *   nach dem Zusammenführen ab, antwortet das Gerät mit seinem ganzen Bucket – nach einer
 *   zufälligen Wartezeit und nur, wenn nicht schon ein anderes Gerät denselben Stand
 *   geschickt hat. Eine Übersicht aller Digests (alle paar Stunden) deckt verpasste Nachrichten
 *   auf; eine leere Antwort bittet die anderen um ihren Stand.
 * - Commits (Einladen, Entfernen, Admins, Schlüssel erneuern, automatische Bestätigung eines
 *   Austritts) werden erst übernommen, wenn ein Relay sie bestätigt hat. Bis dahin ruht die
 *   Verarbeitung fremder Gruppen-Events.
 *
 * Alle Zustandsänderungen passieren in einer Coroutine ([runLoop]); Netzwerk-Callbacks und
 * Teamaktionen stellen nur Aufträge in einen Kanal.
 */
class GroupSyncEngine(
    private val team: TeamRepository,
    private val plan: PlanSync,
    relayUrls: List<String>,
    private val transport: RelayTransport,
    private val clock: Clock = Clock.System,
    private val config: GroupSyncConfig = GroupSyncConfig(),
    private val logger: Logger = Logger.None,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) {
    private val sessions: List<Session> = relayUrls.distinct().map { Session(it) }
    private val inputs = Channel<Input>(capacity = 256)
    private val backoff = Backoff(config.backoffBaseMillis, config.backoffMaxMillis)
    private val opMutex = Mutex()

    private var scope: CoroutineScope? = null
    private var started = false
    private var tickAt = Long.MAX_VALUE

    // Ziel: Beitritt oder Gruppe.
    private var target: Target? = null
    private var anyCaughtUp = false

    // Verarbeitung
    private val seen = object : LinkedHashMap<String, Unit>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > SEEN_CAPACITY
    }
    private val liveBuffer = LinkedHashMap<String, Incoming>()
    private var liveFlushAt = Long.MAX_VALUE
    private val pausedEvents = LinkedHashMap<String, Incoming>()
    private var commitInFlight = false
    private val commitIdle = MutableStateFlow(true)
    private var cursor = 0L
    private var floor = 0L
    private var cursorDirty = false
    private var cursorSavedAt = 0L
    /** Eigener Commit vor einem Neustart: beim ersten Abgleich übernehmen oder verwerfen. */
    private var recoverCommit: String? = null
    /** Der unterbrochene eigene Commit, wie ihn ein Relay geliefert hat. */
    private var recoverEvent: Incoming? = null

    // Ausgang
    private val outbox = LinkedHashMap<String, Outgoing>()
    private val keyPackageQueries = HashMap<String, KeyPackageQuery>()

    // Planprotokoll
    private val lastSeenDigest = HashMap<String, String>()
    private val checkAt = HashMap<String, Long>()
    private val repairAt = HashMap<String, Long>()
    private val lastRepairAt = HashMap<String, Long>()
    private val inflightKeys = HashMap<String, Long>()
    private var deltaDueAt = Long.MAX_VALUE
    private var pendingCount = 0
    private var overviewDueAt = Long.MAX_VALUE
    private var overviewSending = false
    private var selfUpdateCheckAt = Long.MAX_VALUE
    private var selfUpdateRunning = false
    private var lastCommitSeenAt = 0L
    /** Zuletzt übernommener eigener Commit (zum Erkennen eines verlorenen Wettlaufs). */
    private var lastOwnCommit: OwnCommit? = null
    /** Geräte, die entfernt werden sollen bzw. deren Entfernung überwacht wird. */
    private val pendingRemovals = HashMap<String, Removal>()
    private var removalRunning = false

    private var groupDiagnosticsState = GroupDiagnostics()

    private val _status = MutableStateFlow(SyncStatus.stopped(sessions.size))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow(sessions.map { it.diagnostics() })
    val diagnostics: StateFlow<List<RelayDiagnostics>> = _diagnostics.asStateFlow()

    private val _groupDiagnostics = MutableStateFlow(GroupDiagnostics())
    val groupDiagnostics: StateFlow<GroupDiagnostics> = _groupDiagnostics.asStateFlow()

    init {
        require(sessions.isNotEmpty()) { "Mindestens ein Relay nötig" }
        sessions.forEach { require(RelayUrls.isValid(it.url)) { "Ungültige Relay-URL: ${it.url}" } }
    }

    /** Startet den Sync. Eine Engine lässt sich genau einmal starten. */
    fun start() {
        check(!started) { "GroupSyncEngine wurde bereits gestartet" }
        started = true
        val handler = CoroutineExceptionHandler { _, error -> logger.warn(TAG, "Unerwarteter Fehler im Sync", error) }
        val engineScope = CoroutineScope(SupervisorJob() + dispatcher + handler)
        scope = engineScope
        _status.value = SyncStatus(0, sessions.size, sessions.size, 0, running = true)
        engineScope.launch { runLoop() }
        engineScope.launch { plan.localChanges.collect { inputs.send(Input.LocalChanged) } }
        engineScope.launch { team.record.collect { inputs.send(Input.TargetChanged) } }
        for (session in sessions) engineScope.launch { connectionLoop(session) }
    }

    /**
     * Beendet alle Verbindungen. Ein laufender Commit darf zuerst fertig werden, damit der
     * MLS-Zustand zu dem passt, was auf den Relays liegt.
     */
    suspend fun stop() {
        val engineScope = scope ?: return
        withTimeoutOrNull(config.opTimeoutMillis) { commitIdle.first { it } }
        val saveCursor = CompletableDeferred<Unit>()
        if (inputs.trySend(Input.Run { saveCursorNow(); saveCursor.complete(Unit) }).isSuccess) {
            withTimeoutOrNull(STOP_SAVE_MILLIS) { saveCursor.await() }
        }
        scope = null
        engineScope.coroutineContext[Job]?.cancelAndJoin()
        inputs.close()
        _status.value = SyncStatus.stopped(sessions.size)
    }

    /** Nach Netzwechsel: getrennte Relays sofort neu verbinden. */
    fun reconnectNow() {
        inputs.trySend(Input.ReconnectNow)
    }

    /** Wartet, bis ein Relay abgeglichen ist und nichts Eigenes mehr aussteht. */
    suspend fun awaitIdle(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            status.first { it.isLive && it.pendingBuckets == 0 }
        } != null

    // ---------------------------------------------------------------------------------
    // Teamaktionen
    // ---------------------------------------------------------------------------------

    /**
     * Fügt ein Gerät hinzu (nur Admins): KeyPackage laden, Commit veröffentlichen und
     * übernehmen, Einladung senden, danach den ganzen Plan schicken.
     */
    suspend fun addDevice(publicKey: String) = operation {
        require(Hex.isLowerHex(publicKey, 64)) { "Öffentlicher Schlüssel ungültig" }
        val member = requireMember()
        if (!member.isAdmin) throw TeamOperationException(TeamOperationException.Reason.NOT_ADMIN)
        if (publicKey in member.team.members) throw TeamOperationException(TeamOperationException.Reason.ALREADY_MEMBER)
        val keyPackages = queryKeyPackages(publicKey)
        if (keyPackages.isEmpty()) throw TeamOperationException(TeamOperationException.Reason.KEY_PACKAGE_NOT_FOUND)
        val groupId = member.team.groupId
        val invitation: Invitation = commit(groupId) { mls ->
            // Ein anderer Admin war schneller: Seine Einladung genügt.
            if (publicKey in mls.team(groupId)?.members.orEmpty()) return@commit null
            var failure: Exception? = null
            for (keyPackage in keyPackages) {
                try {
                    val result = mls.invite(groupId, keyPackage)
                    return@commit result.commitEvent to result
                } catch (e: Exception) {
                    failure = e
                }
            }
            throw failure ?: TeamOperationException(TeamOperationException.Reason.KEY_PACKAGE_NOT_FOUND)
        } ?: return@operation
        // Nur einladen, wenn das Gerät nach dem Commit wirklich Mitglied ist.
        if (publicKey !in requireMember().team.members) throw TeamOperationException(TeamOperationException.Reason.CONFLICT)
        var delivered = true
        for (welcome in invitation.welcomeEvents) {
            if (!publishAndAwait(welcome)) delivered = false
        }
        // Das neue Gerät kann nur Nachrichten ab seinem Beitritt lesen: ganzen Plan schicken.
        inLoop { sendFullState() }
        if (!delivered) throw TeamOperationException(TeamOperationException.Reason.INVITE_NOT_DELIVERED)
    }

    /** Entfernt ein Gerät (nur Admins). Es kann danach keine Nachrichten mehr lesen. */
    suspend fun removeDevice(publicKey: String) = operation {
        val member = requireMember()
        if (!member.isAdmin) throw TeamOperationException(TeamOperationException.Reason.NOT_ADMIN)
        if (publicKey !in member.team.members) return@operation
        if (publicKey == member.me) throw TeamOperationException(TeamOperationException.Reason.FAILED)
        val groupId = member.team.groupId
        commit(groupId) { mls ->
            if (publicKey in mls.team(groupId)?.members.orEmpty()) mls.removeMembers(groupId, listOf(publicKey)) to Unit else null
        }
        // Ein Rücksprung könnte die Entfernung aufheben: eine Weile überwachen und dann wiederholen.
        inLoop { pendingRemovals[publicKey] = Removal(Long.MAX_VALUE, clock.nowMillis() + config.removalWatchMillis) }
    }

    /** Gibt einem Gerät Admin-Rechte oder entzieht sie (nur Admins). */
    suspend fun setAdmin(publicKey: String, admin: Boolean) = operation {
        val member = requireMember()
        if (!member.isAdmin) throw TeamOperationException(TeamOperationException.Reason.NOT_ADMIN)
        if (publicKey !in member.team.members) throw TeamOperationException(TeamOperationException.Reason.NOT_MEMBER)
        val admins = member.team.admins.filter { it in member.team.members }.toMutableSet()
        if (admin) admins += publicKey else admins -= publicKey
        if (admins.isEmpty()) throw TeamOperationException(TeamOperationException.Reason.LAST_ADMIN)
        if (admins == member.team.admins.toSet()) return@operation
        val groupId = member.team.groupId
        commit(groupId) { mls ->
            // Mit dem aktuellen Stand rechnen (ein konkurrierender Commit kann ihn verändert haben).
            val info = mls.team(groupId) ?: return@commit null
            val current = info.admins.filter { it in info.members }.toMutableSet()
            if (admin) current += publicKey else current -= publicKey
            if (current.isEmpty() || publicKey !in info.members || current == info.admins.toSet()) return@commit null
            mls.setAdmins(groupId, current.sorted()) to Unit
        }
    }

    /**
     * Verlässt das Team: Admin-Rechte abgeben (falls nötig), die Admins um Entfernung bitten
     * (verschlüsselte Nachricht in der Gruppe), danach alles Lokale löschen. Ein Admin-Gerät
     * entfernt das Gerät mit einem normalen Commit, sobald es die Bitte liest.
     *
     * Den Austrittsvorschlag von MLS (SelfRemove) nutzt die App bewusst nicht: In der
     * verwendeten MDK-Version legt dessen automatische Bestätigung keinen Wiederherstellungs-
     * punkt an, sodass ein gleichzeitiger Commit die Geräte dauerhaft entzweien könnte.
     */
    suspend fun leave() = operation {
        val member = requireMember()
        val groupId = member.team.groupId
        if (member.team.members.size > 1) {
            if (member.isAdmin) {
                val otherAdmins = member.team.admins.filter { it != member.me && it in member.team.members }
                if (otherAdmins.isEmpty()) throw TeamOperationException(TeamOperationException.Reason.LAST_ADMIN)
                commit(groupId) { mls ->
                    if (member.me in mls.team(groupId)?.admins.orEmpty()) mls.selfDemote(groupId) to Unit else null
                }
            }
            val request = inLoop { encryptOrNull(GroupMessages.encodeLeave()) }
                ?: throw TeamOperationException(TeamOperationException.Reason.FAILED)
            if (!publishAndAwait(request)) throw TeamOperationException(TeamOperationException.Reason.PUBLISH_FAILED)
        }
        team.reset()
    }

    /** Beitritt abbrechen: KeyPackage löschen lassen (soweit erreichbar), Schlüssel verwerfen. */
    suspend fun cancelJoining() = operation {
        val keyPackage = team.record.value?.takeIf { it.mode == GroupMode.JOINING }?.keyPackage
        if (keyPackage != null) {
            runCatching {
                val deletion = inLoop { deletionFor(keyPackage) }
                if (deletion != null) withTimeoutOrNull(CANCEL_DELETE_MILLIS) { publishAndAwait(deletion) }
            }
        }
        team.reset()
    }

    private suspend fun <T> operation(block: suspend () -> T): T = opMutex.withLock {
        if (scope == null) throw TeamOperationException(TeamOperationException.Reason.NOT_CONNECTED)
        block()
    }

    private fun requireMember(): TeamState.Member =
        team.state.value as? TeamState.Member ?: throw TeamOperationException(TeamOperationException.Reason.NOT_MEMBER)

    /**
     * Ein eigener Commit: erzeugen (Verarbeitung ruht), veröffentlichen, nach der ersten
     * Bestätigung und einer kurzen Wartezeit übernehmen, sonst verwerfen. Hat ein früherer
     * Commit eines anderen Geräts Vorrang, wird neu versucht. [create] liefert `null`, wenn
     * nach dem aktuellen Stand nichts mehr zu tun ist.
     */
    private suspend fun <T> commit(groupId: String, create: (MlsEngine) -> Pair<String, T>?): T? {
        repeat(MAX_COMMIT_ATTEMPTS) {
            val (epoch, created) = acquireCommit {
                team.mls { mls ->
                    val epoch = mls.team(groupId)?.epoch?.toLong() ?: throw TeamOperationException(TeamOperationException.Reason.NOT_MEMBER)
                    epoch to create(mls)
                }
            }
            if (created == null) {
                inLoop { endCommit() }
                return null
            }
            val (event, extra) = created
            val own = eventKeyOf(event)
            var result = CommitResult.NOT_PUBLISHED
            try {
                inLoop { team.updateRecord { it.copy(pendingCommit = own.id) } }
                if (publishAndAwait(event)) {
                    // Konkurrierende Commits derselben Epoche abwarten (siehe commitSettleMillis).
                    delay(config.commitSettleMillis)
                    result = if (inLoop { hasBetterCommit(epoch, own) }) CommitResult.LOST else CommitResult.ACCEPTED
                }
            } finally {
                // Läuft die Engine nicht mehr, entscheidet der nächste Start anhand der Relays.
                val merge = result == CommitResult.ACCEPTED
                withContext(NonCancellable) {
                    withTimeoutOrNull(FINISH_TIMEOUT_MILLIS) {
                        runCatching {
                            inLoop {
                                if (merge) lastOwnCommit = OwnCommit(epoch, own)
                                finishCommit(groupId, merge)
                            }
                        }
                    }
                }
            }
            when (result) {
                CommitResult.ACCEPTED -> return extra
                CommitResult.NOT_PUBLISHED -> throw TeamOperationException(TeamOperationException.Reason.PUBLISH_FAILED)
                CommitResult.LOST -> {
                    logger.debug(TAG, "Ein früherer Commit eines anderen Geräts hat Vorrang; neuer Versuch")
                    delay(jitter(config.commitSettleMillis))
                }
            }
        }
        throw TeamOperationException(TeamOperationException.Reason.CONFLICT)
    }

    /** In der Schleife: Liegt ein Commit derselben Epoche vor, der nach MIP-03 Vorrang hat? */
    private suspend fun hasBetterCommit(epoch: Long, own: EventKey): Boolean {
        for (event in pausedEvents.values) {
            if (event.id == own.id) continue
            if (EventKey(event.createdAt, event.id) >= own) continue
            val commitEpoch = try {
                team.mls { it.peekCommit(event.json) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (commitEpoch?.toLong() == epoch) return true
        }
        return false
    }

    /** Wartet, bis kein anderer Commit läuft, und erzeugt dann den eigenen (in der Schleife). */
    private suspend fun <T> acquireCommit(create: suspend () -> T): T {
        val deadline = clock.nowMillis() + config.opTimeoutMillis
        while (true) {
            val result = inLoop {
                if (commitInFlight) {
                    null
                } else {
                    beginCommit()
                    try {
                        Result.success(create())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        endCommit()
                        Result.failure(e)
                    }
                }
            }
            if (result != null) return result.getOrThrow()
            if (clock.nowMillis() > deadline) throw TeamOperationException(TeamOperationException.Reason.FAILED)
            delay(COMMIT_RETRY_MILLIS)
        }
    }

    private fun beginCommit() {
        commitInFlight = true
        commitIdle.value = false
    }

    private suspend fun endCommit() {
        commitInFlight = false
        commitIdle.value = true
        resumeIngest()
    }

    /** Während eines Commits angekommene Gruppen-Events verarbeiten. */
    private suspend fun resumeIngest() {
        if (commitInFlight || pausedEvents.isEmpty()) return
        val events = pausedEvents.values.toList()
        pausedEvents.clear()
        ingest(events)
    }

    /**
     * In der Schleife: eigenen Commit übernehmen oder verwerfen und weiterverarbeiten. Die
     * Ergebnisse können einen weiteren automatischen Commit auslösen; dann ruht die
     * Verarbeitung weiter.
     */
    private suspend fun finishCommit(groupId: String, accepted: Boolean) {
        var outcomes: List<IngestOutcome> = emptyList()
        try {
            outcomes = if (accepted) {
                team.mls { it.confirmPublished(groupId) }
            } else {
                team.mls { it.abortPending(groupId) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Commit ließ sich nicht abschliessen", e)
        }
        commitInFlight = false
        commitIdle.value = true
        if (accepted) lastCommitSeenAt = clock.nowMillis()
        try {
            team.updateRecord { it.copy(pendingCommit = null) }
            handleOutcomes(outcomes, emptyMap())
            team.refresh()
            applyTarget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Nach dem Commit ging etwas schief", e)
        }
        resumeIngest()
    }

    /** Veröffentlicht ein Event und wartet auf die erste Bestätigung eines Relays. */
    private suspend fun publishAndAwait(eventJson: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        val id = inLoop { enqueue(eventJson, done = done)?.id } ?: return false
        val result = withTimeoutOrNull(config.opTimeoutMillis) { done.await() }
        if (result == null) {
            // Nicht mehr nachliefern: Ein verspätet veröffentlichter Commit passte nicht zum MLS-Zustand.
            runCatching { inLoop { cancelOutgoing(id) } }
        }
        return result == true
    }

    /** Fragt alle verbundenen Relays nach KeyPackages des Geräts (neueste zuerst). */
    private suspend fun queryKeyPackages(publicKey: String): List<String> {
        val query = inLoop {
            val connected = sessions.filter { it.connected }
            if (connected.isEmpty()) return@inLoop null
            val subscriptionId = newSubscriptionId()
            val query = KeyPackageQuery(subscriptionId, publicKey, connected.map { it.url }.toMutableSet())
            keyPackageQueries[subscriptionId] = query
            val filter = RelayMessages.filter(kinds = listOf(KIND_KEY_PACKAGE), authors = listOf(publicKey), limit = KEY_PACKAGE_LIMIT)
            for (session in connected) session.socket?.send(RelayMessages.req(subscriptionId, filter))
            query
        } ?: throw TeamOperationException(TeamOperationException.Reason.NOT_CONNECTED)
        withTimeoutOrNull(config.keyPackageQueryMillis) { query.done.await() }
        return inLoop {
            keyPackageQueries.remove(query.subscriptionId)
            for (session in sessions) if (session.connected) session.socket?.send(RelayMessages.close(query.subscriptionId))
            query.events.values.sortedWith(compareByDescending<Pair<Long, String>> { it.first }).map { it.second }
        }
    }

    /** Führt [block] in der Ereignisschleife aus und liefert das Ergebnis. */
    private suspend fun <T> inLoop(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        try {
            inputs.send(
                Input.Run {
                    try {
                        result.complete(block())
                    } catch (e: CancellationException) {
                        result.completeExceptionally(e)
                        throw e
                    } catch (e: Exception) {
                        result.completeExceptionally(e)
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TeamOperationException(TeamOperationException.Reason.NOT_CONNECTED)
        }
        return result.await()
    }

    // ---------------------------------------------------------------------------------
    // Verbindungen
    // ---------------------------------------------------------------------------------

    private suspend fun connectionLoop(session: Session) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val generation = session.generation.incrementAndGet()
            val closed = CompletableDeferred<Unit>()
            val listener = object : RelayTransport.Listener {
                override fun onOpen(socket: RelaySocket) {
                    offer(Input.Opened(session, generation, socket))
                }

                override fun onMessage(text: String) {
                    offer(Input.Message(session, generation, text))
                }

                override fun onClosed(error: String?) {
                    offer(Input.Closed(session, generation, error))
                    closed.complete(Unit)
                }
            }
            inputs.send(Input.Connecting(session, generation))
            val socket = try {
                transport.connect(session.url, listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Verbindungsaufbau zu ${session.url} fehlgeschlagen", e)
                inputs.send(Input.Closed(session, generation, "Verbindungsaufbau fehlgeschlagen"))
                null
            }
            if (socket != null) {
                try {
                    closed.await()
                } finally {
                    socket.close()
                }
            }
            val liveSince = session.liveSince
            session.liveSince = 0L
            if (liveSince > 0 && clock.nowMillis() - liveSince >= config.stableConnectionMillis) attempt = 0
            val wait = backoff.delayFor(attempt)
            attempt = (attempt + 1).coerceAtMost(MAX_BACKOFF_ATTEMPT)
            val woken = withTimeoutOrNull(wait) { session.wakeup.receive() }
            if (woken != null) attempt = 0
        }
    }

    /** Aus Netzwerk-Threads: blockiert kurz, falls die Engine hinterherhinkt (Gegendruck). */
    private fun offer(input: Input) {
        inputs.trySendBlocking(input)
    }

    // ---------------------------------------------------------------------------------
    // Ereignisschleife
    // ---------------------------------------------------------------------------------

    private suspend fun runLoop() {
        for (input in inputs) {
            try {
                handle(input)
                reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Fehler in der Sync-Schleife", e)
            }
            publishStatus()
        }
    }

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.Connecting -> if (input.generation == input.session.generation.get()) {
                input.session.state = RelayConnectionState.CONNECTING
            }
            is Input.Opened -> if (input.generation == input.session.generation.get()) {
                onOpened(input.session, input.socket)
            } else {
                input.socket.close()
            }
            is Input.Message -> if (input.generation == input.session.generation.get() && input.session.connected) {
                onMessage(input.session, input.text)
            }
            is Input.Closed -> if (input.generation == input.session.generation.get()) onClosed(input.session, input.error)
            Input.LocalChanged -> deltaDueAt = minOf(deltaDueAt, clock.nowMillis() + config.deltaDebounceMillis)
            Input.Tick -> {
                tickAt = Long.MAX_VALUE
                expireInflight()
            }
            Input.ReconnectNow -> sessions.filter { !it.connected }.forEach { it.wakeup.trySend(Unit) }
            Input.TargetChanged -> applyTarget()
            is Input.Run -> input.block()
        }
    }

    // ---------------------------------------------------------------------------------
    // Ziel (Beitritt oder Gruppe) und Abos
    // ---------------------------------------------------------------------------------

    private suspend fun applyTarget() {
        val record = team.record.value
        val me = team.publicKey
        val next: Target? = when {
            record == null || me == null -> null
            record.mode == GroupMode.JOINING -> Target.Join(me, record.joinSince)
            record.mode == GroupMode.MEMBER -> Target.Group(record.groupId!!, record.nostrGroupId!!)
            else -> null
        }
        if (next == target) return
        logger.debug(TAG, "Neues Ziel: ${next?.javaClass?.simpleName ?: "keins"}")
        saveCursorNow()
        target = next
        anyCaughtUp = false
        liveBuffer.clear()
        pausedEvents.clear()
        lastSeenDigest.clear()
        checkAt.clear()
        repairAt.clear()
        inflightKeys.clear()
        pendingRemovals.clear()
        deltaDueAt = Long.MAX_VALUE
        overviewDueAt = Long.MAX_VALUE
        selfUpdateCheckAt = Long.MAX_VALUE
        cursor = record?.cursor ?: 0L
        floor = record?.floor ?: 0L
        cursorDirty = false
        recoverCommit = if (next is Target.Group) record?.pendingCommit else null
        recoverEvent = null
        if (recoverCommit != null && !commitInFlight) beginCommit()
        // KeyPackage-Veröffentlichung einer früheren Phase nicht weiter nachliefern.
        outbox.values.filter { it.kind == OutgoingKind.KEY_PACKAGE }.map { it.id }.forEach { cancelOutgoing(it) }
        for (session in sessions) if (session.connected) {
            closeSubscriptions(session)
            openSubscriptions(session)
        }
        when (next) {
            is Target.Join -> record?.keyPackage?.let { keyPackage ->
                enqueue(keyPackage, kind = OutgoingKind.KEY_PACKAGE, onAccepted = {
                    team.updateRecord { it.copy(keyPackagePublished = true) }
                })
            }
            is Target.Group -> record?.keyPackage?.let { keyPackage ->
                // Nach dem Beitritt: das verbrauchte KeyPackage löschen lassen (NIP-09).
                deletionFor(keyPackage)?.let { deletion ->
                    enqueue(deletion, onAccepted = { team.updateRecord { it.copy(keyPackage = null) } })
                }
            }
            null -> Unit
        }
    }

    private fun onOpened(session: Session, socket: RelaySocket) {
        session.socket = socket
        session.connected = true
        session.lastError = null
        session.authChallenge = null
        session.authEventId = null
        session.authNeeded = false
        session.authed = false
        session.inflight.clear()
        session.attempts.clear()
        session.queue.clear()
        openSubscriptions(session)
        // Eigene Events, die dieses Relay noch nicht bestätigt hat, nachliefern.
        for (outgoing in outbox.values) if (session.url !in outgoing.finished) session.queue.addLast(outgoing)
        logger.debug(TAG, "Verbunden mit ${session.url}")
    }

    private fun onClosed(session: Session, error: String?) {
        session.connected = false
        session.caughtUp = false
        session.socket = null
        session.mainSubscription = null
        session.pageSubscription = null
        session.catchUp.clear()
        session.inflight.clear()
        session.queue.clear()
        session.state = RelayConnectionState.DISCONNECTED
        if (error != null) session.lastError = error
        for (query in keyPackageQueries.values) query.answered(session.url)
        logger.debug(TAG, "Getrennt von ${session.url}: ${error ?: "regulär"}")
    }

    private fun openSubscriptions(session: Session) {
        val socket = session.socket ?: return
        session.resetCatchUp()
        val filter = when (val current = target) {
            null -> {
                session.caughtUp = true
                session.state = RelayConnectionState.LIVE
                return
            }
            is Target.Join -> RelayMessages.filter(
                kinds = listOf(KIND_GIFT_WRAP),
                tags = mapOf("p" to listOf(current.publicKey)),
                since = (current.since - GIFT_WRAP_BACKDATE_SECONDS).coerceAtLeast(0),
                limit = INVITE_LIMIT,
            )
            is Target.Group -> RelayMessages.filter(
                kinds = listOf(KIND_GROUP_EVENT),
                tags = mapOf("h" to listOf(current.nostrGroupId)),
                since = groupSince(),
                limit = config.pageSize,
            )
        }
        val subscriptionId = newSubscriptionId()
        session.mainSubscription = subscriptionId
        session.state = RelayConnectionState.SYNCING
        socket.send(RelayMessages.req(subscriptionId, filter))
    }

    private fun closeSubscriptions(session: Session) {
        val socket = session.socket ?: return
        session.mainSubscription?.let { socket.send(RelayMessages.close(it)) }
        session.pageSubscription?.let { socket.send(RelayMessages.close(it)) }
        session.mainSubscription = null
        session.pageSubscription = null
    }

    private fun groupSince(): Long = maxOf(floor, cursor - config.cursorOverlapSeconds).coerceAtLeast(0)

    // ---------------------------------------------------------------------------------
    // Nachrichten der Relays
    // ---------------------------------------------------------------------------------

    private suspend fun onMessage(session: Session, text: String) {
        val message = RelayMessages.parse(text)
        if (message == null) {
            session.invalid++
            return
        }
        when (message) {
            is RelayMessage.Event -> onEvent(session, message)
            is RelayMessage.EndOfStoredEvents -> onEndOfStoredEvents(session, message.subscriptionId)
            is RelayMessage.Ok -> onOk(session, message)
            is RelayMessage.Notice -> session.lastNotice = sanitize(message.message)
            is RelayMessage.Closed -> onSubscriptionClosed(session, message)
            is RelayMessage.Auth -> {
                session.authChallenge = message.challenge.take(MAX_CHALLENGE)
                maybeAuthenticate(session)
            }
        }
    }

    private suspend fun onEvent(session: Session, message: RelayMessage.Event) {
        val subscriptionId = message.subscriptionId
        keyPackageQueries[subscriptionId]?.let { query ->
            collectKeyPackage(query, message.event)
            return
        }
        val isMain = subscriptionId == session.mainSubscription
        val isPage = session.pageSubscription != null && subscriptionId == session.pageSubscription
        if (!isMain && !isPage) return
        val event = Nip01.parseEvent(message.event)
        if (event == null) {
            session.invalid++
            return
        }
        when (val current = target) {
            is Target.Join -> onInviteEvent(session, current, event, message.event)
            is Target.Group -> onGroupEvent(session, current, event, message.event, isPage)
            null -> Unit
        }
    }

    private suspend fun onInviteEvent(session: Session, target: Target.Join, event: NostrEvent, json: JsonElement) {
        if (event.kind != KIND_GIFT_WRAP || event.tags.none { it.size >= 2 && it[0] == "p" && it[1] == target.publicKey }) {
            session.invalid++
            return
        }
        if (seen.put(event.id, Unit) != null) return
        session.received++
        try {
            team.receiveInvite(json.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kaputte oder fremde Gift Wraps still verwerfen.
            session.undecryptable++
        }
    }

    private suspend fun onGroupEvent(session: Session, target: Target.Group, event: NostrEvent, json: JsonElement, isPage: Boolean) {
        val nowSeconds = clock.nowMillis() / 1000
        val valid = event.kind == KIND_GROUP_EVENT &&
            Hex.isLowerHex(event.id, 64) &&
            event.tags.count { it.size >= 2 && it[0] == "h" } == 1 &&
            event.tags.first { it.size >= 2 && it[0] == "h" }[1] == target.nostrGroupId &&
            event.createdAt > 0 && event.createdAt <= nowSeconds + MAX_FUTURE_SECONDS &&
            event.content.length <= Limits.MAX_MESSAGE_BYTES
        if (!valid) {
            session.invalid++
            return
        }
        session.received++
        val incoming = Incoming(event.id, event.createdAt, json.toString())
        if (recoverCommit == event.id) recoverEvent = incoming
        if (!session.caughtUp) {
            if (isPage) {
                session.pageCount++
                if (!session.catchUp.containsKey(event.id)) session.pageNew++
            } else {
                session.initialCount++
            }
            if (event.createdAt < session.oldestCreatedAt) session.oldestCreatedAt = event.createdAt
            if (session.catchUp.size < config.maxCatchUpEvents) session.catchUp[event.id] = incoming
            return
        }
        liveBuffer[event.id] = incoming
        if (liveFlushAt == Long.MAX_VALUE) liveFlushAt = clock.nowMillis() + config.liveBatchMillis
    }

    private suspend fun onEndOfStoredEvents(session: Session, subscriptionId: String) {
        keyPackageQueries[subscriptionId]?.let {
            it.answered(session.url)
            return
        }
        when (subscriptionId) {
            session.mainSubscription -> if (!session.caughtUp && session.pageSubscription == null) {
                if (target is Target.Group && session.initialCount >= config.pagingThreshold && session.pagesFetched < config.maxPages) {
                    requestOlderPage(session)
                } else {
                    finishCatchUp(session)
                }
            }
            session.pageSubscription -> {
                session.socket?.send(RelayMessages.close(subscriptionId))
                session.pageSubscription = null
                val full = session.pageCount >= config.pagingThreshold && session.pagesFetched < config.maxPages
                when {
                    full && session.pageNew > 0 -> requestOlderPage(session)
                    // Volle Seite ohne neue Events: viele Events in derselben Sekunde – einmal daran vorbei.
                    full && !session.pageSkippedSecond -> {
                        session.pageSkippedSecond = true
                        requestOlderPage(session, until = session.oldestCreatedAt - 1)
                    }
                    else -> finishCatchUp(session)
                }
            }
        }
    }

    private fun requestOlderPage(session: Session, until: Long = session.oldestCreatedAt) {
        val current = target as? Target.Group ?: return
        session.pagesFetched++
        session.pageCount = 0
        session.pageNew = 0
        val subscriptionId = newSubscriptionId()
        session.pageSubscription = subscriptionId
        val filter = RelayMessages.filter(
            kinds = listOf(KIND_GROUP_EVENT),
            tags = mapOf("h" to listOf(current.nostrGroupId)),
            since = groupSince(),
            until = until,
            limit = config.pageSize,
        )
        session.socket?.send(RelayMessages.req(subscriptionId, filter))
    }

    private suspend fun finishCatchUp(session: Session) {
        val now = clock.nowMillis()
        session.caughtUp = true
        session.state = RelayConnectionState.LIVE
        session.lastSyncAt = now
        session.liveSince = now
        val events = session.catchUp.values.toList()
        session.catchUp.clear()
        if (target is Target.Group) {
            if (recoverCommit != null && !anyCaughtUp) {
                // Ergebnis eines unterbrochenen eigenen Commits: Liegt er auf einem Relay und hat
                // kein früherer Commit derselben Epoche Vorrang?
                val pending = recoverCommit!!
                recoverCommit = null
                val groupId = (target as Target.Group).groupId
                seen[pending] = Unit
                pausedEvents.putAll(events.associateBy { it.id })
                val own = recoverEvent
                recoverEvent = null
                val epoch = runCatching { team.mls { it.team(groupId)?.epoch?.toLong() } }.getOrNull()
                val merge = own != null && epoch != null && !hasBetterCommit(epoch, EventKey(own.createdAt, own.id))
                logger.debug(TAG, "Unterbrochener Commit wird ${if (merge) "übernommen" else "verworfen"}")
                if (merge) lastOwnCommit = OwnCommit(epoch, EventKey(own.createdAt, own.id))
                finishCommit(groupId, merge)
            } else {
                ingest(events)
            }
        }
        if (!anyCaughtUp) {
            anyCaughtUp = true
            onFirstCatchUp()
        }
        logger.debug(TAG, "Abgeglichen mit ${session.url}")
    }

    private fun onFirstCatchUp() {
        if (target !is Target.Group) return
        val now = clock.nowMillis()
        deltaDueAt = now
        val lastOverview = team.record.value?.lastOverviewAt ?: 0L
        if (now - lastOverview >= config.overviewIntervalMillis) overviewDueAt = now + config.overviewDelayMillis
        selfUpdateCheckAt = now + config.selfUpdateDelayMillis + jitter(2 * config.selfUpdateDelayMillis)
    }

    private suspend fun onSubscriptionClosed(session: Session, message: RelayMessage.Closed) {
        val prefix = message.message.substringBefore(':', "").trim().lowercase()
        keyPackageQueries[message.subscriptionId]?.let {
            it.answered(session.url)
            return
        }
        if (message.subscriptionId != session.mainSubscription && message.subscriptionId != session.pageSubscription) return
        if (prefix == "auth-required" && !session.authed) {
            session.authNeeded = true
            session.mainSubscription = null
            session.pageSubscription = null
            maybeAuthenticate(session)
            return
        }
        when (message.subscriptionId) {
            session.mainSubscription -> {
                session.lastError = "Relay hat das Abo beendet: ${sanitize(message.message)}"
                session.socket?.close()
            }
            session.pageSubscription -> session.pageSubscription = null
        }
    }

    // ---------------------------------------------------------------------------------
    // Anmeldung bei Relays (NIP-42), nur wenn ein Relay sie verlangt
    // ---------------------------------------------------------------------------------

    private suspend fun maybeAuthenticate(session: Session) {
        if (!session.authNeeded || session.authed || session.authEventId != null) return
        val challenge = session.authChallenge ?: return
        val socket = session.socket ?: return
        val event = try {
            team.mls { it.signEvent(KIND_AUTH.toUShort(), listOf(listOf("relay", session.url), listOf("challenge", challenge)), "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.lastError = "Anmeldung beim Relay nicht möglich"
            return
        }
        val json = Json.parseToJsonElement(event)
        session.authEventId = Nip01.parseEvent(json)?.id
        socket.send(RelayMessages.auth(json))
    }

    private fun onAuthResult(session: Session, ok: RelayMessage.Ok) {
        session.authEventId = null
        if (!ok.accepted) {
            session.lastError = "Anmeldung abgelehnt: ${sanitize(ok.message)}"
            return
        }
        session.authed = true
        session.authNeeded = false
        if (session.mainSubscription == null) openSubscriptions(session)
        for (outgoing in outbox.values) {
            if (session.url !in outgoing.finished && outgoing !in session.queue && outgoing.id !in session.inflight) {
                session.queue.addLast(outgoing)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Verarbeitung der Gruppen-Events
    // ---------------------------------------------------------------------------------

    private suspend fun flushLive() {
        liveFlushAt = Long.MAX_VALUE
        if (liveBuffer.isEmpty()) return
        val events = liveBuffer.values.toList()
        liveBuffer.clear()
        ingest(events)
    }

    private suspend fun ingest(events: Collection<Incoming>) {
        val current = target as? Target.Group ?: return
        if (commitInFlight) {
            for (event in events) pausedEvents[event.id] = event
            return
        }
        val fresh = events.filter { seen.put(it.id, Unit) == null }
        if (fresh.isEmpty()) return
        val outcomes = try {
            team.mls { mls -> mls.ingest(fresh.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.json }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Gruppen-Events ließen sich nicht verarbeiten", e)
            return
        }
        val deferred = handleOutcomes(outcomes, fresh.associateBy { it.id })
        // Stand für den nächsten Abruf: nichts überspringen, was noch zurückgestellt ist.
        var candidate = fresh.filter { it.id !in deferred }.maxOfOrNull { it.createdAt } ?: return
        fresh.filter { it.id in deferred }.minOfOrNull { it.createdAt }?.let { candidate = minOf(candidate, it - 1) }
        if (candidate > cursor && target == current) {
            cursor = candidate
            cursorDirty = true
        }
    }

    /** @return IDs der zurückgestellten Events. */
    private suspend fun handleOutcomes(outcomes: List<IngestOutcome>, batch: Map<String, Incoming>): Set<String> {
        val current = target as? Target.Group ?: return emptySet()
        val me = team.publicKey
        val deferred = HashSet<String>()
        var changed = false
        var diagnostics = groupDiagnosticsState
        for (outcome in outcomes) {
            when (outcome) {
                is IngestOutcome.AppMessage -> {
                    if (outcome.groupId == current.groupId && outcome.sender != me && outcome.kind.toInt() == GroupMessages.KIND) {
                        applyMessage(outcome.sender, outcome.content)
                    }
                }
                is IngestOutcome.GroupChanged -> changed = true
                is IngestOutcome.PublishRequired -> rejectAutoCommit(outcome.groupId)
                is IngestOutcome.Deferred -> {
                    if (outcome.eventId in batch) deferred += outcome.eventId
                    diagnostics = diagnostics.copy(deferred = diagnostics.deferred + 1)
                }
                is IngestOutcome.Ignored -> {
                    diagnostics = diagnostics.copy(ignored = diagnostics.ignored + 1)
                    batch[outcome.eventId]?.let { event ->
                        if (!diagnostics.forked && lostRace(event)) {
                            logger.warn(TAG, "Eigener Commit hat einen Wettlauf verloren – Gerät muss neu hinzugefügt werden")
                            diagnostics = diagnostics.copy(forked = true)
                        }
                    }
                }
                is IngestOutcome.RolledBack -> {
                    changed = true
                    diagnostics = diagnostics.copy(rollbacks = diagnostics.rollbacks + 1)
                    // Nachrichten aus der verworfenen Epoche haben nicht alle erreicht: bald abgleichen.
                    overviewDueAt = minOf(overviewDueAt, clock.nowMillis() + config.overviewDelayMillis)
                }
            }
        }
        groupDiagnosticsState = diagnostics
        if (outcomes.isNotEmpty()) logger.debug(TAG, "Verarbeitet: ${summarize(outcomes)}")
        if (changed) {
            val now = clock.nowMillis()
            lastCommitSeenAt = now
            team.refresh()
            applyTarget()
            // Hat ein Rücksprung eine Entfernung aufgehoben? Dann bald erneut entfernen.
            val members = (team.state.value as? TeamState.Member)?.team?.members.orEmpty()
            for ((publicKey, removal) in pendingRemovals) {
                if (publicKey in members && removal.nextAttemptAt == Long.MAX_VALUE) {
                    removal.nextAttemptAt = now + jitter(config.removalRetryMillis)
                }
            }
        }
        return deferred
    }

    /**
     * true, wenn [event] ein Commit derselben Epoche wie der zuletzt übernommene eigene ist und
     * nach MIP-03 Vorrang hätte: Die anderen Geräte folgen dann ihm, dieses nicht mehr.
     */
    private suspend fun lostRace(event: Incoming): Boolean {
        val own = lastOwnCommit ?: return false
        if (EventKey(event.createdAt, event.id) >= own.key) return false
        val epoch = try {
            team.mls { it.peekCommit(event.json) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return epoch?.toLong() == own.epoch
    }

    /** Zählt Ergebnisse nach Art (für die Fehlersuche, ohne Inhalte). */
    private fun summarize(outcomes: List<IngestOutcome>): String =
        outcomes.groupingBy {
            when (it) {
                is IngestOutcome.AppMessage -> "Nachricht"
                is IngestOutcome.GroupChanged -> "Commit"
                is IngestOutcome.PublishRequired -> "Auto-Commit"
                is IngestOutcome.Deferred -> "zurückgestellt"
                is IngestOutcome.Ignored -> "verworfen(${it.reason.take(40)})"
                is IngestOutcome.RolledBack -> "Rücksprung"
            }
        }.eachCount().entries.joinToString { "${it.key}=${it.value}" }

    /**
     * MDK bestätigt Austrittsvorschläge (SelfRemove) automatisch mit einem Commit. Die App
     * sendet solche Vorschläge nicht (siehe [leave]); kommt trotzdem einer, wird der Commit
     * verworfen statt veröffentlicht.
     */
    private suspend fun rejectAutoCommit(groupId: String) {
        logger.debug(TAG, "Automatischer Commit für einen Austrittsvorschlag verworfen")
        val outcomes = try {
            team.mls { it.abortPending(groupId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Automatischen Commit verwerfen gescheitert", e)
            return
        }
        handleOutcomes(outcomes, emptyMap())
    }

    private suspend fun applyMessage(sender: String, content: String) {
        val now = clock.nowMillis()
        when (val decoded = GroupMessages.decode(content, now)) {
            is GroupMessages.Decoded.Ok -> when (val message = decoded.message) {
                is GroupMessages.Message.State -> applyState(message, now)
                is GroupMessages.Message.Overview -> applyOverview(message)
                GroupMessages.Message.Leave -> onLeaveRequest(sender, now)
            }
            GroupMessages.Decoded.Unsupported -> Unit
            GroupMessages.Decoded.Malformed ->
                groupDiagnosticsState = groupDiagnosticsState.copy(invalidMessages = groupDiagnosticsState.invalidMessages + 1)
        }
    }

    private suspend fun applyState(message: GroupMessages.Message.State, now: Long) {
        var dropped = 0
        for (part in message.parts) {
            if (part.entries.isNotEmpty()) plan.mergeRemote(part.bucket, part.entries)
            dropped += part.dropped
            // Jemand hat genau den eigenen Stand geschickt: eigene Antwort ist überflüssig.
            if (part.digest == digest(part.bucket)) repairAt.remove(part.bucket)
            // Mit verworfenen Einträgen ist der Digest des Absenders nie erreichbar – kein Vergleich.
            if (part.dropped == 0) {
                lastSeenDigest[part.bucket] = part.digest
                checkAt[part.bucket] = now + config.checkDelayMillis
            }
        }
        if (dropped > 0) groupDiagnosticsState = groupDiagnosticsState.copy(droppedEntries = groupDiagnosticsState.droppedEntries + dropped)
    }

    private fun applyOverview(message: GroupMessages.Message.Overview) {
        val state = plan.state.value
        val buckets = HashSet<String>(message.digests.keys)
        for ((bucket, map) in state.buckets) if (!map.isEmpty() && message.window.contains(bucket)) buckets += bucket
        for (bucket in buckets) {
            val theirs = message.digests[bucket] ?: GroupMessages.EMPTY_DIGEST
            if (theirs != digest(bucket)) scheduleRepair(bucket)
        }
    }

    /** Ein Gerät verlässt das Team: Admins entfernen es, gestaffelt nach Rang. */
    private fun onLeaveRequest(sender: String, now: Long) {
        val member = team.state.value as? TeamState.Member ?: return
        if (!member.isAdmin || sender == member.me || sender !in member.team.members) return
        if (pendingRemovals.containsKey(sender)) return
        val rank = member.team.admins.filter { it in member.team.members }.sorted().indexOf(member.me).coerceAtLeast(0)
        val delayMillis = rank * config.leaveRemovalStepMillis + jitter(config.leaveRemovalStepMillis / 2)
        pendingRemovals[sender] = Removal(now + delayMillis, now + config.removalWatchMillis)
        logger.debug(TAG, "Austrittsbitte erhalten")
    }

    /** Führt fällige Entfernungen aus (Austrittsbitten, aufgehobene Entfernungen). */
    private fun processRemovals(now: Long): Long {
        var wakeAt = Long.MAX_VALUE
        val member = team.state.value as? TeamState.Member
        val iterator = pendingRemovals.entries.iterator()
        while (iterator.hasNext()) {
            val (publicKey, removal) = iterator.next()
            if (member == null || now > removal.watchUntil) {
                iterator.remove()
                continue
            }
            if (publicKey !in member.team.members) {
                removal.nextAttemptAt = Long.MAX_VALUE
                continue
            }
            if (removal.nextAttemptAt > now) {
                wakeAt = minOf(wakeAt, removal.nextAttemptAt)
                continue
            }
            if (!member.isAdmin || removalRunning || commitInFlight) continue
            val engineScope = scope ?: continue
            removalRunning = true
            removal.nextAttemptAt = now + config.removalRetryMillis
            wakeAt = minOf(wakeAt, removal.nextAttemptAt)
            val groupId = member.team.groupId
            engineScope.launch {
                try {
                    opMutex.withLock {
                        val current = team.state.value as? TeamState.Member
                        if (current != null && current.isAdmin && publicKey in current.team.members) {
                            commit(groupId) { mls ->
                                if (publicKey in mls.team(groupId)?.members.orEmpty()) mls.removeMembers(groupId, listOf(publicKey)) to Unit else null
                            }
                            logger.debug(TAG, "Gerät nach Austrittsbitte entfernt")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, "Entfernen nach Austrittsbitte gescheitert", e)
                } finally {
                    runCatching { inLoop { removalRunning = false } }
                }
            }
        }
        return wakeAt
    }

    private fun jitter(maxMillis: Long): Long = random.nextLong(maxMillis.coerceAtLeast(0) + 1)

    private fun scheduleRepair(bucket: String) {
        if (repairAt.containsKey(bucket)) return
        val now = clock.nowMillis()
        val earliest = (lastRepairAt[bucket] ?: 0L) + config.repairCooldownMillis
        val jitter = config.repairJitterMinMillis +
            random.nextLong((config.repairJitterMaxMillis - config.repairJitterMinMillis).coerceAtLeast(0) + 1)
        repairAt[bucket] = maxOf(now, earliest) + jitter
    }

    private fun digest(bucket: String): String = GroupMessages.digestOf(plan.state.value.bucket(bucket))

    // ---------------------------------------------------------------------------------
    // Abgleich und Versand
    // ---------------------------------------------------------------------------------

    private suspend fun reconcile() {
        val now = clock.nowMillis()
        if (liveFlushAt <= now) flushLive()
        var wakeAt = liveFlushAt

        if (target is Target.Group && anyCaughtUp && !commitInFlight) {
            if (deltaDueAt <= now) sendDeltas()
            runChecks(now)
            sendRepairs(now)
            if (overviewDueAt <= now && !overviewSending) sendOverview(now)
            if (selfUpdateCheckAt <= now) checkSelfUpdate()
            if (cursorDirty && now - cursorSavedAt >= config.cursorSaveIntervalMillis) saveCursorNow()
            wakeAt = minOf(wakeAt, processRemovals(now))
            wakeAt = minOf(wakeAt, deltaDueAt, overviewDueAt, selfUpdateCheckAt)
            checkAt.values.minOrNull()?.let { wakeAt = minOf(wakeAt, it) }
            repairAt.values.minOrNull()?.let { wakeAt = minOf(wakeAt, it) }
            if (cursorDirty) wakeAt = minOf(wakeAt, cursorSavedAt + config.cursorSaveIntervalMillis)
        }

        wakeAt = minOf(wakeAt, flushQueues(now))
        wakeAt = minOf(wakeAt, settleOutbox(now))
        scheduleTick(wakeAt)
    }

    private suspend fun sendDeltas() {
        deltaDueAt = Long.MAX_VALUE
        val pending = plan.pendingEntries()
        pendingCount = pending.size
        val toSend = pending.filter { (key, entry) -> inflightKeys[key] != entry.timestamp }
        if (toSend.isEmpty()) return
        val byBucket = HashMap<String, MutableMap<String, Entry>>()
        for ((key, entry) in toSend) {
            val parsed = PlanKeys.parse(key) ?: continue
            byBucket.getOrPut(Buckets.forKey(parsed)) { HashMap() }[key] = entry
        }
        val parts = byBucket.map { (bucket, entries) -> GroupMessages.Part(bucket, digest(bucket), entries) }
        for (message in GroupMessages.pack(parts)) {
            val sent = HashMap<String, Long>()
            for (part in message) for ((key, entry) in part.entries) sent[key] = entry.timestamp
            val event = encryptOrNull(GroupMessages.encodeParts(message)) ?: return
            inflightKeys.putAll(sent)
            enqueue(
                event,
                onAccepted = {
                    plan.markSent(sent)
                    sent.forEach { (key, timestamp) -> inflightKeys.remove(key, timestamp) }
                    deltaDueAt = minOf(deltaDueAt, clock.nowMillis())
                },
                onFailed = {
                    sent.forEach { (key, timestamp) -> inflightKeys.remove(key, timestamp) }
                    deltaDueAt = minOf(deltaDueAt, clock.nowMillis() + config.rateLimitCooldownMillis)
                },
            )
        }
    }

    private fun runChecks(now: Long) {
        val due = checkAt.filterValues { it <= now }.keys.toList()
        for (bucket in due) {
            checkAt.remove(bucket)
            val theirs = lastSeenDigest[bucket] ?: continue
            if (theirs != digest(bucket)) scheduleRepair(bucket)
        }
    }

    private suspend fun sendRepairs(now: Long) {
        val due = repairAt.filterValues { it <= now }.keys.sorted()
        if (due.isEmpty()) return
        due.forEach {
            repairAt.remove(it)
            lastRepairAt[it] = now
        }
        val state = plan.state.value
        val parts = due.map { bucket -> GroupMessages.Part(bucket, digest(bucket), state.bucket(bucket).entries) }
        for (message in GroupMessages.pack(parts)) {
            val event = encryptOrNull(GroupMessages.encodeParts(message)) ?: return
            enqueue(event)
        }
    }

    /** Der ganze Plan, z. B. nach dem Hinzufügen eines Geräts. */
    private suspend fun sendFullState() {
        val state = plan.state.value
        val now = clock.nowMillis()
        val parts = state.buckets.filterValues { !it.isEmpty() }.toSortedMap(compareBy({ it != Buckets.TEAM }, { it }))
            .map { (bucket, map) -> GroupMessages.Part(bucket, GroupMessages.digestOf(map), map.entries) }
        for (part in parts) {
            repairAt.remove(part.bucket)
            lastRepairAt[part.bucket] = now
        }
        for (message in GroupMessages.pack(parts)) {
            val event = encryptOrNull(GroupMessages.encodeParts(message)) ?: return
            enqueue(event)
        }
    }

    private suspend fun sendOverview(now: Long) {
        overviewDueAt = Long.MAX_VALUE
        val window = GroupMessages.Window.around(WeekId.of(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()))
        val digests = plan.state.value.buckets
            .filter { (bucket, map) -> !map.isEmpty() && window.contains(bucket) }
            .mapValues { (_, map) -> GroupMessages.digestOf(map) }
        val event = encryptOrNull(GroupMessages.encodeOverview(window, digests)) ?: return
        overviewSending = true
        enqueue(
            event,
            onAccepted = {
                overviewSending = false
                team.updateRecord { it.copy(lastOverviewAt = now) }
            },
            onFailed = { overviewSending = false },
        )
    }

    private suspend fun checkSelfUpdate() {
        selfUpdateCheckAt = Long.MAX_VALUE
        val current = target as? Target.Group ?: return
        if (selfUpdateRunning) return
        val now = clock.nowMillis()
        val quietSince = lastCommitSeenAt + config.selfUpdateQuietMillis
        if (now < quietSince) {
            // Gerade war ein Commit: später erneut prüfen, damit sich Commits nicht kreuzen.
            selfUpdateCheckAt = quietSince + jitter(config.selfUpdateQuietMillis)
            return
        }
        val needed = try {
            current.groupId in team.mls { it.teamsNeedingSelfUpdate(config.selfUpdateAfterSeconds.toULong()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!needed) return
        val engineScope = scope ?: return
        selfUpdateRunning = true
        engineScope.launch {
            try {
                opMutex.withLock { commit(current.groupId) { it.selfUpdate(current.groupId) to Unit } }
                logger.debug(TAG, "Eigener Schlüssel erneuert")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "Schlüsselerneuerung gescheitert", e)
            } finally {
                runCatching { inLoop { selfUpdateRunning = false } }
            }
        }
    }

    private suspend fun encryptOrNull(content: String): String? {
        val current = target as? Target.Group ?: return null
        return try {
            team.mls { it.encrypt(current.groupId, GroupMessages.KIND.toUShort(), content) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Nachricht ließ sich nicht verschlüsseln", e)
            null
        }
    }

    private suspend fun deletionFor(keyPackage: String): String? {
        val id = runCatching { eventIdOf(keyPackage) }.getOrNull() ?: return null
        return try {
            team.mls {
                it.signEvent(KIND_DELETION.toUShort(), listOf(listOf("e", id), listOf("k", KIND_KEY_PACKAGE.toString())), "")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun saveCursorNow() {
        if (!cursorDirty) return
        val value = cursor
        cursorDirty = false
        cursorSavedAt = clock.nowMillis()
        try {
            team.updateRecord { if (it.mode == GroupMode.MEMBER && value > it.cursor) it.copy(cursor = value) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Stand ließ sich nicht speichern", e)
        }
    }

    // ---------------------------------------------------------------------------------
    // Ausgang: Versand mit Bestätigung, Wiederholung und Tempolimit
    // ---------------------------------------------------------------------------------

    private fun enqueue(
        eventJson: String,
        kind: OutgoingKind = OutgoingKind.OTHER,
        onAccepted: (suspend () -> Unit)? = null,
        onFailed: (suspend () -> Unit)? = null,
        done: CompletableDeferred<Boolean>? = null,
    ): Outgoing? {
        val element = try {
            Json.parseToJsonElement(eventJson)
        } catch (e: Exception) {
            done?.complete(false)
            return null
        }
        val id = Nip01.parseEvent(element)?.id ?: run {
            done?.complete(false)
            return null
        }
        // Das eigene Echo nicht nochmals verarbeiten.
        seen[id] = Unit
        val outgoing = Outgoing(id, RelayMessages.event(element), clock.nowMillis(), kind, onAccepted, onFailed, done)
        outbox[id] = outgoing
        for (session in sessions) if (session.connected) session.queue.addLast(outgoing)
        return outgoing
    }

    private fun cancelOutgoing(id: String) {
        val outgoing = outbox.remove(id) ?: return
        for (session in sessions) session.queue.remove(outgoing)
        outgoing.done?.complete(false)
    }

    /** Sendet Wartendes im Rahmen des Tempolimits. @return nächster Zeitpunkt mit Arbeit. */
    private fun flushQueues(now: Long): Long {
        var wakeAt = Long.MAX_VALUE
        val second = now / 1000
        for (session in sessions) {
            val socket = session.socket
            if (!session.connected || socket == null || session.queue.isEmpty()) continue
            if (session.cooldownUntil > now) {
                wakeAt = minOf(wakeAt, session.cooldownUntil)
                continue
            }
            if (session.authNeeded && !session.authed && session.authChallenge != null) continue
            if (session.sendSecond != second) {
                session.sendSecond = second
                session.sentInSecond = 0
            }
            while (session.queue.isNotEmpty()) {
                if (session.sentInSecond >= config.maxEventsPerSecond) {
                    wakeAt = minOf(wakeAt, (second + 1) * 1000)
                    break
                }
                val outgoing = session.queue.removeFirst()
                if (outbox[outgoing.id] !== outgoing || session.url in outgoing.finished || outgoing.id in session.inflight) continue
                if (!socket.send(outgoing.text)) break
                session.inflight[outgoing.id] = now
                session.attempts[outgoing.id] = (session.attempts[outgoing.id] ?: 0) + 1
                session.sentInSecond++
                wakeAt = minOf(wakeAt, now + config.okTimeoutMillis)
            }
        }
        return wakeAt
    }

    private suspend fun onOk(session: Session, ok: RelayMessage.Ok) {
        if (ok.eventId == session.authEventId) {
            onAuthResult(session, ok)
            return
        }
        if (session.inflight.remove(ok.eventId) == null) return
        val outgoing = outbox[ok.eventId] ?: return
        val prefix = ok.message.substringBefore(':', "").trim().lowercase()
        if (ok.accepted || prefix == "duplicate") {
            session.accepted++
            session.lastSyncAt = clock.nowMillis()
            finish(session, outgoing, success = true)
            return
        }
        session.rejected++
        session.lastError = "Event abgelehnt: ${sanitize(ok.message)}"
        val attempts = session.attempts[outgoing.id] ?: 0
        when {
            prefix == "auth-required" && !session.authed -> {
                session.attempts[outgoing.id] = attempts - 1
                session.queue.addFirst(outgoing)
                session.authNeeded = true
                maybeAuthenticate(session)
            }
            prefix in PERMANENT_REJECTIONS || attempts >= config.maxPublishAttempts -> finish(session, outgoing, success = false)
            else -> {
                if (prefix == "rate-limited") session.cooldownUntil = clock.nowMillis() + config.rateLimitCooldownMillis
                session.queue.addLast(outgoing)
            }
        }
    }

    private fun expireInflight() {
        val now = clock.nowMillis()
        for (session in sessions) {
            val iterator = session.inflight.entries.iterator()
            while (iterator.hasNext()) {
                val (id, sentAt) = iterator.next()
                if (now - sentAt < config.okTimeoutMillis) continue
                iterator.remove()
                val outgoing = outbox[id] ?: continue
                session.lastError = "Keine Bestätigung (OK) vom Relay erhalten"
                if ((session.attempts[id] ?: 0) >= config.maxPublishAttempts) {
                    outgoing.finished += session.url
                } else {
                    session.queue.addLast(outgoing)
                }
            }
        }
    }

    private suspend fun finish(session: Session, outgoing: Outgoing, success: Boolean) {
        outgoing.finished += session.url
        if (success && !outgoing.accepted) {
            outgoing.accepted = true
            settle(outgoing, accepted = true)
        }
    }

    private suspend fun settle(outgoing: Outgoing, accepted: Boolean) {
        if (outgoing.settled) return
        outgoing.settled = true
        outgoing.done?.complete(accepted)
        try {
            if (accepted) outgoing.onAccepted?.invoke() else outgoing.onFailed?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Nachbearbeitung eines Versands gescheitert", e)
        }
    }

    /**
     * Räumt den Ausgang auf: von allen Relays abgeschlossen oder zu alt. Was kein Relay
     * angenommen hat, gilt dann als gescheitert. @return nächster Prüfzeitpunkt.
     */
    private suspend fun settleOutbox(now: Long): Long {
        var wakeAt = Long.MAX_VALUE
        val settleAfter = config.okTimeoutMillis * config.maxPublishAttempts
        for (outgoing in outbox.values.toList()) {
            val allFinished = sessions.all { it.url in outgoing.finished }
            val age = now - outgoing.createdAt
            if (!outgoing.accepted && (allFinished || age >= settleAfter)) settle(outgoing, accepted = false)
            if (allFinished || age >= config.outboxRetentionMillis || (outgoing.settled && !outgoing.accepted)) {
                outbox.remove(outgoing.id)
                for (session in sessions) session.queue.remove(outgoing)
            } else {
                wakeAt = minOf(wakeAt, outgoing.createdAt + if (outgoing.accepted) config.outboxRetentionMillis else settleAfter)
            }
        }
        return wakeAt
    }

    private fun collectKeyPackage(query: KeyPackageQuery, json: JsonElement) {
        val event = Nip01.parseEvent(json) ?: return
        if (event.kind != KIND_KEY_PACKAGE || event.pubkey != query.publicKey) return
        if (event.content.length > MAX_KEY_PACKAGE_CHARS || query.events.size >= KEY_PACKAGE_LIMIT * 3) return
        query.events.putIfAbsent(event.id, event.createdAt to json.toString())
    }

    private fun scheduleTick(at: Long) {
        if (at == Long.MAX_VALUE || at >= tickAt) return
        val engineScope = scope ?: return
        tickAt = at
        val wait = (at - clock.nowMillis()).coerceIn(MIN_TICK_MILLIS, maxOf(MIN_TICK_MILLIS, config.maxTickMillis))
        engineScope.launch {
            delay(wait)
            inputs.send(Input.Tick)
        }
    }

    private fun publishStatus() {
        var live = 0
        var connecting = 0
        for (session in sessions) {
            when {
                session.connected && session.caughtUp -> live++
                session.state == RelayConnectionState.CONNECTING || session.state == RelayConnectionState.SYNCING -> connecting++
            }
        }
        val unsent = outbox.values.count { !it.accepted && !it.settled && it.kind != OutgoingKind.KEY_PACKAGE }
        // Eine gerade gemachte Änderung zählt schon, bevor sie nach der Entprellung rausgeht.
        val scheduled = if (target is Target.Group && deltaDueAt != Long.MAX_VALUE) 1 else 0
        val pending = if (target is Target.Group) maxOf(pendingCount, scheduled) + unsent else unsent
        _status.value = SyncStatus(live, sessions.size, connecting, pending, running = scope != null)
        _diagnostics.value = sessions.map { it.diagnostics() }
        val epoch = (team.state.value as? TeamState.Member)?.team?.epoch ?: 0L
        val diagnostics = groupDiagnosticsState.copy(epoch = epoch)
        if (_groupDiagnostics.value != diagnostics) _groupDiagnostics.value = diagnostics
    }

    private fun eventIdOf(eventJson: String): String = eventKeyOf(eventJson).id

    private fun eventKeyOf(eventJson: String): EventKey {
        val event = Nip01.parseEvent(Json.parseToJsonElement(eventJson)) ?: throw IllegalArgumentException("Kein Event")
        return EventKey(event.createdAt, event.id)
    }

    private fun newSubscriptionId(): String = "dp-" + Hex.encode(SecureRandomBytes.next(6))

    private fun sanitize(text: String): String = text.filter { !it.isISOControl() }.take(MAX_RELAY_TEXT)

    // ---------------------------------------------------------------------------------
    // Typen
    // ---------------------------------------------------------------------------------

    private sealed interface Target {
        data class Join(val publicKey: String, val since: Long) : Target
        data class Group(val groupId: String, val nostrGroupId: String) : Target
    }

    private sealed interface Input {
        class Connecting(val session: Session, val generation: Int) : Input
        class Opened(val session: Session, val generation: Int, val socket: RelaySocket) : Input
        class Message(val session: Session, val generation: Int, val text: String) : Input
        class Closed(val session: Session, val generation: Int, val error: String?) : Input
        data object LocalChanged : Input
        data object Tick : Input
        data object ReconnectNow : Input
        data object TargetChanged : Input
        class Run(val block: suspend () -> Unit) : Input
    }

    private data class Incoming(val id: String, val createdAt: Long, val json: String)

    private class Removal(var nextAttemptAt: Long, val watchUntil: Long)

    /** Reihenfolge nach MIP-03: früheres created_at zuerst, bei Gleichstand die kleinere ID. */
    private data class EventKey(val createdAt: Long, val id: String) : Comparable<EventKey> {
        override fun compareTo(other: EventKey): Int =
            compareValuesBy(this, other, EventKey::createdAt, EventKey::id)
    }

    private data class OwnCommit(val epoch: Long, val key: EventKey)

    private enum class CommitResult { ACCEPTED, NOT_PUBLISHED, LOST }

    private enum class OutgoingKind { KEY_PACKAGE, OTHER }

    private class Outgoing(
        val id: String,
        /** Fertige `["EVENT", …]`-Nachricht. */
        val text: String,
        val createdAt: Long,
        val kind: OutgoingKind,
        val onAccepted: (suspend () -> Unit)?,
        val onFailed: (suspend () -> Unit)?,
        val done: CompletableDeferred<Boolean>?,
    ) {
        var accepted = false
        var settled = false
        /** Relays, bei denen der Versand abgeschlossen ist (bestätigt oder aufgegeben). */
        val finished = HashSet<String>()
    }

    private class KeyPackageQuery(val subscriptionId: String, val publicKey: String, private val waiting: MutableSet<String>) {
        val events = LinkedHashMap<String, Pair<Long, String>>()
        val done = CompletableDeferred<Unit>()

        fun answered(url: String) {
            waiting.remove(url)
            if (waiting.isEmpty()) done.complete(Unit)
        }
    }

    private class Session(val url: String) {
        // Werden auch ausserhalb der Ereignisschleife gelesen/geschrieben:
        val generation = AtomicInteger(0)
        val wakeup = Channel<Unit>(Channel.CONFLATED)
        @Volatile var liveSince = 0L

        // Nur innerhalb der Ereignisschleife:
        var socket: RelaySocket? = null
        var connected = false
        var caughtUp = false
        var mainSubscription: String? = null
        var pageSubscription: String? = null
        var initialCount = 0
        var pageCount = 0
        var pageNew = 0
        var pagesFetched = 0
        var pageSkippedSecond = false
        var oldestCreatedAt = Long.MAX_VALUE
        val catchUp = LinkedHashMap<String, Incoming>()
        val queue = ArrayDeque<Outgoing>()
        val inflight = HashMap<String, Long>()
        val attempts = HashMap<String, Int>()
        var cooldownUntil = 0L
        var sendSecond = -1L
        var sentInSecond = 0
        var authChallenge: String? = null
        var authNeeded = false
        var authEventId: String? = null
        var authed = false
        var state = RelayConnectionState.CONNECTING
        var lastError: String? = null
        var lastNotice: String? = null
        var lastSyncAt: Long? = null
        var received = 0
        var accepted = 0
        var rejected = 0
        var undecryptable = 0
        var invalid = 0

        fun resetCatchUp() {
            caughtUp = false
            initialCount = 0
            pageCount = 0
            pageNew = 0
            pagesFetched = 0
            pageSkippedSecond = false
            oldestCreatedAt = Long.MAX_VALUE
            catchUp.clear()
            pageSubscription = null
        }

        fun diagnostics() = RelayDiagnostics(
            url = url,
            state = state,
            lastError = lastError,
            lastSyncAt = lastSyncAt,
            lastNotice = lastNotice,
            eventsReceived = received,
            eventsAccepted = accepted,
            eventsRejected = rejected,
            undecryptable = undecryptable,
            invalid = invalid,
            droppedEntries = 0,
        )
    }

    companion object {
        private const val TAG = "GroupSyncEngine"
        const val KIND_GROUP_EVENT = 445
        const val KIND_GIFT_WRAP = 1059
        const val KIND_KEY_PACKAGE = 30443
        const val KIND_DELETION = 5
        const val KIND_AUTH = 22242
        private const val MAX_FUTURE_SECONDS = 60 * 60L
        /** NIP-59 datiert Gift Wraps bis zu zwei Tage zurück. */
        private const val GIFT_WRAP_BACKDATE_SECONDS = 2 * 24 * 60 * 60L + 600
        private const val INVITE_LIMIT = 100
        private const val KEY_PACKAGE_LIMIT = 10
        private const val MAX_KEY_PACKAGE_CHARS = 64 * 1024
        private const val MAX_CHALLENGE = 256
        private const val MAX_RELAY_TEXT = 160
        private const val MAX_BACKOFF_ATTEMPT = 30
        private const val MIN_TICK_MILLIS = 10L
        private const val SEEN_CAPACITY = 8192
        private const val COMMIT_RETRY_MILLIS = 200L
        private const val MAX_COMMIT_ATTEMPTS = 3
        private const val FINISH_TIMEOUT_MILLIS = 5_000L
        private const val CANCEL_DELETE_MILLIS = 5_000L
        private const val STOP_SAVE_MILLIS = 2_000L

        /** Ablehnungen, bei denen ein erneuter Versuch nichts bringt. */
        private val PERMANENT_REJECTIONS = setOf("blocked", "restricted", "pow", "invalid", "auth-required")
    }
}
