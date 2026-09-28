package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.crypto.TeamKeys
import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.nostr.NostrEvent
import ch.digitana.dienstplan.core.nostr.RelayMessage
import ch.digitana.dienstplan.core.nostr.RelayMessages
import ch.digitana.dienstplan.core.util.Clock
import ch.digitana.dienstplan.core.util.Hex
import ch.digitana.dienstplan.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

data class SyncConfig(
    /** Höchstens so viele Versuche pro Relay, Bucket und Stand. */
    val maxPublishAttempts: Int = 5,
    /** Ohne OK-Antwort innerhalb dieser Zeit gilt ein Versand als gescheitert. */
    val okTimeoutMillis: Long = 15_000,
    /** `limit` pro Abfrage. */
    val pageSize: Int = 500,
    /** Ab so vielen Events pro Seite wird mit `until` weiter zurückgeblättert. */
    val pagingThreshold: Int = 20,
    val maxPages: Int = 50,
    /** Pause nach `rate-limited:`. */
    val rateLimitCooldownMillis: Long = 10_000,
    val backoffBaseMillis: Long = 1_000,
    val backoffMaxMillis: Long = 60_000,
    /** Nach so langer stabiler Verbindung beginnt der Backoff wieder von vorn. */
    val stableConnectionMillis: Long = 30_000,
    /** Längste Pause zwischen zwei internen Prüfungen (Timeouts, Sekundenregel). */
    val maxTickMillis: Long = 60_000,
    /** Höchstens so viele Events pro Sekunde insgesamt (z. B. beim Befüllen eines leeren Relays). */
    val maxEventsPerSecond: Int = 50,
)

/**
 * Synchronisiert den Plan eines Teams über mehrere Nostr-Relays.
 *
 * Grundidee (zustandsbasiert, „level-triggered“): Für jedes Relay merkt sich die Engine,
 * welchen Stand es von jedem Bucket gespeichert hat (Digest des letzten gesehenen
 * bzw. bestätigten Events). Nach jedem Ereignis vergleicht [reconcile] das mit dem lokalen
 * Stand und schickt fehlende Buckets nach. Daraus ergeben sich:
 *
 *  - Anti-Entropie nach EOSE: Was ein Relay nicht (aktuell) hat, wird gesendet.
 *  - Live-Events, denen lokal bekannte Einträge fehlen: Der zusammengeführte Stand geht
 *    an das Relay zurück.
 *  - Abgelehnte Events: erneuter Versand mit neuerem `created_at`, höchstens
 *    [SyncConfig.maxPublishAttempts]-mal pro Stand.
 *  - Höchstens ein Event pro Bucket und Sekunde; `created_at` ist nie kleiner als die
 *    aktuelle Sekunde und immer grösser als das letzte eigene oder gesehene Event.
 *  - Insgesamt höchstens [SyncConfig.maxEventsPerSecond] Events pro Sekunde (Team-Bucket
 *    und Wochen um heute zuerst), damit ein neues Gerät per `until` alles nachladen kann.
 *
 * Alle Zustandsänderungen passieren in einer einzigen Coroutine ([runLoop]); Netzwerk-
 * Callbacks stellen nur Nachrichten in einen Kanal. Dadurch gibt es keine Sperren.
 */
class SyncEngine(
    private val keys: TeamKeys,
    private val store: SyncStore,
    relayUrls: List<String>,
    private val transport: RelayTransport,
    private val clock: Clock = Clock.System,
    private val config: SyncConfig = SyncConfig(),
    private val logger: Logger = Logger.None,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Sicht der Engine auf den lokalen Speicher. */
    interface SyncStore {
        val state: StateFlow<PlanState>
        /** Meldet lokale Änderungen (Eingaben der Nutzerin oder des Nutzers). */
        val localChanges: Flow<Any>
        /** Führt validierte Einträge von einem Relay ein. */
        suspend fun mergeRemote(bucket: String, entries: Map<String, Entry>): Boolean
    }

    private val sessions: List<Session> = relayUrls.distinct().map { Session(it) }
    private val inputs = Channel<Input>(capacity = 256)
    private val backoff = Backoff(config.backoffBaseMillis, config.backoffMaxMillis)

    private val dTags = HashMap<String, String>()
    private val maxSeenCreatedAt = HashMap<String, Long>()
    private val lastPublishSecond = HashMap<String, Long>()
    private val lastCreatedAt = HashMap<String, Long>()
    private val decodeCache = object : LinkedHashMap<String, Decoded>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Decoded>?) = size > 2048
    }
    private var tickAt = Long.MAX_VALUE
    private var budgetSecond = -1L
    private var budgetUsed = 0
    private var pendingBuckets = 0
    private var scope: CoroutineScope? = null
    private var started = false

    private val _status = MutableStateFlow(SyncStatus.stopped(sessions.size))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow(sessions.map { it.diagnostics() })
    val diagnostics: StateFlow<List<RelayDiagnostics>> = _diagnostics.asStateFlow()

    init {
        require(sessions.isNotEmpty()) { "Mindestens ein Relay nötig" }
        sessions.forEach { require(RelayUrls.isValid(it.url)) { "Ungültige Relay-URL: ${it.url}" } }
    }

    /** Startet den Sync. Eine Engine lässt sich genau einmal starten. */
    fun start() {
        check(!started) { "SyncEngine wurde bereits gestartet" }
        started = true
        val handler = CoroutineExceptionHandler { _, error -> logger.warn(TAG, "Unerwarteter Fehler im Sync", error) }
        val engineScope = CoroutineScope(SupervisorJob() + dispatcher + handler)
        scope = engineScope
        _status.value = SyncStatus(0, sessions.size, sessions.size, 0, running = true)
        engineScope.launch { runLoop() }
        engineScope.launch { store.localChanges.collect { inputs.send(Input.LocalChanged) } }
        for (session in sessions) engineScope.launch { connectionLoop(session) }
    }

    /** Beendet alle Verbindungen. */
    suspend fun stop() {
        val engineScope = scope ?: return
        scope = null
        engineScope.coroutineContext[Job]?.cancelAndJoin()
        // Erst danach schliessen: Netzwerk-Threads, die noch senden wollen, kehren sofort zurück.
        inputs.close()
        _status.value = SyncStatus.stopped(sessions.size)
    }

    /** Nach Netzwechsel: getrennte Relays sofort neu verbinden, ohne Backoff abzuwarten. */
    fun reconnectNow() {
        inputs.trySend(Input.ReconnectNow)
    }

    /** Wartet, bis alle lokalen Änderungen bei den erreichbaren Relays angekommen sind. */
    suspend fun awaitIdle(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            status.first { it.isLive && it.pendingBuckets == 0 }
        } != null

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
            is Input.Connecting -> if (input.isCurrent()) {
                input.session.state = RelayConnectionState.CONNECTING
            }
            is Input.Opened -> if (input.isCurrent()) onOpened(input.session, input.socket) else input.socket.close()
            is Input.Message -> if (input.isCurrent() && input.session.connected) onMessage(input.session, input.text)
            is Input.Closed -> if (input.isCurrent()) onClosed(input.session, input.error)
            Input.LocalChanged -> Unit
            Input.Tick -> {
                tickAt = Long.MAX_VALUE
                expireInflight()
            }
            Input.ReconnectNow -> sessions.filter { !it.connected }.forEach { it.wakeup.trySend(Unit) }
        }
    }

    private fun onOpened(session: Session, socket: RelaySocket) {
        session.socket = socket
        session.connected = true
        session.synced = false
        session.state = RelayConnectionState.SYNCING
        session.lastError = null
        session.view.clear()
        session.inflight.clear()
        session.failures.clear()
        session.seenIds.clear()
        session.initialEventCount = 0
        session.pagesFetched = 0
        session.pageSkippedSecond = false
        session.oldestCreatedAt = Long.MAX_VALUE
        session.pageSubscriptionId = null
        val subscriptionId = newSubscriptionId()
        session.subscriptionId = subscriptionId
        socket.send(RelayMessages.req(subscriptionId, RelayMessages.filter(KIND, keys.publicKeyHex, config.pageSize)))
        logger.debug(TAG, "Verbunden mit ${session.url}")
    }

    private fun onClosed(session: Session, error: String?) {
        session.connected = false
        session.synced = false
        session.socket = null
        session.subscriptionId = null
        session.pageSubscriptionId = null
        session.inflight.clear()
        session.state = RelayConnectionState.DISCONNECTED
        if (error != null) session.lastError = error
        logger.debug(TAG, "Getrennt von ${session.url}: ${error ?: "regulär"}")
    }

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
            is RelayMessage.Auth -> session.lastNotice = "Relay verlangt Anmeldung (NIP-42) – wird nicht unterstützt"
        }
    }

    private suspend fun onEvent(session: Session, message: RelayMessage.Event) {
        val isMain = message.subscriptionId == session.subscriptionId
        val isPage = session.pageSubscriptionId != null && message.subscriptionId == session.pageSubscriptionId
        if (!isMain && !isPage) return

        val event = Nip01.parseEvent(message.event)
        if (event == null) {
            session.invalid++
            return
        }
        if (!session.synced) {
            if (isPage) {
                session.pageEventCount++
                if (session.seenIds.add(event.id)) session.pageNewEvents++
            } else {
                session.initialEventCount++
                session.seenIds.add(event.id)
            }
            if (event.createdAt < session.oldestCreatedAt) session.oldestCreatedAt = event.createdAt
        }

        when (val decoded = decode(event)) {
            Decoded.Invalid -> session.invalid++
            is Decoded.Opaque -> {
                if (decoded.reason == BucketCodec.RejectReason.UNDECRYPTABLE) session.undecryptable++ else session.invalid++
                noteRelayVersion(session, decoded.dTag, event, digest = null)
            }
            is Decoded.Valid -> {
                session.received++
                session.dropped += decoded.dropped
                if (decoded.entries.isNotEmpty()) store.mergeRemote(decoded.bucket, decoded.entries)
                noteRelayVersion(session, decoded.dTag, event, decoded.digest)
            }
        }
    }

    private fun onEndOfStoredEvents(session: Session, subscriptionId: String) {
        when (subscriptionId) {
            session.subscriptionId -> if (!session.synced && session.pageSubscriptionId == null) {
                if (session.initialEventCount >= config.pagingThreshold && session.pagesFetched < config.maxPages) {
                    requestOlderPage(session)
                } else {
                    markSynced(session)
                }
            }
            session.pageSubscriptionId -> {
                session.socket?.send(RelayMessages.close(subscriptionId))
                session.pageSubscriptionId = null
                val full = session.pageEventCount >= config.pagingThreshold && session.pagesFetched < config.maxPages
                when {
                    full && session.pageNewEvents > 0 -> requestOlderPage(session)
                    // Volle Seite ohne neue Events: Viele Events teilen sich dieselbe Sekunde.
                    // Einmal an dieser Sekunde vorbei weiterblättern.
                    full && !session.pageSkippedSecond -> {
                        session.pageSkippedSecond = true
                        requestOlderPage(session, until = session.oldestCreatedAt - 1)
                    }
                    else -> markSynced(session)
                }
            }
        }
    }

    /** Relays liefern pro Abfrage nur begrenzt viele Events – ältere per `until` nachladen. */
    private fun requestOlderPage(session: Session, until: Long = session.oldestCreatedAt) {
        session.pagesFetched++
        session.pageEventCount = 0
        session.pageNewEvents = 0
        val subscriptionId = newSubscriptionId()
        session.pageSubscriptionId = subscriptionId
        val filter = RelayMessages.filter(KIND, keys.publicKeyHex, config.pageSize, until = until)
        session.socket?.send(RelayMessages.req(subscriptionId, filter))
    }

    private fun markSynced(session: Session) {
        val now = clock.nowMillis()
        session.synced = true
        session.state = RelayConnectionState.LIVE
        session.lastSyncAt = now
        session.liveSince = now
        session.seenIds.clear()
        logger.debug(TAG, "Abgeglichen mit ${session.url}")
    }

    private fun onSubscriptionClosed(session: Session, message: RelayMessage.Closed) {
        when (message.subscriptionId) {
            session.subscriptionId -> {
                session.lastError = "Relay hat das Abo beendet: ${sanitize(message.message)}"
                session.socket?.close()
            }
            session.pageSubscriptionId -> {
                session.pageSubscriptionId = null
                markSynced(session)
            }
        }
    }

    private fun onOk(session: Session, ok: RelayMessage.Ok) {
        val match = session.inflight.entries.firstOrNull { it.value.eventId == ok.eventId } ?: return
        val bucket = match.key
        val inflight = match.value
        session.inflight.remove(bucket)
        val prefix = ok.message.substringBefore(':', "").trim().lowercase()
        if (ok.accepted || prefix == "duplicate") {
            session.accepted++
            session.failures.remove(bucket)
            session.lastSyncAt = clock.nowMillis()
            val dTag = dTagFor(bucket)
            noteCreatedAt(dTag, inflight.createdAt)
            val current = session.view[dTag]
            if (current == null || isNewer(inflight.createdAt, inflight.eventId, current)) {
                session.view[dTag] = RelayView(inflight.eventId, inflight.createdAt, inflight.digest)
            }
        } else {
            session.rejected++
            session.lastError = "Event abgelehnt: ${sanitize(ok.message)}"
            recordFailure(session, bucket, inflight.digest, permanent = prefix in PERMANENT_REJECTIONS)
            if (prefix == "rate-limited") {
                session.cooldownUntil = clock.nowMillis() + config.rateLimitCooldownMillis
            }
        }
    }

    private fun expireInflight() {
        val now = clock.nowMillis()
        for (session in sessions) {
            val iterator = session.inflight.entries.iterator()
            while (iterator.hasNext()) {
                val (bucket, inflight) = iterator.next()
                if (now - inflight.sentAt >= config.okTimeoutMillis) {
                    iterator.remove()
                    session.lastError = "Keine Bestätigung (OK) vom Relay erhalten"
                    recordFailure(session, bucket, inflight.digest, permanent = false)
                }
            }
        }
    }

    private fun recordFailure(session: Session, bucket: String, digest: String, permanent: Boolean) {
        val previous = session.failures[bucket]
        val count = if (previous != null && previous.digest == digest) previous.count + 1 else 1
        session.failures[bucket] = Failure(digest, if (permanent) config.maxPublishAttempts else count)
    }

    // ---------------------------------------------------------------------------------
    // Abgleich
    // ---------------------------------------------------------------------------------

    private fun reconcile() {
        val state = store.state.value
        val nowMillis = clock.nowMillis()
        val nowSecond = nowMillis / 1000
        var wakeAt = Long.MAX_VALUE
        val targets = LinkedHashMap<String, MutableList<Session>>()
        val pending = HashSet<String>()

        for (session in sessions) {
            if (!session.connected || !session.synced) continue
            for ((bucket, map) in state.buckets) {
                if (map.isEmpty()) continue
                val digest = map.digest
                if (session.view[dTagFor(bucket)]?.digest == digest) continue
                val failure = session.failures[bucket]
                if (failure != null && failure.digest == digest && failure.count >= config.maxPublishAttempts) continue
                pending += bucket
                val inflight = session.inflight[bucket]
                if (inflight != null) {
                    wakeAt = minOf(wakeAt, inflight.sentAt + config.okTimeoutMillis)
                    continue
                }
                if (session.cooldownUntil > nowMillis) {
                    wakeAt = minOf(wakeAt, session.cooldownUntil)
                    continue
                }
                targets.getOrPut(bucket) { ArrayList() }.add(session)
            }
        }

        if (budgetSecond != nowSecond) {
            budgetSecond = nowSecond
            budgetUsed = 0
        }
        val currentWeek = currentWeekIndex(nowMillis)
        for (bucket in targets.keys.sortedBy { priority(it, currentWeek) }) {
            val relays = targets.getValue(bucket)
            val previousSecond = lastPublishSecond[bucket]
            if (previousSecond != null && nowSecond <= previousSecond) {
                // Höchstens ein Event pro Bucket und Sekunde.
                wakeAt = minOf(wakeAt, (previousSecond + 1) * 1000)
                continue
            }
            if (budgetUsed >= config.maxEventsPerSecond) {
                // Gesamtbudget: So teilen sich nie mehr Events eine Sekunde, als sich per
                // `until` wieder vollständig abrufen lassen.
                wakeAt = minOf(wakeAt, (nowSecond + 1) * 1000)
                break
            }
            val map = state.bucket(bucket)
            if (map.size > Limits.MAX_ENTRIES_PER_EVENT) {
                relays.forEach { it.lastError = "Zu viele Einträge in einer Woche (> ${Limits.MAX_ENTRIES_PER_EVENT})" }
                continue
            }
            val dTag = dTagFor(bucket)
            val createdAt = maxOf(
                nowSecond,
                (lastCreatedAt[bucket] ?: 0L) + 1,
                (maxSeenCreatedAt[dTag] ?: 0L) + 1,
            )
            val content = BucketCodec.encrypt(keys.packetCipher, dTag, bucket, map)
            val event = Nip01.sign(keys, createdAt, KIND, listOf(listOf("d", dTag)), content)
            val text = RelayMessages.event(event)
            lastPublishSecond[bucket] = nowSecond
            lastCreatedAt[bucket] = createdAt
            budgetUsed++
            // Das eigene Echo muss nicht erneut entschlüsselt werden.
            decodeCache[event.id] = Decoded.Valid(dTag, bucket, map.entries, 0, map.digest)
            for (session in relays) {
                val socket = session.socket ?: continue
                if (socket.send(text)) {
                    session.inflight[bucket] = Inflight(event.id, map.digest, createdAt, nowMillis)
                    wakeAt = minOf(wakeAt, nowMillis + config.okTimeoutMillis)
                }
            }
        }
        pendingBuckets = pending.size
        scheduleTick(wakeAt)
    }

    /** Team-Bucket zuerst, danach Wochen nach Abstand zur aktuellen Woche. */
    private fun priority(bucket: String, currentWeek: Long): Long {
        val week = Buckets.parseWeek(bucket) ?: return 0
        return 1 + abs(week.monday.toEpochDay() / 7 - currentWeek)
    }

    private fun currentWeekIndex(nowMillis: Long): Long =
        WeekId.of(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()).monday.toEpochDay() / 7

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
                session.connected && session.synced -> live++
                session.state == RelayConnectionState.CONNECTING || session.state == RelayConnectionState.SYNCING -> connecting++
            }
        }
        _status.value = SyncStatus(live, sessions.size, connecting, pendingBuckets, running = scope != null)
        _diagnostics.value = sessions.map { it.diagnostics() }
    }

    // ---------------------------------------------------------------------------------
    // Events prüfen und entschlüsseln
    // ---------------------------------------------------------------------------------

    private fun decode(event: NostrEvent): Decoded {
        decodeCache[event.id]?.let { return it }
        val result = decodeUncached(event)
        // Nur Ergebnisse cachen, deren ID und Signatur geprüft sind – sonst könnte ein Relay
        // mit einer gefälschten Kopie die echte Version desselben Events „vergiften“.
        if (result !== Decoded.Invalid) decodeCache[event.id] = result
        return result
    }

    private fun decodeUncached(event: NostrEvent): Decoded {
        if (event.kind != KIND || event.pubkey != keys.publicKeyHex) return Decoded.Invalid
        val dTag = singleDTag(event) ?: return Decoded.Invalid
        val nowMillis = clock.nowMillis()
        if (event.createdAt <= 0 || event.createdAt > nowMillis / 1000 + MAX_FUTURE_SECONDS) return Decoded.Invalid
        if (event.content.length > Limits.MAX_MESSAGE_BYTES) return Decoded.Invalid
        if (!Nip01.verify(event)) return Decoded.Invalid
        return when (val result = BucketCodec.decrypt(keys.packetCipher, dTag, event.content, nowMillis, ::dTagFor)) {
            is BucketCodec.Result.Ok ->
                Decoded.Valid(dTag, result.bucket, result.entries, result.dropped, LwwMap.of(result.entries).digest)
            is BucketCodec.Result.Rejected -> Decoded.Opaque(dTag, result.reason)
        }
    }

    /** Genau ein Tag: `["d", <64 Hex-Zeichen>]`. */
    private fun singleDTag(event: NostrEvent): String? {
        if (event.tags.size != 1) return null
        val tag = event.tags[0]
        if (tag.size != 2 || tag[0] != "d" || !Hex.isLowerHex(tag[1], 64)) return null
        return tag[1]
    }

    private fun noteRelayVersion(session: Session, dTag: String, event: NostrEvent, digest: String?) {
        noteCreatedAt(dTag, event.createdAt)
        val current = session.view[dTag]
        if (current == null || isNewer(event.createdAt, event.id, current)) {
            session.view[dTag] = RelayView(event.id, event.createdAt, digest)
        }
    }

    private fun noteCreatedAt(dTag: String, createdAt: Long) {
        val previous = maxSeenCreatedAt[dTag]
        if (previous == null || createdAt > previous) maxSeenCreatedAt[dTag] = createdAt
    }

    /** NIP-01: neueres `created_at` gewinnt, bei Gleichstand die kleinere ID. */
    private fun isNewer(createdAt: Long, eventId: String, current: RelayView): Boolean =
        createdAt > current.createdAt || (createdAt == current.createdAt && eventId <= current.eventId)

    private fun dTagFor(bucket: String): String = dTags.getOrPut(bucket) { keys.dTag(bucket) }

    private fun newSubscriptionId(): String = "dp-" + Hex.encode(SecureRandomBytes.next(6))

    private fun sanitize(text: String): String =
        text.filter { !it.isISOControl() }.take(MAX_RELAY_TEXT)

    // ---------------------------------------------------------------------------------
    // Typen
    // ---------------------------------------------------------------------------------

    private sealed interface Input {
        class Connecting(val session: Session, val generation: Int) : Input
        class Opened(val session: Session, val generation: Int, val socket: RelaySocket) : Input
        class Message(val session: Session, val generation: Int, val text: String) : Input
        class Closed(val session: Session, val generation: Int, val error: String?) : Input
        data object LocalChanged : Input
        data object Tick : Input
        data object ReconnectNow : Input
    }

    private fun Input.Connecting.isCurrent() = generation == session.generation.get()
    private fun Input.Opened.isCurrent() = generation == session.generation.get()
    private fun Input.Message.isCurrent() = generation == session.generation.get()
    private fun Input.Closed.isCurrent() = generation == session.generation.get()

    private sealed interface Decoded {
        data object Invalid : Decoded
        /** Signatur gültig, Inhalt aber nicht lesbar (fremder Schlüssel oder fehlerhaft). */
        data class Opaque(val dTag: String, val reason: BucketCodec.RejectReason) : Decoded
        data class Valid(
            val dTag: String,
            val bucket: String,
            val entries: Map<String, Entry>,
            val dropped: Int,
            val digest: String,
        ) : Decoded
    }

    private data class RelayView(val eventId: String, val createdAt: Long, val digest: String?)
    private data class Inflight(val eventId: String, val digest: String, val createdAt: Long, val sentAt: Long)
    private data class Failure(val digest: String, val count: Int)

    private class Session(val url: String) {
        // Werden auch ausserhalb der Ereignisschleife gelesen/geschrieben:
        val generation = AtomicInteger(0)
        val wakeup = Channel<Unit>(Channel.CONFLATED)
        @Volatile var liveSince = 0L

        // Nur innerhalb der Ereignisschleife:
        var socket: RelaySocket? = null
        var connected = false
        var synced = false
        var subscriptionId: String? = null
        var pageSubscriptionId: String? = null
        var initialEventCount = 0
        var pageEventCount = 0
        var pageNewEvents = 0
        var pagesFetched = 0
        var pageSkippedSecond = false
        var oldestCreatedAt = Long.MAX_VALUE
        val seenIds = HashSet<String>()
        val view = HashMap<String, RelayView>()
        val inflight = HashMap<String, Inflight>()
        val failures = HashMap<String, Failure>()
        var cooldownUntil = 0L
        var state = RelayConnectionState.CONNECTING
        var lastError: String? = null
        var lastNotice: String? = null
        var lastSyncAt: Long? = null
        var received = 0
        var accepted = 0
        var rejected = 0
        var undecryptable = 0
        var invalid = 0
        var dropped = 0

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
            droppedEntries = dropped,
        )
    }

    companion object {
        private const val TAG = "SyncEngine"
        const val KIND = NostrEvent.KIND_APP_DATA
        private const val MAX_FUTURE_SECONDS = Limits.MAX_FUTURE_MILLIS / 1000
        private const val MAX_RELAY_TEXT = 160
        private const val MAX_BACKOFF_ATTEMPT = 30
        private const val MIN_TICK_MILLIS = 10L

        /** Ablehnungen, bei denen ein erneuter Versuch nichts bringt. */
        private val PERMANENT_REJECTIONS = setOf("blocked", "restricted", "pow", "invalid", "auth-required")
    }
}
