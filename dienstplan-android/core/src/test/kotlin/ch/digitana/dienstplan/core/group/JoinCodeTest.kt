package ch.digitana.dienstplan.core.group

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JoinCodeTest {

    private val publicKey = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"

    private fun parse(input: String) = JoinCode.parse(input)

    @Test
    fun `Hin und zurueck, auch eingebettet und umbrochen`() {
        val code = JoinCode.encode(publicKey)
        assertTrue(code.startsWith("DP3-"))
        assertEquals(JoinCode.PREFIX.length + JoinCode.ENCODED_LENGTH, code.length)
        val valid = JoinCode.ParseResult.Valid(publicKey)
        assertEquals(valid, parse(code))
        assertEquals(valid, parse("Mein Code: $code – bitte hinzufügen."))
        assertEquals(valid, parse("  dp3-" + code.removePrefix("DP3-").chunked(12).joinToString("\n") + "  "))
    }

    @Test
    fun `Fehler werden benannt`() {
        val code = JoinCode.encode(publicKey)
        assertEquals(JoinCode.ParseResult.Invalid(JoinCode.Problem.EMPTY), parse("   "))
        assertEquals(JoinCode.ParseResult.Invalid(JoinCode.Problem.MISSING_PREFIX), parse("hallo"))
        assertEquals(JoinCode.ParseResult.Invalid(JoinCode.Problem.OLD_VERSION), parse("DP2-" + "A".repeat(48)))
        assertEquals(JoinCode.ParseResult.Invalid(JoinCode.Problem.WRONG_LENGTH), parse(code.dropLast(3)))
        assertEquals(JoinCode.ParseResult.Invalid(JoinCode.Problem.INVALID_CHARACTERS), parse(code.take(10) + "+" + code.drop(11)))
        val typo = code.take(20) + (if (code[20] == 'A') 'B' else 'A') + code.drop(21)
        assertEquals(JoinCode.ParseResult.Invalid(JoinCode.Problem.CHECKSUM_MISMATCH), parse(typo))
    }

    @Test
    fun `Anzeige gekuerzt und Fingerabdruck`() {
        val code = JoinCode.encode(publicKey)
        assertEquals("DP3-" + code.substring(4, 8) + "…" + code.takeLast(4), JoinCode.abbreviate(code))
        assertEquals("7E7E 9C42 A91B FEF1", Fingerprint.of(publicKey))
    }
}
