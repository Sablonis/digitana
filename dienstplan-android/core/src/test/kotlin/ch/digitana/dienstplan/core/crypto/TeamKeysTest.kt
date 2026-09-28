package ch.digitana.dienstplan.core.crypto

import ch.digitana.dienstplan.core.util.Hex
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamKeysTest {

    private val secret = TeamSecret(ByteArray(32) { it.toByte() })

    @Test
    fun `Ableitung ist deterministisch`() {
        val a = TeamKeys.derive(secret)
        val b = TeamKeys.derive(TeamSecret(ByteArray(32) { it.toByte() }))
        assertEquals(a.publicKeyHex, b.publicKeyHex)
        assertEquals(a.dTag("team"), b.dTag("team"))
        val sealed = a.packetCipher.encrypt("x".toByteArray(), ByteArray(0))
        assertEquals("x", b.packetCipher.decrypt(sealed, ByteArray(0))!!.decodeToString())
    }

    @Test
    fun `verschiedene Geheimnisse ergeben unabhaengige Schluessel`() {
        val a = TeamKeys.derive(secret)
        val b = TeamKeys.derive(TeamSecret.generate())
        assertNotEquals(a.publicKeyHex, b.publicKeyHex)
        assertNotEquals(a.dTag("team"), b.dTag("team"))
        val sealed = a.packetCipher.encrypt("x".toByteArray(), ByteArray(0))
        assertNull(b.packetCipher.decrypt(sealed, ByteArray(0)))
    }

    @Test
    fun `d-Tags sind pro Bucket verschieden und verraten den Namen nicht`() {
        val keys = TeamKeys.derive(secret)
        val tags = listOf("team", "2026-W39", "2026-W40").map { keys.dTag(it) }
        assertEquals(3, tags.toSet().size)
        tags.forEach {
            assertTrue(Hex.isLowerHex(it, 64))
            assertTrue(!it.contains("2026"))
        }
    }

    @Test
    fun `Public Key ist ein gueltiger x-only-Schluessel und Signaturen pruefen`() {
        val keys = TeamKeys.derive(secret)
        assertTrue(Hex.isLowerHex(keys.publicKeyHex, 64))
        val message = ByteArray(32) { 9 }
        val signature = keys.sign(message)
        assertTrue(Schnorr.verify(signature, message, Hex.decode(keys.publicKeyHex)))
    }

    @Test
    fun `Geheimnis erscheint nicht in toString`() {
        assertEquals("TeamSecret(***)", secret.toString())
        assertTrue(!TeamKeys.derive(secret).toString().contains(Hex.encode(secret.bytes())))
    }
}
