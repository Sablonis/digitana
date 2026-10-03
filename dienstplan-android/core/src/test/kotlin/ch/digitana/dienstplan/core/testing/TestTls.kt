package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.sync.SecureHttp
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress

/**
 * Eigene Test-CA für lokale TLS-Relays. Der Client vertraut ausschliesslich dieser CA –
 * ansonsten ist er identisch mit dem Produktions-Client (TLS 1.2/1.3, kein Klartext).
 */
object TestTls {
    val rootCa: HeldCertificate = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("Dienstplan Test-CA")
        .build()

    val localhostCertificate: HeldCertificate = HeldCertificate.Builder()
        .signedBy(rootCa)
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .build()

    val serverCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(localhostCertificate, rootCa.certificate)
        .build()

    val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()

    /**
     * Lokale Testserver lauschen nur auf 127.0.0.1. Auf Rechnern mit IPv6 löst „localhost“
     * zusätzlich zu ::1 auf; OkHttp versucht dann zuerst ::1 und meldet dessen Fehler.
     * Die Test-Clients lösen „localhost“ deshalb genau auf 127.0.0.1 auf.
     */
    val loopback: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

    val loopbackDns = Dns { host -> if (host == "localhost") listOf(loopback) else Dns.SYSTEM.lookup(host) }

    fun startOnLoopback(server: MockWebServer) = server.start(loopback, 0)

    /** Produktions-Client, nur mit Test-DNS für localhost (TLS-Einstellungen unverändert). */
    fun productionClient(): OkHttpClient = SecureHttp.newClientBuilder().dns(loopbackDns).build()

    /** Wie der Produktions-Client, vertraut aber ausschliesslich der Test-CA. */
    fun client(): OkHttpClient = SecureHttp.newClientBuilder()
        .dns(loopbackDns)
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()
}
