package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.nostr.NostrEvent
import ch.digitana.dienstplan.core.sync.RelaySocket
import ch.digitana.dienstplan.core.sync.RelayTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Relay-Attrappe im Speicher für deterministische Tests der Sync-Regeln.
 * Antworten laufen über einen eigenen Thread, wie bei einem echten Netzwerk-Client.
 */
class ScriptedTransport(
    /** Entscheidet über die OK-Antwort; `attempt` zählt die Events desselben Buckets ab 1. */
    var onEvent: (NostrEvent, attempt: Int) -> Reply = { _, _ -> Reply.Accept },
) : RelayTransport {

    sealed interface Reply {
        data object Accept : Reply
        data class Reject(val message: String) : Reply
        data object Silent : Reply
    }

    /** Events, die das Relay beim Abonnieren als „gespeichert“ ausliefert. */
    val storedOnConnect = CopyOnWriteArrayList<NostrEvent>()
    val published = CopyOnWriteArrayList<NostrEvent>()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var listener: RelayTransport.Listener? = null
    @Volatile private var mainSubscription: String? = null

    override fun connect(url: String, listener: RelayTransport.Listener): RelaySocket {
        this.listener = listener
        mainSubscription = null
        val socket = object : RelaySocket {
            override fun send(text: String): Boolean {
                executor.execute { handle(text) }
                return true
            }

            override fun close() {
                executor.execute { this@ScriptedTransport.listener?.onClosed(null) }
            }
        }
        executor.execute { listener.onOpen(socket) }
        return socket
    }

    /** Schickt ein Event, als hätte ein anderes Gerät es live veröffentlicht. */
    fun pushLive(event: NostrEvent) {
        executor.execute {
            val sub = mainSubscription ?: return@execute
            listener?.onMessage(eventMessage(sub, event))
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun handle(text: String) {
        val message = Json.parseToJsonElement(text).jsonArray
        when (message[0].jsonPrimitive.content) {
            "REQ" -> {
                val sub = message[1].jsonPrimitive.content
                if (mainSubscription == null) mainSubscription = sub
                val until = (message[2] as JsonObject)["until"]
                if (until == null) storedOnConnect.forEach { listener?.onMessage(eventMessage(sub, it)) }
                listener?.onMessage(buildJsonArray { add("EOSE"); add(sub) }.toString())
            }
            "EVENT" -> {
                val event = Nip01.parseEvent(message[1])!!
                check(Nip01.verify(event)) { "Engine hat ein ungültiges Event gesendet" }
                published += event
                val attempt = published.count { it.tags == event.tags }
                val ok = when (val reply = onEvent(event, attempt)) {
                    Reply.Accept -> okMessage(event.id, true, "")
                    is Reply.Reject -> okMessage(event.id, false, reply.message)
                    Reply.Silent -> null
                }
                ok?.let { listener?.onMessage(it) }
            }
        }
    }

    private fun eventMessage(sub: String, event: NostrEvent) =
        buildJsonArray {
            add("EVENT")
            add(sub)
            add(event.toJson())
        }.toString()

    private fun okMessage(id: String, accepted: Boolean, message: String) =
        buildJsonArray {
            add("OK")
            add(id)
            add(accepted)
            add(message)
        }.toString()
}

/** Manuell gestellte Uhr für Tests der Sekundenregeln. */
class ManualClock(@Volatile var millis: Long) : ch.digitana.dienstplan.core.util.Clock {
    override fun nowMillis(): Long = millis
    fun advance(ms: Long) {
        millis += ms
    }
}
