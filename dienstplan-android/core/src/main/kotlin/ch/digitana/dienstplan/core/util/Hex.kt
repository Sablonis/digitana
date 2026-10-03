package ch.digitana.dienstplan.core.util

/** Hexadezimal-Kodierung. Akzeptiert beim Dekodieren nur ASCII-Ziffern und a–f/A–F. */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[2 * i] = DIGITS[v ushr 4]
            out[2 * i + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    /** @throws IllegalArgumentException bei ungerader Länge oder fremden Zeichen. */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex-Länge ist ungerade" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = nibble(hex[2 * i])
            val lo = nibble(hex[2 * i + 1])
            require(hi >= 0 && lo >= 0) { "Ungültiges Hex-Zeichen" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** true, wenn [s] genau [length] Zeichen aus 0–9 und a–f enthält. */
    fun isLowerHex(s: String, length: Int): Boolean =
        s.length == length && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun nibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
