package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.testing.TestTls
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.UnknownServiceException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TLS-Negativtests ohne Internet: Die Server laufen lokal, die Zertifikate werden im Test
 * erzeugt. Geprüft wird die Konfiguration des Produktions-Clients. Die gleichen Fälle gegen
 * badssl.com stehen in [BadSslNetworkTest] (Tag "network").
 */
class TlsLocalTest {

    private fun server(certificate: HeldCertificate, chain: HeldCertificate? = TestTls.rootCa): MockWebServer {
        val handshake = HandshakeCertificates.Builder().apply {
            if (chain != null) heldCertificate(certificate, chain.certificate) else heldCertificate(certificate)
        }.build()
        return MockWebServer().apply {
            useHttps(handshake.sslSocketFactory())
            enqueue(MockResponse.Builder().body("ok").build())
            TestTls.startOnLoopback(this)
        }
    }

    private fun trustingTestCa(): OkHttpClient = TestTls.client()

    /** Produktions-Client (nur System-/JDK-CAs); lediglich „localhost“ zeigt fest auf 127.0.0.1. */
    private fun production(): OkHttpClient = TestTls.productionClient()

    private fun get(client: OkHttpClient, server: MockWebServer, scheme: String = "https") =
        client.newCall(Request.Builder().url("$scheme://localhost:${server.port}/").build()).execute().use { it.body.string() }

    @Test
    fun `selbstsigniertes Zertifikat wird vom Produktions-Client abgelehnt`() {
        val selfSigned = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        server(selfSigned, chain = null).use { server ->
            assertThrows<SSLHandshakeException> { get(production(), server) }
        }
    }

    @Test
    fun `Zertifikat einer unbekannten CA wird abgelehnt`() {
        // Gültig signiert – aber von einer CA, der der Produktions-Client nicht vertraut.
        server(TestTls.localhostCertificate).use { server ->
            assertThrows<SSLHandshakeException> { get(production(), server) }
        }
    }

    @Test
    fun `falscher Hostname wird abgelehnt`() {
        val wrongHost = HeldCertificate.Builder()
            .signedBy(TestTls.rootCa)
            .commonName("relay.example.org")
            .addSubjectAlternativeName("relay.example.org")
            .build()
        server(wrongHost).use { server ->
            assertThrows<SSLPeerUnverifiedException> { get(trustingTestCa(), server) }
        }
    }

    @Test
    fun `abgelaufenes Zertifikat wird abgelehnt`() {
        val day = TimeUnit.DAYS.toMillis(1)
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder()
            .signedBy(TestTls.rootCa)
            .addSubjectAlternativeName("localhost")
            .validityInterval(now - 3 * day, now - day)
            .build()
        server(expired).use { server ->
            assertThrows<SSLHandshakeException> { get(trustingTestCa(), server) }
        }
    }

    @Test
    fun `Positivkontrolle - gueltiges Zertifikat einer vertrauten CA`() {
        server(TestTls.localhostCertificate).use { server ->
            assertEquals("ok", get(trustingTestCa(), server))
            val handshake = server.takeRequest().handshake
            assertTrue(handshake!!.tlsVersion in setOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2))
        }
    }

    @Test
    fun `nur TLS 1_2 und 1_3, kein Klartext`() {
        assertEquals(listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), SecureHttp.TLS_SPEC.tlsVersions)
        val client = SecureHttp.newClient()
        assertEquals(listOf(SecureHttp.TLS_SPEC), client.connectionSpecs)
        assertFalse(client.followRedirects)
        MockWebServer().use { plain ->
            plain.enqueue(MockResponse.Builder().body("klartext").build())
            TestTls.startOnLoopback(plain)
            assertThrows<UnknownServiceException> { get(production(), plain, scheme = "http") }
        }
    }

    @Test
    fun `nur wss-Relays`() {
        assertTrue(RelayUrls.isValid("wss://relay.damus.io"))
        assertTrue(RelayUrls.DEFAULT.all { RelayUrls.isValid(it) })
        assertTrue(RelayUrls.DEFAULT.size >= 3)
        for (url in listOf("ws://relay.damus.io", "https://relay.damus.io", "wss://", "wss://user:pw@relay.damus.io", "relay.damus.io", "wss:// relay")) {
            assertFalse(RelayUrls.isValid(url), url)
        }
        assertThrows<IllegalArgumentException> {
            OkHttpRelayTransport(SecureHttp.newClient()).connect("ws://127.0.0.1:1", object : RelayTransport.Listener {
                override fun onOpen(socket: RelaySocket) = Unit
                override fun onMessage(text: String) = Unit
                override fun onClosed(error: String?) = Unit
            })
        }
    }

    @Test
    fun `Diagnose bevorzugt TLS-Fehler, auch wenn ein frueherer Adressversuch scheiterte`() {
        // So meldet OkHttp einen Dual-Stack-Fehlschlag: erst ::1 abgelehnt, dann TLS-Fehler auf IPv4.
        val connect = java.net.ConnectException("Failed to connect to localhost/[::1]")
        connect.addSuppressed(SSLHandshakeException("PKIX path building failed"))
        assertTrue(OkHttpRelayTransport.describeFailure(connect, null).startsWith("TLS-Handshake"))
        val peer = java.io.IOException("x", SSLPeerUnverifiedException("Hostname localhost not verified"))
        assertTrue(OkHttpRelayTransport.describeFailure(peer, null).contains("Hostnamen"))
        assertEquals("Verbindung abgelehnt", OkHttpRelayTransport.describeFailure(java.net.ConnectException("refused"), null))
    }

    @Test
    fun `WebSocket zu einem Relay mit ungueltigem Zertifikat meldet einen TLS-Fehler`() {
        val selfSigned = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server(selfSigned, chain = null).use { server ->
            val result = CompletableFuture<String?>()
            val socket = OkHttpRelayTransport(production()).connect(
                "wss://localhost:${server.port}/",
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
            val error = result.get(15, TimeUnit.SECONDS)
            socket.close()
            assertTrue(error != null && error.startsWith("TLS"), "Fehler: $error")
        }
    }
}
