package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.nostr.NostrEvent
import ch.digitana.dienstplan.core.util.Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimales Nostr-Relay (NIP-01) über TLS auf localhost – deterministischer Ersatz für echte
 * Relays in den Integrationstests.
 *
 * - REQ mit kinds, authors, ids, Tag-Filtern (`#d`, `#h`, `#p`, …), since, until, limit; CLOSE.
 * - EVENT mit Signaturprüfung. Normale Events werden alle gespeichert, ersetzbare und
 *   adressierbare nach NIP-01 ersetzt (neueres created_at gewinnt, bei Gleichstand die
 *   kleinere ID), flüchtige (20000–29999) nur weitergereicht.
 * - Löschanfragen (Kind 5, NIP-09) für eigene Events per `e`-Tag.
 * - Optional Anmeldung (NIP-42) für bestimmte Kinds; Gift Wraps (1059) gehen dann nur an den
 *   angemeldeten Empfänger.
 */
class FakeRelay(private val label: String) : Closeable {

    private val server = MockWebServer()
    private val lock = Any()
    private val stored = LinkedHashMap<String, NostrEvent>()
    private val deleted = HashSet<String>()
    private val connections = CopyOnWriteArrayList<Connection>()

    /** Liefert eine Ablehnungsmeldung (z. B. "blocked: …") oder null. */
    @Volatile var rejectHook: ((NostrEvent) -> String?)? = null

    /** Verbindungen mit HTTP 503 abweisen (Relay „offline“). */
    @Volatile var refuseConnections = false

    /** Höchstzahl Events pro REQ, unabhängig vom `limit` des Clients (wie echte Relays). */
    @Volatile var maxLimit = 500

    /** Abfragen dieser Kinds nur nach Anmeldung (NIP-42). */
    @Volatile var authRequiredKinds: Set<Int> = emptySet()

    val accepted = AtomicInteger()
    val rejected = AtomicInteger()
    val connectionsOpened = AtomicInteger()
    val authentications = AtomicInteger()

    val url: String get() = "wss://localhost:${server.port}/"

    fun start(): FakeRelay {
        server.useHttps(TestTls.serverCertificates.sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (refuseConnections) {
                    MockResponse.Builder().code(503).body("offline").build()
                } else {
                    MockResponse.Builder().webSocketUpgrade(Listener()).build()
                }
        }
        TestTls.startOnLoopback(server)
        return this
    }

    override fun close() {
        dropConnections()
        server.close()
    }

    fun storedEvents(): List<NostrEvent> = synchronized(lock) { stored.values.toList() }

    fun storedEvents(kind: Int): List<NostrEvent> = storedEvents().filter { it.kind == kind }

    /** Simuliert Datenverlust auf dem Relay. */
    fun wipe() = synchronized(lock) { stored.clear() }

    /** Trennt alle Clients (serverseitige WebSockets kennen kein cancel(), daher Close-Frame 1001). */
    fun dropConnections() {
        connections.forEach { runCatching { it.socket.close(1001, "going away") } }
        connections.clear()
    }

    /** Legt ein Event direkt ab (z. B. manipulierte Daten für Tests). */
    fun inject(event: NostrEvent) = synchronized(lock) { stored[address(event)] = event }

    /** Wie ein EVENT eines anderen Clients: speichern und an passende Abos verteilen. */
    fun publish(event: NostrEvent) {
        val outcome = synchronized(lock) { store(event) }
        if (outcome == "stored") broadcast(event)
    }

    private inner class Connection(val socket: WebSocket) {
        val subscriptions = ConcurrentHashMap<String, JsonObject>()
        val challenge: String = Hex.encode(SecureRandomBytes.next(16))
        @Volatile var authedPubkey: String? = null
    }

    private inner class Listener : WebSocketListener() {
        private var connection: Connection? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connectionsOpened.incrementAndGet()
            connection = Connection(webSocket).also { connections += it }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val conn = connection ?: return
            try {
                handle(conn, text)
            } catch (e: Exception) {
                webSocket.send("""["NOTICE","error: ${e.javaClass.simpleName}"]""")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connection?.let { connections -= it }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connection?.let { connections -= it }
        }
    }

    private fun handle(conn: Connection, text: String) {
        val message = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
        when (message[0].jsonPrimitive.content) {
            "REQ" -> {
                val subscriptionId = message[1].jsonPrimitive.content
                val filter = message[2] as JsonObject
                if (needsAuth(conn, filter)) {
                    conn.socket.send(buildJsonArray { add("AUTH"); add(conn.challenge) }.toString())
                    conn.socket.send(buildJsonArray { add("CLOSED"); add(subscriptionId); add("auth-required: please authenticate") }.toString())
                    return
                }
                conn.subscriptions[subscriptionId] = filter
                val limit = minOf((filter["limit"] as? JsonPrimitive)?.int ?: maxLimit, maxLimit)
                val matches = synchronized(lock) { stored.values.filter { matches(filter, it) && visibleTo(conn, it) } }
                    .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
                    .take(limit)
                for (event in matches) conn.socket.send(eventMessage(subscriptionId, event))
                conn.socket.send(buildJsonArray { add("EOSE"); add(subscriptionId) }.toString())
            }
            "CLOSE" -> conn.subscriptions.remove(message[1].jsonPrimitive.content)
            "AUTH" -> {
                val event = Nip01.parseEvent(message[1])
                val ok = event != null && TestEvents.verify(event) && event.kind == 22242 &&
                    event.tags.any { it.size >= 2 && it[0] == "challenge" && it[1] == conn.challenge } &&
                    event.tags.any { it.size >= 2 && it[0] == "relay" }
                if (ok) {
                    conn.authedPubkey = event.pubkey
                    authentications.incrementAndGet()
                }
                ok(conn, event?.id ?: "", ok, if (ok) "" else "invalid: bad auth")
            }
            "EVENT" -> {
                val event = Nip01.parseEvent(message[1])
                if (event == null || !TestEvents.verify(event)) {
                    ok(conn, event?.id ?: "", false, "invalid: bad event")
                    return
                }
                rejectHook?.invoke(event)?.let {
                    rejected.incrementAndGet()
                    ok(conn, event.id, false, it)
                    return
                }
                val outcome = synchronized(lock) { store(event) }
                when (outcome) {
                    "duplicate" -> ok(conn, event.id, true, "duplicate: already have this event")
                    "replaced" -> {
                        rejected.incrementAndGet()
                        ok(conn, event.id, false, "replaced: have newer event")
                    }
                    "deleted" -> {
                        rejected.incrementAndGet()
                        ok(conn, event.id, false, "blocked: event was deleted")
                    }
                    else -> {
                        accepted.incrementAndGet()
                        ok(conn, event.id, true, "")
                        broadcast(event)
                    }
                }
            }
        }
    }

    /** Unter der Sperre: speichern nach NIP-01/NIP-09. */
    private fun store(event: NostrEvent): String {
        if (event.id in deleted) return "deleted"
        if (event.kind in 20000..29999) return "stored" // flüchtig: nur weiterreichen
        val key = address(event)
        val existing = stored[key]
        return when {
            existing != null && existing.id == event.id -> "duplicate"
            existing != null && (existing.createdAt > event.createdAt ||
                (existing.createdAt == event.createdAt && existing.id < event.id)) -> "replaced"
            else -> {
                stored[key] = event
                if (event.kind == 5) applyDeletion(event)
                "stored"
            }
        }
    }

    private fun applyDeletion(deletion: NostrEvent) {
        for (tag in deletion.tags) {
            if (tag.size < 2 || tag[0] != "e") continue
            val target = stored.entries.firstOrNull { it.value.id == tag[1] } ?: continue
            if (target.value.pubkey != deletion.pubkey) continue
            stored.remove(target.key)
            deleted += tag[1]
        }
    }

    private fun needsAuth(conn: Connection, filter: JsonObject): Boolean {
        if (authRequiredKinds.isEmpty() || conn.authedPubkey != null) return false
        val kinds = (filter["kinds"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: return true
        return kinds.any { it in authRequiredKinds }
    }

    /** Mit Anmeldung sehen nur Empfänger ihre Gift Wraps. */
    private fun visibleTo(conn: Connection, event: NostrEvent): Boolean {
        if (event.kind != 1059 || 1059 !in authRequiredKinds) return true
        val me = conn.authedPubkey ?: return false
        return event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == me }
    }

    private fun broadcast(event: NostrEvent) {
        for (conn in connections) {
            for ((subscriptionId, filter) in conn.subscriptions) {
                if (filter["until"] != null) continue // Paging-Abfragen sind nicht live
                if (matches(filter, event) && visibleTo(conn, event)) conn.socket.send(eventMessage(subscriptionId, event))
            }
        }
    }

    private fun matches(filter: JsonObject, event: NostrEvent): Boolean {
        for ((name, value) in filter) {
            when {
                name == "kinds" -> if ((value as JsonArray).none { it.jsonPrimitive.int == event.kind }) return false
                name == "authors" -> if ((value as JsonArray).none { it.jsonPrimitive.content == event.pubkey }) return false
                name == "ids" -> if ((value as JsonArray).none { it.jsonPrimitive.content == event.id }) return false
                name == "since" -> if (event.createdAt < (value as JsonPrimitive).long) return false
                name == "until" -> if (event.createdAt > (value as JsonPrimitive).long) return false
                name.startsWith("#") && name.length == 2 -> {
                    val tagName = name.substring(1)
                    val wanted = (value as JsonArray).map { it.jsonPrimitive.content }.toSet()
                    if (event.tags.none { it.size >= 2 && it[0] == tagName && it[1] in wanted }) return false
                }
            }
        }
        return true
    }

    private fun address(event: NostrEvent): String {
        val kind = event.kind
        return when {
            kind == 0 || kind == 3 || kind in 10000..19999 -> "$kind:${event.pubkey}"
            kind in 30000..39999 -> {
                val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
                "$kind:${event.pubkey}:$d"
            }
            else -> event.id
        }
    }

    private fun eventMessage(subscriptionId: String, event: NostrEvent) =
        buildJsonArray {
            add("EVENT")
            add(subscriptionId)
            add(event.toJson())
        }.toString()

    private fun ok(conn: Connection, id: String, accepted: Boolean, message: String) {
        conn.socket.send(
            buildJsonArray {
                add("OK")
                add(id)
                add(accepted)
                add(message)
            }.toString(),
        )
    }

    override fun toString() = "FakeRelay($label)"
}
