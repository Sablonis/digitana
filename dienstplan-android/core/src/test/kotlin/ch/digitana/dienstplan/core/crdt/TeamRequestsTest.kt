package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.crdt.EntryValidator.Problem
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pensum, Teameinstellungen, Angebote und Tauschvorschläge: Format, Buckets und Sperre. */
class TeamRequestsTest {

    private val now = 1_790_000_000_000L
    private val anna = "000000000000000a"
    private val ben = "000000000000000b"
    private val device = "fedcba9876543210"
    private val monday = LocalDate.of(2026, 10, 5)
    private val week = WeekId.of(monday).bucketName

    private fun check(bucket: String, key: String, value: String) = EntryValidator.check(bucket, key, Entry(value, now, device), now)

    @Test
    fun `Schluessel lesen und bauen`() {
        assertEquals(PlanKey.Pensum(anna), PlanKeys.parse("p|$anna"))
        assertEquals(PlanKey.Offer(anna, monday), PlanKeys.parse("o|$anna|2026-10-05"))
        assertEquals(PlanKey.Swap(anna, monday, ben, monday.plusDays(3)), PlanKeys.parse("t|$anna|2026-10-05|$ben|2026-10-08"))
        assertEquals("t|$anna|2026-10-05|$ben|2026-10-08", PlanKeys.swap(anna, monday, ben, monday.plusDays(3)))
        assertEquals(PlanKey.Setting("hours"), PlanKeys.parse("c|hours"))
        assertEquals(PlanKey.Setting("canton"), PlanKeys.parse("c|canton"))
        assertEquals(PlanKey.Setting("wish-2026-11"), PlanKeys.parse("c|wish-2026-11"))
        assertEquals("c|wish-2026-11", PlanKeys.setting(PlanRules.wishDeadline(YearMonth.of(2026, 11))))
        val invalid = listOf(
            "p|000000000000000A", "p|$anna|x", "o|$anna", "o|$anna|2026-02-30", "o|$anna|1999-12-31",
            "t|$anna|2026-10-05|$anna|2026-10-06", // mit sich selbst tauschen
            "t|$anna|2026-10-05|$ben", "t|$anna|2026-10-05|$ben|2026-13-01",
            "c|wish-2026-13", "c|wish-2026-00", "c|wish-1999-12", "c|wish-2101-01", "c|wish-26-11", "c|Hours", "c|kanton",
        )
        for (key in invalid) assertNull(PlanKeys.parse(key), key)
        assertFailsWith<IllegalArgumentException> { PlanKeys.swap(anna, monday, anna, monday) }
        assertFailsWith<IllegalArgumentException> { PlanKeys.setting("wish-1999-01") }
    }

    @Test
    fun `Werte werden streng geprueft`() {
        for (value in listOf("", "1", "50", "80", "100")) assertNull(check(Buckets.TEAM, "p|$anna", value), value)
        for (value in listOf("0", "101", "050", "80%", " 80", "1000")) assertEquals(Problem.VALUE, check(Buckets.TEAM, "p|$anna", value), value)

        for (value in listOf("", "60", "2520", "4200")) assertNull(check(Buckets.TEAM, "c|hours", value), value)
        for (value in listOf("0", "59", "4201", "02520", "42h")) assertEquals(Problem.VALUE, check(Buckets.TEAM, "c|hours", value), value)

        for (value in listOf("", "ZH", "AI", "VS")) assertNull(check(Buckets.TEAM, "c|canton", value), value)
        for (value in listOf("zh", "XX", "CH", "ZHH", "Z")) assertEquals(Problem.VALUE, check(Buckets.TEAM, "c|canton", value), value)

        for (value in listOf("", "2026-10-15", "2026-12-31")) assertNull(check(Buckets.TEAM, "c|wish-2026-11", value), value)
        for (value in listOf("2026-02-30", "15.10.2026", "1999-12-31", "2026-10-15 ")) {
            assertEquals(Problem.VALUE, check(Buckets.TEAM, "c|wish-2026-11", value), value)
        }

        val offer = "o|$anna|2026-10-05"
        for (value in listOf("", "F", "0a1b2c3d", "F@$ben")) assertNull(check(week, offer, value), value)
        for (value in listOf("Q", "F@", "F@$anna", "F@000000000000000B", "f", "F@$ben@$ben")) {
            assertEquals(Problem.VALUE, check(week, offer, value), value)
        }

        val swap = "t|$anna|2026-10-05|$ben|2026-10-08"
        for (value in listOf("", "P:F:S", "P:F:", "A:0a1b2c3d:N", "X:F:S", "D:N:")) assertNull(check(week, swap, value), value)
        for (value in listOf("Q:F:S", "P:F", "P::S", "P:F:S:", "p:F:S", "P:F:Q")) assertEquals(Problem.VALUE, check(week, swap, value), value)
    }

    @Test
    fun `Buckets passen zum Datum`() {
        assertEquals(Problem.WRONG_BUCKET, check(week, "p|$anna", "80"))
        assertEquals(Problem.WRONG_BUCKET, check(Buckets.TEAM, "o|$anna|2026-10-05", "F"))
        // Ein Tausch liegt in der Woche seines ersten Tages, auch wenn der zweite in einer anderen liegt.
        val acrossWeeks = "t|$anna|2026-10-05|$ben|2026-10-14"
        assertNull(check(week, acrossWeeks, "P:F:S"))
        assertEquals(Problem.WRONG_BUCKET, check(WeekId.of(LocalDate.of(2026, 10, 14)).bucketName, acrossWeeks, "P:F:S"))
    }

    @Test
    fun `Pensum und Regeln sind geschuetzt, Angebote und Tausch nicht`() {
        val admin = "00000000000000bb"
        val member = "00000000000000cc"
        val access = PlanAccess(PlanLock(listOf(PlanLock.Segment(null, 1000)), setOf(admin)), setOf(admin))
        assertFalse(access.allows("p|$anna", Entry("80", 2000, member)))
        assertTrue(access.allows("p|$anna", Entry("80", 2000, admin)))
        assertFalse(access.mayWrite(member, "c|canton", "ZH"))
        assertFalse(access.mayWrite(member, "c|wish-2026-11", "2026-10-15"))
        assertTrue(access.mayWrite(member, "o|$anna|2026-10-05", "F"))
        assertTrue(access.mayWrite(member, "t|$anna|2026-10-05|$ben|2026-10-08", "P:F:S"))
        assertTrue(access.allows("t|$anna|2026-10-05|$ben|2026-10-08", Entry("A:F:S", 2000, member)))
    }

    @Test
    fun `Zugriff ueber den Planstand`() {
        var ts = 1L
        fun PlanState.set(key: String, value: String) = withEntry(key, Entry(value, ts++, device))
        val empty = PlanState.EMPTY
        assertEquals(PlanRules.DEFAULT_WEEK_MINUTES, empty.weekMinutes)
        assertNull(empty.canton)
        assertNull(empty.pensum(anna))
        val state = empty
            .set("p|$anna", "80")
            .set("c|hours", "2460")
            .set("c|canton", "BE")
            .set("c|wish-2026-11", "2026-10-15")
            .set("c|wish-2026-12", "2026-11-15")
            .set("c|wish-2026-12", "") // Frist entfernt
            .set("o|$ben|2026-10-07", "S")
            .set("o|$anna|2026-10-06", "F@$ben")
            .set("o|$anna|2026-10-08", "N")
            .set("o|$anna|2026-10-08", "") // zurückgezogen
            .set("t|$anna|2026-10-09|$ben|2026-10-12", "P:F:S")
        assertEquals(80, state.pensum(anna))
        assertEquals(2460, state.weekMinutes)
        assertEquals(Canton.BE, state.canton)
        assertEquals(LocalDate.of(2026, 10, 15), state.wishDeadline(YearMonth.of(2026, 11)))
        assertNull(state.wishDeadline(YearMonth.of(2026, 12)))
        assertEquals(mapOf(YearMonth.of(2026, 11) to LocalDate.of(2026, 10, 15)), state.wishDeadlines)
        assertEquals(listOf(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7)), state.offers.map { it.date })
        assertEquals(ShiftOffer("F", ben), state.offer(anna, LocalDate.of(2026, 10, 6)))
        val swap = state.swaps.single()
        assertEquals(SwapRequest(SwapStatus.PROPOSED, "F", "S"), swap.request)
        assertEquals("t|$anna|2026-10-09|$ben|2026-10-12", swap.key)
        assertTrue(swap.request.isOpen)
        assertFalse(swap.request.with(SwapStatus.DONE).isOpen)
    }
}
