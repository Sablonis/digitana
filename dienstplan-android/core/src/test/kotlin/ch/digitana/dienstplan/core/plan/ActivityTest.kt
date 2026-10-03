package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.Wish
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Aktivität, Erinnerungen und Tausch-Ereignisse. */
class ActivityTest {

    private val dev = "00000000000000aa"
    private val anna = "000000000000000a"
    private val ben = "000000000000000b"
    private var ts = 1L
    private val monday = LocalDate.of(2026, 10, 5)

    private fun PlanState.set(key: String, value: String, device: String = dev) = withEntry(key, Entry(value, ts++, device))

    private val base = PlanState.EMPTY.set(PlanKeys.member(anna), "Anna").set(PlanKeys.member(ben), "Ben")

    @Test
    fun `Aktivitaet zeigt die neuesten Aenderungen ohne interne Eintraege`() {
        val state = base
            .set(PlanKeys.device(dev), "Stationshandy")
            .set(PlanKeys.deviceOwner(dev), anna)
            .set(PlanKeys.shift(anna, monday), "F")
            .set(PlanKeys.wish(ben, monday), Wish.DAY_OFF.code)
            .set(PlanKeys.shift(anna, monday), "")
            .set(PlanKeys.setting(PlanRules.REST), "600")
        val items = Activity.recent(state)
        assertEquals(
            listOf(PlanKeys.setting(PlanRules.REST), PlanKeys.shift(anna, monday), PlanKeys.wish(ben, monday), PlanKeys.member(ben), PlanKeys.member(anna)),
            items.map { it.key },
        )
        assertTrue(items[1].isRemoval)
        assertTrue(items[1].parsed is PlanKey.Shift)
        assertEquals(2, Activity.recent(state, limit = 2).size)
        assertEquals(PlanKeys.setting(PlanRules.REST), Activity.recent(state, limit = 1).single().key)
        assertTrue(Activity.recent(state, limit = 0).isEmpty())
    }

    @Test
    fun `Erinnerung an den naechsten eigenen Dienst`() {
        val state = base.set(PlanKeys.shift(anna, monday), "F").set(PlanKeys.shift(anna, monday.plusDays(1)), "X")
            .set(PlanKeys.shift(anna, monday.plusDays(2)), "S")
        val sunday = monday.minusDays(1)
        val evening = Reminders.nextShift(state, anna, sunday.atTime(12, 0), ReminderMode.EVENING_BEFORE)!!
        assertEquals(sunday.atTime(19, 0), evening.at)
        assertEquals(monday, evening.date)
        // Nach 19 Uhr am Vorabend: die nächste Arbeit ist Mittwoch (Dienstag ist frei).
        val next = Reminders.nextShift(state, anna, sunday.atTime(19, 30), ReminderMode.EVENING_BEFORE)!!
        assertEquals(monday.plusDays(2), next.date)
        assertEquals(monday.atTime(4, 0), Reminders.nextShift(state, anna, sunday.atTime(23, 0), ReminderMode.TWO_HOURS)!!.at)
        assertEquals(monday.atTime(5, 0), Reminders.nextShift(state, anna, sunday.atTime(23, 0), ReminderMode.ONE_HOUR)!!.at)
        assertNull(Reminders.nextShift(state, anna, sunday.atTime(23, 0), ReminderMode.OFF))
        assertNull(Reminders.nextShift(state, ben, sunday.atTime(23, 0), ReminderMode.EVENING_BEFORE))
    }

    @Test
    fun `Erinnerung an Wunschfristen nur ohne eigene Wuensche`() {
        val november = YearMonth.of(2026, 11)
        var state = base.set(PlanKeys.setting(PlanRules.wishDeadline(november)), "2026-10-15")
            .set(PlanKeys.setting(PlanRules.wishDeadline(YearMonth.of(2026, 12))), "2026-11-15")
        val reminder = Reminders.nextDeadline(state, anna, LocalDateTime.of(2026, 10, 1, 8, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 13, 9, 0), reminder.at)
        assertEquals(november, reminder.month)
        assertEquals(YearMonth.of(2026, 12), Reminders.nextDeadline(state, anna, LocalDateTime.of(2026, 10, 14, 8, 0))!!.month)
        assertEquals(november to LocalDate.of(2026, 10, 15), Reminders.openDeadline(state, LocalDate.of(2026, 10, 15)))
        assertEquals(YearMonth.of(2026, 12), Reminders.openDeadline(state, LocalDate.of(2026, 10, 16))?.first)
        state = state.set(PlanKeys.wish(anna, LocalDate.of(2026, 11, 3)), Wish.DAY_OFF.code)
        assertEquals(YearMonth.of(2026, 12), Reminders.nextDeadline(state, anna, LocalDateTime.of(2026, 10, 1, 8, 0))!!.month)
        assertEquals(november, Reminders.nextDeadline(state, ben, LocalDateTime.of(2026, 10, 1, 8, 0))!!.month)
    }

    @Test
    fun `Tausch-Ereignisse fuer Benachrichtigungen`() {
        val other = "00000000000000cc"
        val state = base
            .set(PlanKeys.shift(anna, monday), "F")
            .set(PlanKeys.shift(ben, monday.plusDays(1)), "S")
            .set(PlanKeys.swap(anna, monday, ben, monday.plusDays(1)), "P:F:S", other)
            .set(PlanKeys.offer(ben, monday.plusDays(1)), "S", other)
        val forBen = TradeWatch.events(state, ben, isAdmin = false, today = monday)
        assertEquals(1, forBen.size)
        assertTrue(forBen.single() is TradeEvent.SwapProposed)
        val forAnna = TradeWatch.events(state, anna, isAdmin = false, today = monday)
        assertEquals(listOf("o|$ben|2026-10-06=S"), forAnna.map { it.id })
        val answered = state.set(PlanKeys.swap(anna, monday, ben, monday.plusDays(1)), "A:F:S", other)
            .set(PlanKeys.offer(ben, monday.plusDays(1)), "S@$anna", other)
        val forAdmin = TradeWatch.events(answered, null, isAdmin = true, today = monday)
        assertEquals(2, forAdmin.size)
        assertTrue(forAdmin.any { it is TradeEvent.SwapNeedsAdmin } && forAdmin.any { it is TradeEvent.OfferNeedsAdmin })
        assertTrue(TradeWatch.events(answered, anna, isAdmin = false, today = monday).single() is TradeEvent.SwapAnswered)
        assertTrue(TradeWatch.events(state, ben, isAdmin = false, today = monday.plusDays(2)).isEmpty())
    }
}
