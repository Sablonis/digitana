package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.Shift
import ch.digitana.dienstplan.core.data.DeviceSettings
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShiftWatchTest {

    private val dev = "00000000000000aa"
    private val anna = "000000000000000a"
    private val ben = "000000000000000b"
    private val today = LocalDate.of(2026, 10, 7) // Mittwoch, KW 41
    private var ts = 1L

    private fun PlanState.set(memberId: String, date: LocalDate, code: String) =
        withEntry(PlanKeys.shift(memberId, date), Entry(code, ts++, dev))

    private val base = PlanState.EMPTY
        .withEntry(PlanKeys.member(anna), Entry("Anna", ts++, dev))
        .withEntry(PlanKeys.member(ben), Entry("Ben", ts++, dev))
        .set(anna, today.minusDays(2), "F") // Vergangenheit, gleiche Woche
        .set(anna, today.minusDays(14), "N") // Vergangenheit, frühere Woche
        .set(anna, today, "F")
        .set(anna, today.plusDays(1), "S")
        .set(anna, today.plusDays(9), "U") // Folgewoche
        .set(anna, today.plusDays(2), "N").set(anna, today.plusDays(2), "") // geleert
        .set(ben, today, "N")

    private val enabled = DeviceSettings(myMemberId = anna, notifyOnChanges = true)

    @Test
    fun `kuenftige Dienste ohne Vergangenheit, leere Felder und andere Personen`() {
        assertEquals(
            mapOf(today to Shift.FRUEH, today.plusDays(1) to Shift.SPAET, today.plusDays(9) to Shift.URLAUB),
            ShiftWatch.upcomingShifts(base, anna, today),
        )
    }

    @Test
    fun `Unterschiede nach Datum sortiert, Vergangenheit ignoriert`() {
        val before = mapOf(today.minusDays(1) to Shift.FRUEH, today to Shift.FRUEH, today.plusDays(3) to Shift.NACHT)
        val after = mapOf(today to Shift.SPAET, today.plusDays(1) to Shift.FREI)
        assertEquals(
            listOf(
                ShiftChange(today, Shift.FRUEH, Shift.SPAET),
                ShiftChange(today.plusDays(1), null, Shift.FREI),
                ShiftChange(today.plusDays(3), Shift.NACHT, null),
            ),
            ShiftWatch.diff(before, after, today),
        )
    }

    @Test
    fun `erste Runde legt nur die Grundlage fest`() {
        val (next, alert) = ShiftWatch.afterSync(enabled, base, today)
        assertEquals(ChangeAlert.None, alert)
        assertEquals(ShiftWatch.upcomingShifts(base, anna, today), next.seenShifts)
    }

    @Test
    fun `Aenderung wird einmal gemeldet, Abweichungen kumuliert`() {
        val seen = ShiftWatch.seen(enabled, base, today)
        val changed = base.set(anna, today.plusDays(1), "N")

        val (afterFirst, first) = ShiftWatch.afterSync(seen, changed, today)
        assertEquals(ChangeAlert.Show(listOf(ShiftChange(today.plusDays(1), Shift.SPAET, Shift.NACHT))), first)

        val (afterRepeat, repeat) = ShiftWatch.afterSync(afterFirst, changed, today)
        assertEquals(ChangeAlert.None, repeat, "derselbe Stand wird nicht nochmals gemeldet")

        val more = changed.set(anna, today.plusDays(4), "F")
        val (_, second) = ShiftWatch.afterSync(afterRepeat, more, today)
        assertEquals(
            ChangeAlert.Show(
                listOf(
                    ShiftChange(today.plusDays(1), Shift.SPAET, Shift.NACHT),
                    ShiftChange(today.plusDays(4), null, Shift.FRUEH),
                ),
            ),
            second,
        )
    }

    @Test
    fun `Rueckgaengig gemachte Aenderung schliesst die Benachrichtigung`() {
        val seen = ShiftWatch.seen(enabled, base, today)
        val changed = base.set(anna, today, "X")
        val (notified, _) = ShiftWatch.afterSync(seen, changed, today)
        val reverted = changed.set(anna, today, "F")
        val (next, alert) = ShiftWatch.afterSync(notified, reverted, today)
        assertEquals(ChangeAlert.Cancel, alert)
        assertNull(next.notifiedShifts)
    }

    @Test
    fun `Aenderungen anderer Personen und vergangener Tage werden nicht gemeldet`() {
        val seen = ShiftWatch.seen(enabled, base, today)
        val changed = base.set(ben, today, "F").set(anna, today.minusDays(2), "X")
        assertEquals(ChangeAlert.None, ShiftWatch.afterSync(seen, changed, today).second)
    }

    @Test
    fun `ein vergangener Tag loest keine erneute Meldung aus`() {
        val seen = ShiftWatch.seen(enabled, base, today)
        val changed = base.set(anna, today.plusDays(1), "N")
        val (notified, _) = ShiftWatch.afterSync(seen, changed, today)
        // Am nächsten Tag ist „heute“ Vergangenheit; die offene Änderung bleibt dieselbe.
        assertEquals(ChangeAlert.None, ShiftWatch.afterSync(notified, changed, today.plusDays(1)).second)
    }

    @Test
    fun `ohne Person oder ausgeschaltet keine Meldung, offene wird geschlossen`() {
        val seen = ShiftWatch.seen(enabled, base, today)
        val changed = base.set(anna, today, "S")
        val (notified, _) = ShiftWatch.afterSync(seen, changed, today)

        val off = notified.copy(notifyOnChanges = false)
        assertEquals(ChangeAlert.Cancel, ShiftWatch.afterSync(off, changed, today).second)

        val nobody = DeviceSettings(notifyOnChanges = true)
        assertEquals(ChangeAlert.None, ShiftWatch.afterSync(nobody, changed, today).second)
    }

    @Test
    fun `Texte fuer die Benachrichtigung`() {
        val changes = listOf(
            ShiftChange(LocalDate.of(2026, 10, 5), Shift.FRUEH, Shift.SPAET),
            ShiftChange(LocalDate.of(2026, 10, 6), null, Shift.NACHT),
            ShiftChange(LocalDate.of(2026, 10, 7), Shift.URLAUB, null),
        )
        assertEquals("3 Änderungen an deinem Dienstplan", ShiftChangeText.title(changes))
        assertEquals("Dein Dienstplan wurde geändert", ShiftChangeText.title(changes.take(1)))
        assertEquals(
            listOf("Mo 5.10.: Früh → Spät", "Di 6.10.: Nacht (neu)", "Mi 7.10.: Urlaub entfällt"),
            changes.map(ShiftChangeText::line),
        )
    }
}
