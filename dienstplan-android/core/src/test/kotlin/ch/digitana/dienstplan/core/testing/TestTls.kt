package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.sync.SecureHttp
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

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

    fun client(): OkHttpClient = SecureHttp.newClientBuilder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()
}
