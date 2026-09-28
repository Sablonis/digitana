package ch.digitana.dienstplan.core.crypto

import ch.digitana.dienstplan.core.crypto.InviteCode.ParseResult
import ch.digitana.dienstplan.core.crypto.InviteCode.Problem
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InviteCodeTest {

    private val secret = TeamSecret(ByteArray(32) { (it * 3 + 1).toByte() })
    private val code = InviteCode.encode(secret)

    @Test
    fun `Format DP2 plus 48 Base64url-Zeichen`() {
        assertTrue(code.startsWith("DP2-"))
        assertEquals(4 + 48, code.length)
        assertTrue(code.substring(4).all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `Hin und zurueck`() {
        val result = InviteCode.parse(code)
        assertIs<ParseResult.Valid>(result)
        assertEquals(secret, result.secret)
        repeat(50) {
            val s = TeamSecret.generate()
            assertEquals(s, (InviteCode.parse(InviteCode.encode(s)) as ParseResult.Valid).secret)
        }
    }

    @Test
    fun `Code in einer geteilten Nachricht und mit Umbruechen wird erkannt`() {
        val message = "Einladung zum Dienstplan:\n$code\nBitte nicht weitergeben."
        assertIs<ParseResult.Valid>(InviteCode.parse(message))
        val wrapped = code.substring(0, 20) + "\n" + code.substring(20, 40) + " " + code.substring(40)
        assertIs<ParseResult.Valid>(InviteCode.parse(wrapped))
        assertIs<ParseResult.Valid>(InviteCode.parse("  $code  "))
        assertIs<ParseResult.Valid>(InviteCode.parse("dp2-" + code.substring(4)))
    }

    @Test
    fun `jeder einzelne Tippfehler wird erkannt`() {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
        for (position in 4 until code.length) {
            for (replacement in alphabet) {
                if (replacement == code[position]) continue
                val typo = code.substring(0, position) + replacement + code.substring(position + 1)
                val result = InviteCode.parse(typo)
                assertTrue(result is ParseResult.Invalid, "Tippfehler an Stelle $position ($replacement) nicht erkannt")
            }
        }
    }

    @Test
    fun `vertauschte Nachbarzeichen werden erkannt`() {
        for (position in 4 until code.length - 1) {
            if (code[position] == code[position + 1]) continue
            val swapped = StringBuilder(code).apply {
                val c = this[position]
                setCharAt(position, this[position + 1])
                setCharAt(position + 1, c)
            }.toString()
            assertEquals(ParseResult.Invalid(Problem.CHECKSUM_MISMATCH), InviteCode.parse(swapped))
        }
    }

    @Test
    fun `typische Fehleingaben liefern verstaendliche Gruende`() {
        assertEquals(ParseResult.Invalid(Problem.EMPTY), InviteCode.parse("   "))
        assertEquals(ParseResult.Invalid(Problem.MISSING_PREFIX), InviteCode.parse(code.substring(4)))
        assertEquals(ParseResult.Invalid(Problem.WRONG_LENGTH), InviteCode.parse(code.dropLast(1)))
        assertEquals(ParseResult.Invalid(Problem.WRONG_LENGTH), InviteCode.parse(code + "A"))
        assertEquals(ParseResult.Invalid(Problem.INVALID_CHARACTERS), InviteCode.parse(code.substring(0, 10) + "+" + code.substring(11)))
        assertEquals(ParseResult.Invalid(Problem.INVALID_CHARACTERS), InviteCode.parse(code.substring(0, 10) + "ä" + code.substring(11)))
        assertEquals(ParseResult.Invalid(Problem.WRONG_LENGTH), InviteCode.parse("DP2-"))
    }

    @Test
    fun `zufaellige Eingaben werfen nie`() {
        val random = Random(7)
        repeat(5000) {
            val length = random.nextInt(0, 80)
            val input = "DP2-" + String(CharArray(length) { (random.nextInt(32, 0x3000)).toChar() })
            InviteCode.parse(input)
        }
    }

    @Test
    fun `gekuerzte Anzeige verraet den Code nicht`() {
        val short = InviteCode.abbreviate(code)
        assertEquals("DP2-" + code.substring(4, 8) + "…" + code.takeLast(4), short)
        assertTrue(short.length < 16)
    }
}
