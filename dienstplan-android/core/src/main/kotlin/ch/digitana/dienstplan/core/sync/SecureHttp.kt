package ch.digitana.dienstplan.core.sync

import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * OkHttp-Konfiguration für die Relay-Verbindungen.
 *
 * - Nur TLS 1.3 und 1.2 (ConnectionSpec ohne CLEARTEXT, also kein ws://).
 * - Zertifikats- und Hostnamenprüfung bleiben bei den Standards von OkHttp bzw. der
 *   Plattform (auf Android: System-CAs laut Network Security Config, keine Nutzer-CAs).
 * - Keine Weiterleitungen, damit ein Relay die Verbindung nicht umlenken kann.
 * - Der callTimeout gilt nur bis zum WebSocket-Upgrade; danach hält pingInterval die
 *   Verbindung wach und erkennt Abbrüche.
 */
object SecureHttp {

    val TLS_SPEC: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()

    fun newClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionSpecs(listOf(TLS_SPEC))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)

    fun newClient(): OkHttpClient = newClientBuilder().build()
}

object RelayUrls {
    /** Standard-Relays. Mindestens drei, damit ein Ausfall den Sync nicht stoppt. */
    val DEFAULT: List<String> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
    )

    /** Nur wss:// mit gültigem Host; ws:// und alles andere wird abgelehnt. */
    fun isValid(url: String): Boolean {
        if (!url.startsWith("wss://")) return false
        val http = "https://" + url.removePrefix("wss://")
        val parsed = http.toHttpUrlOrNull() ?: return false
        return parsed.host.isNotEmpty() && parsed.username.isEmpty() && parsed.password.isEmpty()
    }
}
