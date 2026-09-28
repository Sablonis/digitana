package ch.digitana.dienstplan.core.crypto

import ch.digitana.dienstplan.core.util.Hex
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offizielle BIP-340-Testvektoren (bitcoin/bips, bip-0340/test-vectors.csv, unverändert
 * unter src/test/resources). Vektoren 15–18 signieren Nachrichten mit einer Länge ≠ 32 Byte;
 * libsecp256k1 unterstützt das zwar, secp256k1-kmp aber nur für 32 Byte. Nostr signiert
 * ausschliesslich 32-Byte-Event-IDs, deshalb werden diese vier Vektoren übersprungen.
 */
class SchnorrBip340Test {

    private data class Vector(
        val index: Int,
        val secretKey: String,
        val publicKey: String,
        val auxRand: String,
        val message: String,
        val signature: String,
        val result: Boolean,
        val comment: String,
    )

    private val vectors: List<Vector> by lazy {
        val text = requireNotNull(javaClass.getResource("/bip340-test-vectors.csv")).readText()
        text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split(',')
            Vector(f[0].toInt(), f[1], f[2], f[3], f[4], f[5], f[6] == "TRUE", f.getOrElse(7) { "" })
        }.toList()
    }

    @Test
    fun `alle 19 Vektoren sind geladen`() {
        assertEquals(19, vectors.size)
        assertEquals((0..18).toList(), vectors.map { it.index })
    }

    @TestFactory
    fun `BIP-340 Testvektoren`(): List<DynamicTest> = vectors.map { v ->
        DynamicTest.dynamicTest("Vektor ${v.index} ${v.comment}".trim()) {
            val message = Hex.decode(v.message)
            assumeTrue(message.size == 32, "secp256k1-kmp signiert nur 32-Byte-Nachrichten")
            val publicKey = Hex.decode(v.publicKey)
            val signature = Hex.decode(v.signature)

            if (v.secretKey.isNotEmpty()) {
                val secretKey = Hex.decode(v.secretKey)
                assertContentEquals(publicKey, Schnorr.xOnlyPublicKey(secretKey), "Public Key")
                val produced = Schnorr.sign(message, secretKey, Hex.decode(v.auxRand))
                assertEquals(v.signature.lowercase(), Hex.encode(produced), "Signatur")
            }
            assertEquals(v.result, Schnorr.verify(signature, message, publicKey), "Prüfergebnis")
        }
    }

    @Test
    fun `verify lehnt falsche Längen ab statt zu werfen`() {
        assertFalse(Schnorr.verify(ByteArray(63), ByteArray(32), ByteArray(32)))
        assertFalse(Schnorr.verify(ByteArray(64), ByteArray(31), ByteArray(32)))
        assertFalse(Schnorr.verify(ByteArray(64), ByteArray(32), ByteArray(33)))
        assertFalse(Schnorr.verify(ByteArray(64), ByteArray(32), ByteArray(32))) // x = 0 ist kein Punkt
    }

    @Test
    fun `Signatur mit frischer Zufaelligkeit ist gueltig und nicht deterministisch`() {
        val secretKey = Hex.decode("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        val publicKey = Schnorr.xOnlyPublicKey(secretKey)
        val message = ByteArray(32) { it.toByte() }
        val a = Schnorr.sign(message, secretKey)
        val b = Schnorr.sign(message, secretKey)
        assertTrue(Schnorr.verify(a, message, publicKey))
        assertTrue(Schnorr.verify(b, message, publicKey))
        assertFalse(a.contentEquals(b), "auxrand sollte die Nonce verändern")
    }

    @Test
    fun `ungueltige Schluessel werden erkannt`() {
        assertFalse(Schnorr.isValidSecretKey(ByteArray(32)))
        assertFalse(Schnorr.isValidSecretKey(Hex.decode("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141")))
        assertFalse(Schnorr.isValidSecretKey(ByteArray(31) { 1 }))
        assertTrue(Schnorr.isValidSecretKey(ByteArray(32) { 1 }))
    }
}
