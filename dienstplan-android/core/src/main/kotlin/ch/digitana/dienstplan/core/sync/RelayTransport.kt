package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.util.JsonGuards
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Eine offene Verbindung zu einem Relay. */
interface RelaySocket {
    fun send(text: String): Boolean
    fun close()
}

/** Baut Verbindungen auf; austauschbar für Tests. */
interface RelayTransport {
    interface Listener {
        fun onOpen(socket: RelaySocket)
        fun onMessage(text: String)
        /** Genau einmal pro Verbindung; [error] ist `null` bei regulärem Schliessen. */
        fun onClosed(error: String?)
    }

    fun connect(url: String, listener: Listener): RelaySocket
}

/** WebSocket-Transport über OkHttp. */
class OkHttpRelayTransport(private val client: OkHttpClient) : RelayTransport {

    override fun connect(url: String, listener: RelayTransport.Listener): RelaySocket {
        require(RelayUrls.isValid(url)) { "Nur wss://-Relays sind erlaubt" }
        val request = Request.Builder().url(url).build()
        val finished = AtomicBoolean(false)
        fun finish(error: String?) {
            if (finished.compareAndSet(false, true)) listener.onClosed(error)
        }

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen(OkHttpRelaySocket(webSocket))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JsonGuards.utf8LengthExceeds(text, Limits.MAX_MESSAGE_BYTES)) {
                    webSocket.close(CLOSE_TOO_BIG, "message too big")
                    finish("Nachricht grösser als 2 MB – Verbindung getrennt")
                    return
                }
                listener.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Nostr verwendet ausschliesslich Textnachrichten.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(CLOSE_NORMAL, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(if (code == CLOSE_NORMAL) null else "Vom Relay geschlossen (Code $code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(describeFailure(t, response))
            }
        })
        return OkHttpRelaySocket(webSocket)
    }

    private class OkHttpRelaySocket(private val webSocket: WebSocket) : RelaySocket {
        override fun send(text: String): Boolean = webSocket.send(text)
        override fun close() {
            if (!webSocket.close(CLOSE_NORMAL, null)) webSocket.cancel()
        }
    }

    companion object {
        private const val CLOSE_NORMAL = 1000
        private const val CLOSE_TOO_BIG = 1009

        /** Verständlicher Fehlertext für die Diagnose (ohne Geheimnisse). */
        fun describeFailure(t: Throwable, response: Response?): String {
            response?.let { if (it.code != 101) return "HTTP ${it.code} statt WebSocket-Upgrade" }
            // OkHttp probiert mehrere Adressen (z. B. IPv6, dann IPv4) und wirft den ersten Fehler;
            // TLS-Fehler späterer Versuche hängen als „suppressed“ daran. Sie sind aussagekräftiger.
            val related = relatedThrowables(t)
            if (related.any { it is SSLPeerUnverifiedException }) return "TLS: Zertifikat passt nicht zum Hostnamen"
            if (related.any { it is SSLHandshakeException }) {
                return "TLS-Handshake fehlgeschlagen (Zertifikat ungültig oder abgelaufen)"
            }
            return when (t) {
                is SSLPeerUnverifiedException -> "TLS: Zertifikat passt nicht zum Hostnamen"
                is SSLHandshakeException -> "TLS-Handshake fehlgeschlagen (Zertifikat ungültig oder abgelaufen)"
                is UnknownHostException -> "Host nicht gefunden (keine Verbindung?)"
                is SocketTimeoutException -> "Zeitüberschreitung"
                is ConnectException -> "Verbindung abgelehnt"
                is NoRouteToHostException -> "Kein Netz"
                is ProtocolException -> "Protokollfehler"
                is IOException -> if (t.message?.contains("Canceled", ignoreCase = true) == true) {
                    "Verbindung abgebrochen"
                } else {
                    "Netzwerkfehler (${t.javaClass.simpleName})"
                }
                else -> "Fehler (${t.javaClass.simpleName})"
            }
        }

        /** Die Ausnahme selbst, ihre Ursachen und unterdrückten Ausnahmen (begrenzt, ohne Zyklen). */
        private fun relatedThrowables(root: Throwable): List<Throwable> {
            val result = ArrayList<Throwable>()
            val queue = ArrayDeque<Throwable>().apply { add(root) }
            while (queue.isNotEmpty() && result.size < 32) {
                val current = queue.removeFirst()
                if (result.any { it === current }) continue
                result += current
                current.cause?.let { queue.addLast(it) }
                current.suppressed.forEach { queue.addLast(it) }
            }
            return result
        }
    }
}
