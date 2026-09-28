package ch.digitana.dienstplan.core.sync

import okhttp3.Request
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TLS-Negativtests gegen badssl.com mit dem Produktions-Client.
 * Braucht eine direkte Internetverbindung: `./gradlew :core:networkTest`.
 * (Hinter einem TLS-aufbrechenden Proxy sind die Ergebnisse nicht aussagekräftig.)
 */
@Tag("network")
class BadSslNetworkTest {

    private val client = SecureHttp.newClient()

    private fun get(url: String) =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `Positivkontrolle - gueltiges Zertifikat`() {
        assertEquals(200, get("https://badssl.com/"))
    }

    @Test
    fun `abgelaufenes Zertifikat`() {
        assertThrows<SSLHandshakeException> { get("https://expired.badssl.com/") }
    }

    @Test
    fun `falscher Hostname`() {
        assertThrows<SSLPeerUnverifiedException> { get("https://wrong.host.badssl.com/") }
    }

    @Test
    fun `selbstsigniertes Zertifikat`() {
        assertThrows<SSLHandshakeException> { get("https://self-signed.badssl.com/") }
    }

    @Test
    fun `unbekannte Wurzel-CA`() {
        assertThrows<SSLHandshakeException> { get("https://untrusted-root.badssl.com/") }
    }

    @Test
    fun `TLS 1_0 und 1_1 werden nicht ausgehandelt`() {
        assertThrows<IOException> { get("https://tls-v1-0.badssl.com:1010/") }
        assertThrows<IOException> { get("https://tls-v1-1.badssl.com:1011/") }
        assertEquals(200, get("https://tls-v1-2.badssl.com:1012/"))
    }

    @Test
    fun `WebSocket-Transport meldet TLS-Fehler statt zu verbinden`() {
        for (host in listOf("expired.badssl.com", "wrong.host.badssl.com", "self-signed.badssl.com")) {
            val result = CompletableFuture<String?>()
            val socket = OkHttpRelayTransport(client).connect(
                "wss://$host/",
                object : RelayTransport.Listener {
                    override fun onOpen(socket: RelaySocket) {
                        result.complete("verbunden")
                    }
                    override fun onMessage(text: String) = Unit
                    override fun onClosed(error: String?) {
                        result.complete(error)
                    }
                },
            )
            val error = result.get(30, TimeUnit.SECONDS)
            socket.close()
            assertTrue(error != null && error.startsWith("TLS"), "$host: $error")
        }
    }

    @Test
    fun `alle Fehler sind SSL-Fehler (kein stilles Durchlassen)`() {
        for (url in listOf("https://expired.badssl.com/", "https://wrong.host.badssl.com/", "https://self-signed.badssl.com/")) {
            assertThrows<SSLException>(url) { get(url) }
        }
    }
}
