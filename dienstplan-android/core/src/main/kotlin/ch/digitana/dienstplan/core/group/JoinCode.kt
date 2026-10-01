package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.util.Hex
import java.security.MessageDigest
import java.util.Base64

/**
 * Beitrittscode eines Geräts: `DP3-` + Base64url(öffentlicher Schlüssel (32 Bytes) + erste
 * 4 Bytes von SHA-256(Schlüssel)).
 *
 * Der Code ist kein Geheimnis: Er sagt nur, welches Gerät ein Admin ins Team holen soll.
 * Zugang bekommt das Gerät erst, wenn ein Admin es hinzufügt und es die Einladung annimmt.
 * Die Prüfsumme erkennt Tippfehler (unerkannte Änderungen ≈ 2^-32).
 */
object JoinCode {
    const val PREFIX = "DP3-"
    private const val LEGACY_PREFIX = "DP2-"
    private const val KEY_SIZE = 32
    private const val CHECKSUM_SIZE = 4
    private const val PAYLOAD_SIZE = KEY_SIZE + CHECKSUM_SIZE
    /** 36 Bytes ergeben genau 48 Base64-Zeichen ohne Padding. */
    const val ENCODED_LENGTH = PAYLOAD_SIZE / 3 * 4

    enum class Problem {
        /** Keine Eingabe. */
        EMPTY,
        /** Ein Einladungscode der alten Version (DP2) – gilt nicht mehr. */
        OLD_VERSION,
        /** Kein `DP3-` gefunden. */
        MISSING_PREFIX,
        /** Zeichen ausserhalb von A–Z, a–z, 0–9, `-`, `_`. */
        INVALID_CHARACTERS,
        /** Zu kurz oder zu lang. */
        WRONG_LENGTH,
        /** Prüfsumme passt nicht – meist ein Tippfehler. */
        CHECKSUM_MISMATCH,
    }

    sealed interface ParseResult {
        /** [publicKey]: 64 Hex-Zeichen (klein). */
        data class Valid(val publicKey: String) : ParseResult
        data class Invalid(val problem: Problem) : ParseResult
    }

    fun encode(publicKey: String): String {
        require(Hex.isLowerHex(publicKey, KEY_SIZE * 2)) { "Öffentlicher Schlüssel ungültig" }
        val key = Hex.decode(publicKey)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(key + checksum(key))
    }

    /**
     * Liest einen Code, auch eingebettet in einen längeren Text (z. B. eine geteilte
     * Nachricht) oder von einem Messenger umbrochen.
     */
    fun parse(input: String): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) return ParseResult.Invalid(Problem.EMPTY)
        val start = text.indexOf(PREFIX, ignoreCase = true)
        if (start < 0) {
            val legacy = text.contains(LEGACY_PREFIX, ignoreCase = true)
            return ParseResult.Invalid(if (legacy) Problem.OLD_VERSION else Problem.MISSING_PREFIX)
        }

        val encoded = StringBuilder(ENCODED_LENGTH)
        var i = start + PREFIX.length
        while (i < text.length) {
            val c = text[i]
            when {
                isBase64Url(c) -> encoded.append(c)
                c.isWhitespace() -> if (encoded.length >= ENCODED_LENGTH) break // Code vollständig, Rest ist Text
                else -> {
                    if (encoded.length < ENCODED_LENGTH && (c.isLetterOrDigit() || c in "+/=")) {
                        return ParseResult.Invalid(Problem.INVALID_CHARACTERS)
                    }
                    break
                }
            }
            i++
        }
        if (encoded.length != ENCODED_LENGTH) return ParseResult.Invalid(Problem.WRONG_LENGTH)

        val payload = try {
            Base64.getUrlDecoder().decode(encoded.toString())
        } catch (e: IllegalArgumentException) {
            return ParseResult.Invalid(Problem.INVALID_CHARACTERS)
        }
        if (payload.size != PAYLOAD_SIZE) return ParseResult.Invalid(Problem.WRONG_LENGTH)
        val key = payload.copyOfRange(0, KEY_SIZE)
        if (!MessageDigest.isEqual(checksum(key), payload.copyOfRange(KEY_SIZE, PAYLOAD_SIZE))) {
            return ParseResult.Invalid(Problem.CHECKSUM_MISMATCH)
        }
        return ParseResult.Valid(Hex.encode(key))
    }

    /** Gekürzte Anzeige, z. B. `DP3-Qm9i…x4Zk`. */
    fun abbreviate(code: String): String {
        if (!code.startsWith(PREFIX) || code.length < PREFIX.length + 8) return PREFIX + "…"
        val body = code.substring(PREFIX.length)
        return PREFIX + body.take(4) + "…" + body.takeLast(4)
    }

    private fun checksum(key: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(key).copyOf(CHECKSUM_SIZE)

    private fun isBase64Url(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
}

/** Kurzer Fingerabdruck eines Geräteschlüssels zum Vergleichen, z. B. `3F2A 9C1D 0B7E 55AA`. */
object Fingerprint {
    fun of(publicKey: String): String =
        publicKey.take(16).uppercase().chunked(4).joinToString(" ")
}
