package ch.digitana.dienstplan.core.nostr

import ch.digitana.dienstplan.core.crdt.Limits
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RelayMessagesTest {

    @Test
    fun `gueltige Nachrichten`() {
        assertIs<RelayMessage.Event>(RelayMessages.parse("""["EVENT","sub",{"id":"x"}]"""))
        assertEquals(RelayMessage.EndOfStoredEvents("sub"), RelayMessages.parse("""["EOSE","sub"]"""))
        assertEquals(RelayMessage.Ok("abc", true, ""), RelayMessages.parse("""["OK","abc",true,""]"""))
        assertEquals(RelayMessage.Ok("abc", false, "replaced: have newer event"), RelayMessages.parse("""["OK","abc",false,"replaced: have newer event"]"""))
        assertEquals(RelayMessage.Ok("abc", true, ""), RelayMessages.parse("""["OK","abc",true]"""))
        assertEquals(RelayMessage.Notice("hi"), RelayMessages.parse("""["NOTICE","hi"]"""))
        assertEquals(RelayMessage.Closed("sub", "error: nope"), RelayMessages.parse("""["CLOSED","sub","error: nope"]"""))
        assertEquals(RelayMessage.Auth("chal"), RelayMessages.parse("""["AUTH","chal"]"""))
    }

    @Test
    fun `kaputte und boesartige Nachrichten werden verworfen`() {
        val bad = listOf(
            "", "null", "{}", "[]", "[1]", "\"EVENT\"", "[\"EVENT\"]", "[\"EVENT\",\"sub\"]", "[\"EVENT\",1,{}]",
            "[\"EVENT\",\"sub\",{},{}]", "[\"OK\",\"abc\",\"true\",\"\"]", "[\"OK\",\"abc\",1,\"\"]", "[\"OK\",\"abc\"]",
            "[\"EOSE\"]", "[\"EOSE\",5]", "[\"WHAT\",\"x\"]", "[\"EVENT\",\"sub\",{}", "['EOSE','sub']",
            "[\"EOSE\",\"sub\",]", "/* x */[\"EOSE\",\"sub\"]", "[\"OK\",\"abc\",NaN,\"\"]",
        )
        for (text in bad) assertNull(RelayMessages.parse(text), text)
    }

    @Test
    fun `tiefe Verschachtelung wird ohne Stackueberlauf abgelehnt`() {
        val deep = "[\"EVENT\",\"sub\"," + "[".repeat(100_000) + "]".repeat(100_000) + "]"
        assertNull(RelayMessages.parse(deep))
    }

    @Test
    fun `Nachrichten ueber 2 MB werden abgelehnt`() {
        val big = "[\"NOTICE\",\"" + "a".repeat(Limits.MAX_MESSAGE_BYTES) + "\"]"
        assertNull(RelayMessages.parse(big))
        val multibyte = "[\"NOTICE\",\"" + "ä".repeat(Limits.MAX_MESSAGE_BYTES / 2) + "\"]"
        assertNull(RelayMessages.parse(multibyte))
        val ok = "[\"NOTICE\",\"" + "a".repeat(1000) + "\"]"
        assertIs<RelayMessage.Notice>(RelayMessages.parse(ok))
    }

    @Test
    fun `Zufallseingaben werfen nie`() {
        val random = Random(99)
        val alphabet = "[]{}\",:0123456789truefalsnEVNTOKS \\u"
        repeat(20_000) {
            val text = String(CharArray(random.nextInt(0, 60)) { alphabet[random.nextInt(alphabet.length)] })
            RelayMessages.parse(text)
        }
    }

    @Test
    fun `Filter und REQ`() {
        val filter = RelayMessages.filter(30078, "ab", 500, until = 42)
        assertEquals("""{"kinds":[30078],"authors":["ab"],"until":42,"limit":500}""", filter.toString())
        assertEquals("""["REQ","s1",{"kinds":[30078],"authors":["ab"],"until":42,"limit":500}]""", RelayMessages.req("s1", filter))
        assertEquals("""["CLOSE","s1"]""", RelayMessages.close("s1"))
    }
}
