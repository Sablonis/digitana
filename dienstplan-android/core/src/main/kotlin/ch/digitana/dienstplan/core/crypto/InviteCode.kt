package ch.digitana.dienstplan.core.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * Einladungscode: `DP2-` + Base64url(Geheimnis (32 Bytes) + erste 4 Bytes von SHA-256(Geheimnis)).
 *
 * Die Prüfsumme erkennt Tippfehler (Fehlerrate unerkannter Änderungen ≈ 2^-32).
 * Sie ist kein Schutz gegen Angreifer – wer den Code hat, hat Zugriff aufs Team.
 */
object InviteCode {
    const val PREFIX = "DP2-"
    private const val CHECKSUM_SIZE = 4
    private const val PAYLOAD_SIZE = TeamSecret.SIZE + CHECKSUM_SIZE
    /** 36 Bytes ergeben genau 48 Base64-Zeichen ohne Padding. */
    const val ENCODED_LENGTH = PAYLOAD_SIZE / 3 * 4

    enum class Problem {
        /** Keine Eingabe. */
        EMPTY,
        /** Kein `DP2-` gefunden. */
        MISSING_PREFIX,
        /** Zeichen ausserhalb von A–Z, a–z, 0–9, `-`, `_`. */
        INVALID_CHARACTERS,
        /** Zu kurz oder zu lang. */
        WRONG_LENGTH,
        /** Prüfsumme passt nicht – meist ein Tippfehler. */
        CHECKSUM_MISMATCH,
    }

    sealed interface ParseResult {
        data class Valid(val secret: TeamSecret) : ParseResult
        data class Invalid(val problem: Problem) : ParseResult
    }

    fun encode(secret: TeamSecret): String {
        val secretBytes = secret.bytes()
        val payload = secretBytes + checksum(secretBytes)
        secretBytes.fill(0)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    /**
     * Liest einen Code, auch wenn er in einen längeren Text eingebettet ist
     * (z. B. aus einer geteilten Nachricht) oder von einem Messenger umbrochen wurde.
     */
    fun parse(input: String): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) return ParseResult.Invalid(Problem.EMPTY)
        val start = text.indexOf(PREFIX, ignoreCase = true)
        if (start < 0) return ParseResult.Invalid(Problem.MISSING_PREFIX)

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

        val secretBytes = payload.copyOfRange(0, TeamSecret.SIZE)
        val expected = checksum(secretBytes)
        val actual = payload.copyOfRange(TeamSecret.SIZE, PAYLOAD_SIZE)
        payload.fill(0)
        if (!MessageDigest.isEqual(expected, actual)) {
            secretBytes.fill(0)
            return ParseResult.Invalid(Problem.CHECKSUM_MISMATCH)
        }
        return ParseResult.Valid(TeamSecret(secretBytes).also { secretBytes.fill(0) })
    }

    /** Gekürzte Anzeige für die Oberfläche, z. B. `DP2-Qm9i…x4Zk`. */
    fun abbreviate(code: String): String {
        if (!code.startsWith(PREFIX) || code.length < PREFIX.length + 8) return PREFIX + "…"
        val body = code.substring(PREFIX.length)
        return PREFIX + body.take(4) + "…" + body.takeLast(4)
    }

    private fun checksum(secret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret).copyOf(CHECKSUM_SIZE)

    private fun isBase64Url(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
}
