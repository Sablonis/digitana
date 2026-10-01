package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.WeekId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GroupMessagesTest {

    private val now = 1_790_000_000_000L
    private val device = "00000000000000aa"
    private val member = "0123456789abcdef"
    private val week = WeekId.of(LocalDate.of(2026, 9, 21))

    private fun shifts(count: Int): Map<String, Entry> =
        (0 until count).associate { i ->
            val id = "%016x".format(i + 1)
            PlanKeys.shift(id, week.monday) to Entry("F", now - i, device)
        }

    private fun decode(content: String) = GroupMessages.decode(content, now)

    private fun state(content: String): GroupMessages.Message.State =
        (assertIs<GroupMessages.Decoded.Ok>(decode(content)).message as GroupMessages.Message.State)

    @Test
    fun `Stand hin und zurueck mit mehreren Buckets`() {
        val team = mapOf(PlanKeys.member(member) to Entry("Anna", now, device), PlanKeys.device(device) to Entry("Annas Handy", now, device))
        val parts = listOf(
            GroupMessages.Part(Buckets.TEAM, GroupMessages.digestOf(LwwMap.of(team)), team),
            GroupMessages.Part(week.bucketName, "0123456789abcdef", shifts(3)),
            GroupMessages.Part(week.next().bucketName, GroupMessages.EMPTY_DIGEST, emptyMap()),
        )
        val messages = GroupMessages.encodeState(parts)
        assertEquals(1, messages.size)
        val decoded = state(messages.single())
        assertEquals(parts.map { it.bucket }, decoded.parts.map { it.bucket })
        assertEquals(team, decoded.parts[0].entries)
        assertEquals("0123456789abcdef", decoded.parts[1].digest)
        assertEquals(emptyMap(), decoded.parts[2].entries)
        assertEquals(GroupMessages.digestOf(LwwMap.EMPTY), GroupMessages.EMPTY_DIGEST)
    }

    @Test
    fun `grosse Buckets werden auf mehrere Nachrichten verteilt`() {
        val entries = shifts(450)
        val packed = GroupMessages.pack(
            listOf(
                GroupMessages.Part(week.bucketName, "0123456789abcdef", entries),
                GroupMessages.Part(week.next().bucketName, GroupMessages.EMPTY_DIGEST, emptyMap()),
            ),
            maxEntries = 200,
        )
        assertEquals(listOf(200, 200, 50), packed.map { message -> message.sumOf { it.entries.size } })
        assertEquals(2, packed.last().size) // Rest plus leere Bitte
        val merged = packed.flatten().filter { it.bucket == week.bucketName }.flatMap { it.entries.entries }.associate { it.key to it.value }
        assertEquals(entries, merged)
    }

    @Test
    fun `ungueltige Eintraege werden einzeln verworfen und gezaehlt`() {
        val bad = """{"v":3,"t":"s","p":[{"b":"${week.bucketName}","h":"0123456789abcdef","e":[
            ["z|$member|${week.monday}","F",$now,"$device"],
            ["z|$member|${week.monday.plusDays(1)}","Q",$now,"$device"],
            ["z|$member|2026-01-01","F",$now,"$device"],
            ["z|$member|${week.monday.plusDays(2)}","F",${now + 2 * 24 * 3600 * 1000L},"$device"],
            ["z|$member|${week.monday.plusDays(3)}","F",$now,"KURZ"]
        ]},{"b":"team","h":"0123456789abcdef","e":[["m|$member","Anna\u0007",$now,"$device"]]}]}"""
        val decoded = state(bad)
        assertEquals(1, decoded.parts[0].entries.size)
        assertEquals(4, decoded.parts[0].dropped)
        assertEquals(1, decoded.parts[1].dropped)
    }

    @Test
    fun `Strukturfehler verwerfen die ganze Nachricht`() {
        val entry = """["m|$member","Anna",$now,"$device"]"""
        fun withParts(parts: String) = """{"v":3,"t":"s","p":[$parts]}"""
        val malformed = listOf(
            "kein json",
            "[]",
            """{"t":"s","p":[]}""",
            """{"v":"3","t":"s","p":[]}""",
            """{"v":3,"t":"q"}""",
            withParts(""),
            withParts("""{"b":"woche","h":"0123456789abcdef","e":[]}"""),
            withParts("""{"b":"team","h":"XYZ","e":[]}"""),
            withParts("""{"b":"team","h":"0123456789abcdef","e":[$entry,$entry]}"""),
            withParts("""{"b":"team","h":"0123456789abcdef","e":[["m|$member","Anna",-5,"$device"]]}"""),
            withParts("""{"b":"team","h":"0123456789abcdef","e":[["m|$member","Anna",1.5,"$device"]]}"""),
            withParts("""{"b":"team","h":"0123456789abcdef","e":[[["tief"]],"x",1,"$device"]}"""),
            """{"v":3,"t":"g","from":"2027-W01","to":"2026-W01","d":{}}""",
            """{"v":3,"t":"g","from":"2026-W01","to":"2026-W10","d":{"2026-W40":"0123456789abcdef"}}""",
        )
        for (content in malformed) assertEquals(GroupMessages.Decoded.Malformed, decode(content), content)
        assertEquals(GroupMessages.Decoded.Unsupported, decode("""{"v":4,"t":"neu"}"""))

        val tooMany = shifts(5001).entries.joinToString(",") { (key, e) -> """["$key","F",${e.timestamp},"$device"]""" }
        assertEquals(GroupMessages.Decoded.Malformed, decode(withParts("""{"b":"${week.bucketName}","h":"0123456789abcdef","e":[$tooMany]}""")))
        assertEquals(GroupMessages.Decoded.Malformed, decode("x".repeat(2 * 1024 * 1024 + 1)))
    }

    @Test
    fun `Uebersicht und Austritt`() {
        val window = GroupMessages.Window.around(week)
        assertEquals(WeekId.of(week.monday.minusWeeks(52)), window.from)
        val digests = mapOf(Buckets.TEAM to "0123456789abcdef", week.bucketName to "fedcba9876543210", "2000-W01" to "0000000000000000")
        val decoded = assertIs<GroupMessages.Decoded.Ok>(decode(GroupMessages.encodeOverview(window, digests))).message
        val overview = assertIs<GroupMessages.Message.Overview>(decoded)
        assertEquals(digests - "2000-W01", overview.digests)
        assertEquals(window, overview.window)
        assertThrows<IllegalArgumentException> {
            val many = (0 until 301).associate { WeekId.of(week.monday.plusWeeks(it - 150L)).bucketName to "0123456789abcdef" }
            GroupMessages.encodeOverview(GroupMessages.Window(WeekId.of(week.monday.minusWeeks(200)), WeekId.of(week.monday.plusWeeks(200))), many)
        }
        assertEquals(GroupMessages.Message.Leave, assertIs<GroupMessages.Decoded.Ok>(decode(GroupMessages.encodeLeave())).message)
    }
}
