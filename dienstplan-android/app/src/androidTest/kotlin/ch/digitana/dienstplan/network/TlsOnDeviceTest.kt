package ch.digitana.dienstplan.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.digitana.dienstplan.core.sync.SecureHttp
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * TLS-Negativtests auf dem Gerät, also mit dem Android-Trust-Store und der Network Security
 * Config der App. Braucht Internet: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class TlsOnDeviceTest {

    private val client = SecureHttp.newClient()

    private fun get(url: String): Int =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun gueltigesZertifikat() {
        assertEquals(200, get("https://badssl.com/"))
    }

    @Test
    fun abgelaufenesZertifikat() {
        assertThrows(SSLHandshakeException::class.java) { get("https://expired.badssl.com/") }
    }

    @Test
    fun falscherHostname() {
        assertThrows(SSLPeerUnverifiedException::class.java) { get("https://wrong.host.badssl.com/") }
    }

    @Test
    fun selbstsigniertesZertifikat() {
        assertThrows(SSLHandshakeException::class.java) { get("https://self-signed.badssl.com/") }
    }

    @Test
    fun unbekannteWurzelCa() {
        assertThrows(SSLHandshakeException::class.java) { get("https://untrusted-root.badssl.com/") }
    }

    @Test
    fun klartextVerbietetDieNetworkSecurityConfig() {
        // Unabhängig von OkHttp: Die Plattform blockiert http:// für die ganze App.
        assertThrows(IOException::class.java) {
            val connection = URL("http://neverssl.com/").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.inputStream.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}
