package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crypto.TeamKeys
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.sync.BucketCodec.RejectReason
import ch.digitana.dienstplan.core.sync.BucketCodec.Result
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BucketCodecTest {

    private val keys = TeamKeys.derive(TeamSecret(ByteArray(32) { 1 }))
    private val now = 1_790_000_000_000L
    private val id = "0123456789abcdef"
    private val device = "00000000000000aa"
    private val week = "2026-W39"
    private val dTag = keys.dTag(week)

    private fun decode(plaintext: String, tag: String = dTag) =
        BucketCodec.decodePlaintext(plaintext.toByteArray(), tag, now, keys::dTag)

    @Test
    fun `Hin und zurueck ueber Verschluesselung`() {
        val map = LwwMap.of(
            mapOf(
                "z|$id|2026-09-21" to Entry("F", now - 5, device),
                "z|$id|2026-09-22" to Entry("", now - 4, device),
            ),
        )
        val content = BucketCodec.encrypt(keys.packetCipher, dTag, week, map)
        val result = BucketCodec.decrypt(keys.packetCipher, dTag, content, now, keys::dTag)
        assertIs<Result.Ok>(result)
        assertEquals(week, result.bucket)
        assertEquals(map.entries, result.entries)
        assertEquals(0, result.dropped)
    }

    @Test
    fun `fremder Schluessel, anderer d-Tag oder manipuliertes Chiffrat werden still verworfen`() {
        val map = LwwMap.of(mapOf("z|$id|2026-09-21" to Entry("F", now, device)))
        val content = BucketCodec.encrypt(keys.packetCipher, dTag, week, map)
        val foreign = TeamKeys.derive(TeamSecret(ByteArray(32) { 2 }))
        assertEquals(Result.Rejected(RejectReason.UNDECRYPTABLE), BucketCodec.decrypt(foreign.packetCipher, dTag, content, now, foreign::dTag))
        // Paket unter den d-Tag einer anderen Woche verschoben
        val otherTag = keys.dTag("2026-W40")
        assertEquals(Result.Rejected(RejectReason.UNDECRYPTABLE), BucketCodec.decrypt(keys.packetCipher, otherTag, content, now, keys::dTag))
        val raw = Base64.getDecoder().decode(content)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 1).toByte()
        assertEquals(
            Result.Rejected(RejectReason.UNDECRYPTABLE),
            BucketCodec.decrypt(keys.packetCipher, dTag, Base64.getEncoder().encodeToString(raw), now, keys::dTag),
        )
        assertEquals(Result.Rejected(RejectReason.MALFORMED), BucketCodec.decrypt(keys.packetCipher, dTag, "kein base64!", now, keys::dTag))
    }

    @Test
    fun `Bucket im Klartext muss zum d-Tag passen`() {
        // Ein Teammitglied könnte versuchen, Einträge unter falschem Namen einzuschleusen.
        assertEquals(Result.Rejected(RejectReason.BUCKET_MISMATCH), decode("""{"v":2,"b":"2026-W40","e":[]}"""))
        assertEquals(Result.Rejected(RejectReason.BUCKET_MISMATCH), decode("""{"v":2,"b":"quatsch","e":[]}""", keys.dTag("quatsch")))
    }

    @Test
    fun `Strukturfehler verwerfen das ganze Event`() {
        val cases = mapOf(
            "" to RejectReason.MALFORMED,
            "[]" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week"}""" to RejectReason.MALFORMED,
            """{"v":"2","b":"$week","e":[]}""" to RejectReason.MALFORMED,
            """{"v":3,"b":"$week","e":[]}""" to RejectReason.UNSUPPORTED_VERSION,
            """{"v":2.0,"b":"$week","e":[]}""" to RejectReason.UNSUPPORTED_VERSION,
            """{"v":2,"b":5,"e":[]}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":{}}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":[["z|$id|2026-09-21","F",$now]]}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":[["z|$id|2026-09-21","F","$now","$device"]]}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":[["z|$id|2026-09-21","F",1.5,"$device"]]}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":[["z|$id|2026-09-21","F",1e12,"$device"]]}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":[["z|$id|2026-09-21","F",-5,"$device"]]}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":[["z|$id|2026-09-21",null,$now,"$device"]]}""" to RejectReason.MALFORMED,
            """{"v":2,"b":"$week","e":[["z|$id|2026-09-21","F",$now,"$device"],["z|$id|2026-09-21","S",$now,"$device"]]}""" to RejectReason.DUPLICATE_KEY,
            """{"v":2,"b":"$week","e":[[[[["tief"]]]]]}""" to RejectReason.MALFORMED,
        )
        for ((plaintext, reason) in cases) assertEquals(Result.Rejected(reason), decode(plaintext), plaintext)
    }

    @Test
    fun `mehr als 5000 Eintraege werden abgelehnt`() {
        val items = (0..Limits.MAX_ENTRIES_PER_EVENT).joinToString(",") { "[\"m|%016x\",\"A\",$now,\"$device\"]".format(it) }
        assertEquals(Result.Rejected(RejectReason.TOO_MANY_ENTRIES), decode("""{"v":2,"b":"team","e":[$items]}""", keys.dTag(Buckets.TEAM)))
        val exactly = (1..Limits.MAX_ENTRIES_PER_EVENT).joinToString(",") { "[\"m|%016x\",\"A\",$now,\"$device\"]".format(it) }
        val ok = decode("""{"v":2,"b":"team","e":[$exactly]}""", keys.dTag(Buckets.TEAM))
        assertIs<Result.Ok>(ok)
        assertEquals(Limits.MAX_ENTRIES_PER_EVENT, ok.entries.size)
    }

    @Test
    fun `ungueltige Eintraege werden einzeln verworfen`() {
        val plaintext = """
            {"v":2,"b":"$week","e":[
              ["z|$id|2026-09-21","F",$now,"$device"],
              ["z|$id|2026-09-28","F",$now,"$device"],
              ["m|$id","Anna",$now,"$device"],
              ["z|$id|2026-09-22","Q",$now,"$device"],
              ["z|$id|2026-09-23","N",0,"$device"],
              ["z|$id|2026-09-24","N",${now + Limits.MAX_FUTURE_MILLIS + 1},"$device"],
              ["z|$id|2026-09-25","N",$now,"KAPUTT"],
              ["z|$id|2026-02-30","N",$now,"$device"],
              ["z|$id|2026-09-26","\u0000",$now,"$device"]
            ],"zusatz":"wird ignoriert"}
        """.trimIndent()
        val result = decode(plaintext)
        assertIs<Result.Ok>(result)
        assertEquals(setOf("z|$id|2026-09-21"), result.entries.keys)
        assertEquals(8, result.dropped)
    }

    @Test
    fun `ungueltiges UTF-8 wird abgelehnt`() {
        val bytes = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte())
        assertEquals(Result.Rejected(RejectReason.MALFORMED), BucketCodec.decodePlaintext(bytes, dTag, now, keys::dTag))
    }
}
