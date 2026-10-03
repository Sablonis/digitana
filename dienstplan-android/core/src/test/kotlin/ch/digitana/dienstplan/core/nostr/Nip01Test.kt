package ch.digitana.dienstplan.core.nostr

import ch.digitana.dienstplan.core.crypto.Schnorr
import ch.digitana.dienstplan.core.testing.TestEvents
import ch.digitana.dienstplan.core.util.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip01Test {

    private val pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    /**
     * Referenzwerte unabhängig mit Python berechnet:
     * `sha256(json.dumps([0,pub,ca,kind,tags,content], ensure_ascii=False, separators=(',',':')).encode())`.
     * Für die hier verwendeten Zeichen stimmt das mit den NIP-01-Regeln überein.
     */
    @Test
    fun `Event-ID stimmt mit unabhaengiger Referenz ueberein`() {
        assertEquals(
            "82a407027587d7f13f7fb2b27f57772b0782eb70578463f92e2558be94c0f7c3",
            Hex.encode(Nip01.computeId(pubkey, 1, 1, emptyList(), "hello")),
        )
        val tags = listOf(
            listOf("d", "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"),
            listOf("alt", "Zitat: \"Hallo\"\\n"),
        )
        val content = "Zeile 1\nZeile 2\t\"zitiert\" \\ Backslash\r\b\u000C / Grüße ✓ 😀 \u007f"
        assertEquals(
            "0c271ee679f57cbe36d121e5a14435e7a88e7ff266af6a60f09b8daae836118d",
            Hex.encode(Nip01.computeId(pubkey, 1_790_000_000, 30078, tags, content)),
        )
    }

    @Test
    fun `Serialisierung maskiert genau die sieben NIP-01-Zeichen`() {
        val s = Nip01.serializeForId(pubkey, 1, 1, emptyList(), "\n\"\\\r\t\b\u000C/\u0001ä")
        assertEquals("[0,\"$pubkey\",1,1,[],\"\\n\\\"\\\\\\r\\t\\b\\f/\u0001ä\"]", s)
    }

    @Test
    fun `Signieren und Pruefen`() {
        val keys = ByteArray(32) { 5 }
        val event = TestEvents.sign(keys, 1_790_000_000, 30078, listOf(listOf("d", "ab".repeat(32))), "SGFsbG8=")
        assertTrue(TestEvents.verify(event))
        assertEquals(Hex.encode(Schnorr.xOnlyPublicKey(keys)), event.pubkey)
        assertTrue(Hex.isLowerHex(event.id, 64))
        assertTrue(Hex.isLowerHex(event.sig, 128))
    }

    @Test
    fun `jede Manipulation macht das Event ungueltig`() {
        val keys = ByteArray(32) { 5 }
        val other = ByteArray(32) { 6 }
        val event = TestEvents.sign(keys, 1_790_000_000, 30078, listOf(listOf("d", "ab".repeat(32))), "SGFsbG8=")
        val flippedSig = event.sig.substring(0, 10) + (if (event.sig[10] == '0') '1' else '0') + event.sig.substring(11)
        val flippedId = event.id.substring(0, 5) + (if (event.id[5] == '0') '1' else '0') + event.id.substring(6)
        val tampered = listOf(
            event.copy(content = "SGFsbG9=") to "Inhalt",
            event.copy(createdAt = event.createdAt + 1) to "created_at",
            event.copy(kind = 30079) to "kind",
            event.copy(tags = listOf(listOf("d", "cd".repeat(32)))) to "Tags",
            event.copy(tags = emptyList()) to "Tags entfernt",
            event.copy(sig = flippedSig) to "Signatur",
            event.copy(id = flippedId) to "ID",
            event.copy(pubkey = Hex.encode(Schnorr.xOnlyPublicKey(other))) to "fremder Pubkey",
            event.copy(id = event.id.uppercase()) to "ID in Grossbuchstaben",
            event.copy(sig = event.sig.dropLast(2)) to "Signatur zu kurz",
            event.copy(pubkey = "zz" + event.pubkey.drop(2)) to "Pubkey kein Hex",
        )
        for ((candidate, what) in tampered) assertFalse(TestEvents.verify(candidate), what)
        // Ein von einem fremden Schlüssel korrekt signiertes Event ist gültig – aber mit anderem Pubkey.
        val foreign = TestEvents.sign(other, event.createdAt, event.kind, event.tags, event.content)
        assertTrue(TestEvents.verify(foreign))
        assertTrue(foreign.pubkey != Hex.encode(Schnorr.xOnlyPublicKey(keys)))
    }

    @Test
    fun `Parsen ist streng typisiert`() {
        val keys = ByteArray(32) { 5 }
        val event = TestEvents.sign(keys, 1_790_000_000, 30078, listOf(listOf("d", "ab".repeat(32))), "SGFsbG8=")
        val json = event.toJson()
        assertEquals(event, Nip01.parseEvent(json))
        assertTrue(TestEvents.verify(Nip01.parseEvent(Json.parseToJsonElement(json.toString()))!!))

        fun with(name: String, value: kotlinx.serialization.json.JsonElement) =
            JsonObject(json.toMutableMap().apply { put(name, value) })

        assertNull(Nip01.parseEvent(with("created_at", JsonPrimitive("1790000000"))))
        assertNull(Nip01.parseEvent(with("created_at", Json.parseToJsonElement("1.79e9"))))
        assertNull(Nip01.parseEvent(with("created_at", Json.parseToJsonElement("-5"))))
        assertNull(Nip01.parseEvent(with("kind", Json.parseToJsonElement("30078.0"))))
        assertNull(Nip01.parseEvent(with("kind", Json.parseToJsonElement("99999999"))))
        assertNull(Nip01.parseEvent(with("tags", Json.parseToJsonElement("[[\"d\",1]]"))))
        assertNull(Nip01.parseEvent(with("tags", Json.parseToJsonElement("{\"d\":\"x\"}"))))
        assertNull(Nip01.parseEvent(with("content", Json.parseToJsonElement("null"))))
        assertNull(Nip01.parseEvent(with("sig", Json.parseToJsonElement("123"))))
        assertNull(Nip01.parseEvent(JsonObject(json.toMutableMap().apply { remove("id") })))
        assertNull(Nip01.parseEvent(Json.parseToJsonElement("[]")))
        assertNotNull(Nip01.parseEvent(with("unbekannt", JsonPrimitive("wird ignoriert"))))
    }

    @Test
    fun `Umlaute und Emojis im Inhalt sind nach dem Rundlauf ueber JSON pruefbar`() {
        val keys = ByteArray(32) { 5 }
        val event = TestEvents.sign(keys, 1_790_000_000, 1, listOf(listOf("t", "ä\"\\")), "Grüße 😀\n\t\u0001")
        val wire = RelayMessages.event(event)
        val parsed = Nip01.parseEvent(Json.parseToJsonElement(wire).let { (it as kotlinx.serialization.json.JsonArray)[1].jsonObject })
        assertNotNull(parsed)
        assertTrue(TestEvents.verify(parsed))
    }
}
