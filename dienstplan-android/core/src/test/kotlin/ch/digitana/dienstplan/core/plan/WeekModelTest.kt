package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.WeekId
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeekModelTest {

    private val dev = "00000000000000aa"
    private val anna = "000000000000000a"
    private val ben = "000000000000000b"
    private val gone = "000000000000000c"
    private val week = WeekId(2026, 39)
    private var ts = 1L

    private fun PlanState.set(key: String, value: String) = withEntry(key, Entry(value, ts++, dev))

    private val state = PlanState.EMPTY
        .set(PlanKeys.member(ben), "Ben")
        .set(PlanKeys.member(anna), "anna")
        .set(PlanKeys.member(gone), "Gelöscht").set(PlanKeys.member(gone), "")
        .set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 21)), "F")
        .set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 22)), "S")
        .set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 23)), "N")
        .set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 24)), "X")
        .set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 25)), "U")
        .set(PlanKeys.shift(ben, LocalDate.of(2026, 9, 21)), "F")
        .set(PlanKeys.shift(ben, LocalDate.of(2026, 9, 27)), "N")
        .set(PlanKeys.shift(ben, LocalDate.of(2026, 9, 26)), "S").set(PlanKeys.shift(ben, LocalDate.of(2026, 9, 26)), "")
        .set(PlanKeys.shift(gone, LocalDate.of(2026, 9, 21)), "F")
        .set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 28)), "F") // Folgewoche

    @Test
    fun `Zeilen, Stunden und Besetzung`() {
        val model = WeekModel.build(state, week, today = LocalDate.of(2026, 9, 23))
        assertEquals(listOf("anna", "Ben"), model.rows.map { it.member.name }) // deutsche Sortierung, gelöschte fehlen
        val annaRow = model.rows[0]
        assertEquals(listOf("F", "S", "N", "X", "U", null, null), annaRow.cells.map { it.typeId })
        assertEquals(listOf("Früh", "Spät", "Nacht", "Frei", "Urlaub"), annaRow.cells.take(5).map { it.type?.name })
        assertEquals(24 * 60, annaRow.minutes) // X und U zählen 0 h
        assertEquals(16 * 60, model.rows[1].minutes)
        assertEquals(40 * 60, model.totalMinutes)
        assertEquals(listOf("F", "S", "N"), model.coverageTypes.map { it.id }) // nur Arbeitsschichten
        fun counts(day: Int) = model.coverage[day].counts.map { it.second }
        assertEquals(listOf(2, 0, 0), counts(0)) // Gelöschte zählen nicht
        assertEquals(listOf(0, 1, 0), counts(1))
        assertEquals(listOf(0, 0, 1), counts(2))
        assertEquals(listOf(0, 0, 0), counts(5))
        assertEquals(listOf(0, 0, 1), counts(6))
        assertEquals(1, model.coverage[6].count("N"))
    }

    @Test
    fun `heute und Wochenende`() {
        val model = WeekModel.build(state, week, today = LocalDate.of(2026, 9, 23))
        assertEquals(listOf(false, false, true, false, false, false, false), model.days.map { it.isToday })
        assertEquals(listOf(false, false, false, false, false, true, true), model.days.map { it.isWeekend })
        assertTrue(model.days.all { it.editable })
        val other = WeekModel.build(state, week.next(), today = LocalDate.of(2026, 9, 23))
        assertFalse(other.days.any { it.isToday })
    }

    @Test
    fun `Beschriftungen`() {
        assertEquals("KW 39", WeekFormat.weekLabel(week))
        assertEquals("21.–27. September 2026", WeekFormat.rangeLabel(week))
        assertEquals("28. September – 4. Oktober 2026", WeekFormat.rangeLabel(WeekId(2026, 40)))
        assertEquals("29. Dezember 2025 – 4. Januar 2026", WeekFormat.rangeLabel(WeekId(2026, 1)))
        assertEquals("Mo", WeekFormat.weekday(LocalDate.of(2026, 9, 21)))
        assertEquals("So", WeekFormat.weekday(LocalDate.of(2026, 9, 27)))
        assertEquals("21.9.", WeekFormat.shortDate(LocalDate.of(2026, 9, 21)))
        assertEquals("Montag, 21. September 2026", WeekFormat.longDate(LocalDate.of(2026, 9, 21)))
    }

    @Test
    fun `Grenzwochen liegen im erlaubten Bereich`() {
        assertEquals(LocalDate.of(2000, 1, 3), WeekModel.FIRST_WEEK.monday)
        assertTrue(WeekModel.LAST_WEEK.sunday <= PlanKeys.MAX_DATE)
        assertTrue(WeekModel.LAST_WEEK.next().sunday > PlanKeys.MAX_DATE)
    }
}
