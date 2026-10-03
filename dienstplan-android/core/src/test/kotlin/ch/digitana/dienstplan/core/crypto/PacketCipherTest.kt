package ch.digitana.dienstplan.core.crypto

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PacketCipherTest {

    private val key = ByteArray(32) { (it * 7).toByte() }
    private val cipher = PacketCipher(key)
    private val aad = "DP2|abc".toByteArray()
    private val plaintext = "{\"v\":2,\"b\":\"team\",\"e\":[]}".toByteArray()

    @Test
    fun `Hin und zurueck`() {
        val sealed = cipher.encrypt(plaintext, aad)
        assertEquals(plaintext.size + PacketCipher.NONCE_SIZE + PacketCipher.TAG_SIZE, sealed.size)
        assertContentEquals(plaintext, cipher.decrypt(sealed, aad))
    }

    @Test
    fun `jedes Paket hat eine eigene Nonce`() {
        val a = cipher.encrypt(plaintext, aad)
        val b = cipher.encrypt(plaintext, aad)
        assertFalse(a.copyOf(12).contentEquals(b.copyOf(12)))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `jedes einzelne gekippte Bit wird erkannt (Nonce, Chiffrat, Tag)`() {
        val sealed = cipher.encrypt(plaintext, aad)
        for (i in sealed.indices) {
            for (bit in 0 until 8) {
                val tampered = sealed.copyOf()
                tampered[i] = (tampered[i].toInt() xor (1 shl bit)).toByte()
                assertNull(cipher.decrypt(tampered, aad), "Byte $i, Bit $bit")
            }
        }
    }

    @Test
    fun `gekuerzte, verlaengerte und leere Pakete werden verworfen`() {
        val sealed = cipher.encrypt(plaintext, aad)
        assertNull(cipher.decrypt(sealed.copyOf(sealed.size - 1), aad))
        assertNull(cipher.decrypt(sealed + byteArrayOf(0), aad))
        assertNull(cipher.decrypt(ByteArray(0), aad))
        assertNull(cipher.decrypt(ByteArray(27), aad))
        assertNull(cipher.decrypt(sealed.copyOf(PacketCipher.MIN_CIPHERTEXT_SIZE), aad))
    }

    @Test
    fun `Paket eines fremden Teams wird verworfen`() {
        val foreign = PacketCipher(ByteArray(32) { 1 }).encrypt(plaintext, aad)
        assertNull(cipher.decrypt(foreign, aad))
    }

    @Test
    fun `Paket unter anderem d-Tag (AAD) wird verworfen`() {
        val sealed = cipher.encrypt(plaintext, aad)
        assertNull(cipher.decrypt(sealed, "DP2|abd".toByteArray()))
        assertNull(cipher.decrypt(sealed, ByteArray(0)))
    }

    @Test
    fun `zufaelliger Muell wird verworfen, nie eine Exception`() {
        val random = Random(42)
        repeat(2000) {
            val garbage = random.nextBytes(random.nextInt(0, 200))
            assertNull(cipher.decrypt(garbage, aad))
        }
    }

    @Test
    fun `falsche Schluessellaenge wird abgelehnt`() {
        runCatching { PacketCipher(ByteArray(16)) }.also { assert(it.isFailure) }
    }
}
