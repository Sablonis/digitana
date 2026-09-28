package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.nostr.NostrEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
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
 * Minimales Nostr-Relay (NIP-01) über TLS auf localhost – deterministischer Ersatz für
 * echte Relays in den Integrationstests. Unterstützt REQ (kinds, authors, #d, until, limit),
 * CLOSE, EVENT mit Signaturprüfung und Ersetzungsregel für adressierbare Events
 * (neueres created_at gewinnt, bei Gleichstand die kleinere ID).
 */
class FakeRelay(private val label: String) : Closeable {

    private val server = MockWebServer()
    private val lock = Any()
    private val stored = LinkedHashMap<String, NostrEvent>()
    private val connections = CopyOnWriteArrayList<Connection>()

    /** Liefert eine Ablehnungsmeldung (z. B. "blocked: …") oder null. */
    @Volatile var rejectHook: ((NostrEvent) -> String?)? = null

    /** Verbindungen mit HTTP 503 abweisen (Relay „offline“). */
    @Volatile var refuseConnections = false

    /** Höchstzahl Events pro REQ, unabhängig vom `limit` des Clients (wie echte Relays). */
    @Volatile var maxLimit = 500

    val accepted = AtomicInteger()
    val rejected = AtomicInteger()
    val connectionsOpened = AtomicInteger()

    val url: String get() = "wss://${server.hostName}:${server.port}/"

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
        server.start()
        return this
    }

    override fun close() {
        dropConnections()
        server.close()
    }

    fun storedEvents(): List<NostrEvent> = synchronized(lock) { stored.values.toList() }

    /** Simuliert Datenverlust auf dem Relay. */
    fun wipe() = synchronized(lock) { stored.clear() }

    /** Trennt alle Clients (serverseitige WebSockets kennen kein cancel(), daher Close-Frame 1001). */
    fun dropConnections() {
        connections.forEach { runCatching { it.socket.close(1001, "going away") } }
        connections.clear()
    }

    /** Legt ein Event direkt ab (z. B. manipulierte Daten für Tests). */
    fun inject(event: NostrEvent) = synchronized(lock) { stored[address(event)] = event }

    private inner class Connection(val socket: WebSocket) {
        val subscriptions = ConcurrentHashMap<String, JsonObject>()
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
                conn.subscriptions[subscriptionId] = filter
                val limit = minOf((filter["limit"] as? JsonPrimitive)?.int ?: maxLimit, maxLimit)
                val matches = synchronized(lock) { stored.values.filter { matches(filter, it) } }
                    .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
                    .take(limit)
                for (event in matches) conn.socket.send(eventMessage(subscriptionId, event))
                conn.socket.send(buildJsonArray { add("EOSE"); add(subscriptionId) }.toString())
            }
            "CLOSE" -> conn.subscriptions.remove(message[1].jsonPrimitive.content)
            "EVENT" -> {
                val event = Nip01.parseEvent(message[1])
                if (event == null || !Nip01.verify(event)) {
                    ok(conn, event?.id ?: "", false, "invalid: bad event")
                    return
                }
                rejectHook?.invoke(event)?.let {
                    rejected.incrementAndGet()
                    ok(conn, event.id, false, it)
                    return
                }
                val outcome = synchronized(lock) {
                    val key = address(event)
                    val existing = stored[key]
                    when {
                        existing != null && existing.id == event.id -> "duplicate"
                        existing != null && (existing.createdAt > event.createdAt ||
                            (existing.createdAt == event.createdAt && existing.id < event.id)) -> "replaced"
                        else -> {
                            stored[key] = event
                            "stored"
                        }
                    }
                }
                when (outcome) {
                    "duplicate" -> ok(conn, event.id, true, "duplicate: already have this event")
                    "replaced" -> {
                        rejected.incrementAndGet()
                        ok(conn, event.id, false, "replaced: have newer event")
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

    private fun broadcast(event: NostrEvent) {
        for (conn in connections) {
            for ((subscriptionId, filter) in conn.subscriptions) {
                if (filter["until"] != null) continue // Paging-Abfragen sind nicht live
                if (matches(filter, event)) conn.socket.send(eventMessage(subscriptionId, event))
            }
        }
    }

    private fun matches(filter: JsonObject, event: NostrEvent): Boolean {
        (filter["kinds"] as? JsonArray)?.let { kinds -> if (kinds.none { it.jsonPrimitive.int == event.kind }) return false }
        (filter["authors"] as? JsonArray)?.let { authors -> if (authors.none { it.jsonPrimitive.content == event.pubkey }) return false }
        (filter["#d"] as? JsonArray)?.let { tags ->
            val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
            if (tags.none { it.jsonPrimitive.content == d }) return false
        }
        (filter["until"] as? JsonPrimitive)?.let { if (event.createdAt > it.long) return false }
        return true
    }

    private fun address(event: NostrEvent): String {
        val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
        return "${event.kind}:${event.pubkey}:$d"
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
