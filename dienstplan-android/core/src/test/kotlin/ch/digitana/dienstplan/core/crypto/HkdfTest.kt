package ch.digitana.dienstplan.core.crypto

import ch.digitana.dienstplan.core.util.Hex
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Testfälle 1–3 aus RFC 5869, Anhang A (HKDF-SHA256). */
class HkdfTest {

    private fun bytes(range: IntRange) = ByteArray(range.count()) { (range.first + it).toByte() }

    @Test
    fun `RFC 5869 Testfall 1 - Grundfall`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = bytes(0x00..0x0c)
        val info = bytes(0xf0..0xf9)
        val okm = Hkdf.sha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(okm),
        )
    }

    @Test
    fun `RFC 5869 Testfall 2 - lange Eingaben`() {
        val ikm = bytes(0x00..0x4f)
        val salt = bytes(0x60..0xaf)
        val info = bytes(0xb0..0xff)
        val okm = Hkdf.sha256(ikm, salt, info, 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hex.encode(okm),
        )
    }

    @Test
    fun `RFC 5869 Testfall 3 - leeres Salt und leeres info`() {
        val ikm = ByteArray(22) { 0x0b }
        val okm = Hkdf.sha256(ikm, ByteArray(0), ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hex.encode(okm),
        )
    }
}
