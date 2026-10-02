package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.Wish
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Monatsansicht, „Meine Dienste“, Rhythmen und Kalender-Export. */
class PlanViewsTest {

    private val dev = "00000000000000aa"
    private val anna = "000000000000000a"
    private val ben = "000000000000000b"
    private var ts = 1L
    private val custom = ShiftType("0a1b2c3d", "T", "Tag", LocalTime.of(7, 0), LocalTime.of(16, 30), breakMinutes = 30, color = 6)
    private val course = ShiftType("0a1b2c3e", "K", "Kurs, extern", kind = ShiftKind.ABSENCE, creditMinutes = 480, color = 8)

    private fun PlanState.set(key: String, value: String) = withEntry(key, Entry(value, ts++, dev))

    private val base = PlanState.EMPTY
        .set(PlanKeys.member(anna), "Anna")
        .set(PlanKeys.member(ben), "Ben")
        .set(PlanKeys.shiftType(custom.id), ShiftTypes.encode(custom))
        .set(PlanKeys.shiftType(course.id), ShiftTypes.encode(course))

    @Test
    fun `Monat mit eigenen Schichtarten, Notizen und Wuenschen`() {
        val state = base
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 1)), "F")
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 2)), custom.id)
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 3)), course.id)
            .set(PlanKeys.shift(ben, LocalDate.of(2026, 10, 2)), custom.id)
            .set(PlanKeys.shift(ben, LocalDate.of(2026, 10, 31)), "ffffffff") // unbekannte Art
            .set(PlanKeys.dayNote(LocalDate.of(2026, 10, 2)), "Teamsitzung")
            .set(PlanKeys.memberNote(anna, LocalDate.of(2026, 10, 1)), "Schlüssel holen")
            .set(PlanKeys.wish(ben, LocalDate.of(2026, 10, 5)), Wish.DAY_OFF.code)
        val model = MonthModel.build(state, YearMonth.of(2026, 10), today = LocalDate.of(2026, 10, 1))
        assertEquals(31, model.days.size)
        assertTrue(model.days[0].isToday)
        assertEquals("Teamsitzung", model.days[1].note)
        val annaRow = model.rows[0]
        assertEquals(8 * 60 + 9 * 60 + 8 * 60, annaRow.minutes) // Früh + Tag (9.5 h − 30 min) + Kurs
        assertEquals("Schlüssel holen", annaRow.cells[0].note)
        val benRow = model.rows[1]
        assertEquals(Wish.DAY_OFF, benRow.cells[4].wish)
        assertNull(benRow.cells[4].typeId)
        assertTrue(benRow.cells[30].isUnknownType)
        assertEquals(9 * 60, benRow.minutes) // Unbekanntes zählt nicht
        assertEquals(listOf("F", "T", "S", "N"), model.coverage[0].counts.map { it.first.code })
        assertEquals(2, model.coverage[1].count(custom.id))
        assertEquals(0, model.coverage[2].total) // Kurs zählt nicht zur Besetzung
    }

    @Test
    fun `archivierte Arten erscheinen in der Besetzung nur, wenn sie vorkommen`() {
        val archived = base.set(PlanKeys.shiftType("N"), ShiftTypes.encode(ShiftTypes.default("N")!!.copy(archived = true)))
        val week = ch.digitana.dienstplan.core.crdt.WeekId(2026, 40)
        assertEquals(listOf("F", "T", "S"), WeekModel.build(archived, week, LocalDate.of(2026, 10, 1)).coverageTypes.map { it.code })
        val used = archived.set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 30)), "N")
        assertEquals(listOf("F", "T", "S", "N"), WeekModel.build(used, week, LocalDate.of(2026, 10, 1)).coverageTypes.map { it.code })
    }

    @Test
    fun `Meine Dienste ab Montag mit Stunden und naechstem Dienst`() {
        val today = LocalDate.of(2026, 10, 1) // Donnerstag
        val state = base
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 28)), "F") // Montag, vorbei
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 1)), "X")
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 2)), course.id)
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 5)), custom.id)
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 31)), "N")
            .set(PlanKeys.wish(anna, LocalDate.of(2026, 10, 9)), Wish.VACATION.code)
        val model = MyShiftsModel.build(state, anna, today)!!
        assertEquals(LocalDate.of(2026, 9, 28), model.days.first().date)
        assertEquals(MyShiftsModel.DEFAULT_WEEKS * 7, model.days.size)
        assertTrue(model.days[3].isToday)
        assertEquals(8 * 60 + 8 * 60, model.weekMinutes) // Früh am Montag + Kurs
        assertEquals(8 * 60 + 9 * 60 + 8 * 60, model.monthMinutes) // Kurs, Tag, Nacht im Oktober
        assertEquals(LocalDate.of(2026, 10, 5), model.next?.date) // Kurs zählt nicht als Dienst
        assertEquals(Wish.VACATION, model.days.first { it.date == LocalDate.of(2026, 10, 9) }.cell.wish)
        assertNull(MyShiftsModel.build(state, "000000000000000c", today))
        val upcoming = MyShiftsModel.upcoming(state, anna, today, limit = 3)
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5)), upcoming.map { it.date })
    }

    @Test
    fun `Rhythmus fuellt oder ueberschreibt`() {
        val monday = LocalDate.of(2026, 10, 5)
        val pattern = ShiftPattern(
            "0a1b2c3f", "Wechsel",
            listOf("F", "F", "F", "F", "F", null, null, "S", "S", "S", "S", "S", null, null),
        )
        val state = base
            .set(PlanKeys.shift(anna, monday), "U")
            .set(PlanKeys.shift(anna, monday.plusDays(5)), "X")
        val fill = PatternPlanner.changes(state, pattern, listOf(anna, ben, "000000000000000c"), monday, weeks = 3, overwrite = false)
        val fillMap = fill.toMap()
        assertEquals(null, fillMap[PlanKeys.shift(anna, monday)]) // bestehender Urlaub bleibt
        assertEquals("F", fillMap[PlanKeys.shift(anna, monday.plusDays(1))])
        assertEquals("S", fillMap[PlanKeys.shift(ben, monday.plusDays(7))])
        assertEquals("F", fillMap[PlanKeys.shift(ben, monday.plusDays(14))]) // Wiederholung
        assertEquals(null, fillMap[PlanKeys.shift(anna, monday.plusDays(5))]) // leerer Rhythmustag lässt Feld in Ruhe
        assertEquals(4 + 5 + 5 + 5 + 5 + 5, fill.size)
        val overwrite = PatternPlanner.changes(state, pattern, listOf(anna), monday, weeks = 1, overwrite = true).toMap()
        assertEquals("F", overwrite[PlanKeys.shift(anna, monday)])
        assertEquals("", overwrite[PlanKeys.shift(anna, monday.plusDays(5))]) // leert das Feld
        assertEquals(7, overwrite.size)
        assertThrows<IllegalArgumentException> { PatternPlanner.changes(state, pattern, listOf(anna), monday.plusDays(1), 1, true) }
        assertThrows<IllegalArgumentException> { PatternPlanner.changes(state, pattern, listOf(anna), monday, 53, true) }
    }

    @Test
    fun `Kalender-Export nach RFC 5545`() {
        val state = base
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 26)), "N") // Nacht nach der Zeitumstellung
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 27)), "X") // frei: fehlt
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 10, 28)), course.id) // ganztägig
            .set(PlanKeys.memberNote(anna, LocalDate.of(2026, 10, 26)), "Übergabe; Raum 2, bitte \\ pünktlich")
        val ics = CalendarExport.ics(state, anna, LocalDate.of(2026, 10, 26), 7, ZoneId.of("Europe/Zurich"), Instant.parse("2026-10-01T12:00:00Z"))
        assertTrue(ics.endsWith("\r\n"))
        val lines = ics.split("\r\n").dropLast(1)
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertEquals("END:VCALENDAR", lines.last())
        assertEquals(2, lines.count { it == "BEGIN:VEVENT" })
        assertTrue("DTSTART:20261026T210000Z" in lines) // 22:00 MEZ = 21:00 UTC
        assertTrue("DTEND:20261027T050000Z" in lines) // 8 h später
        assertTrue("SUMMARY:Nacht (N)" in lines)
        assertTrue("DESCRIPTION:Übergabe\\; Raum 2\\, bitte \\\\ pünktlich" in lines)
        assertTrue("DTSTART;VALUE=DATE:20261028" in lines)
        assertTrue("DTEND;VALUE=DATE:20261029" in lines)
        assertTrue("SUMMARY:Kurs\\, extern (K)" in lines)
        assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(lines.filter { it.startsWith("UID:") }.distinct().size == 2)
        val uid = lines.first { it.startsWith("UID:") }
        assertTrue(anna !in uid) // keine Mitglieds-ID in der Datei
    }

    @Test
    fun `lange Zeilen werden umgebrochen, ohne Zeichen zu teilen`() {
        val folded = CalendarExport.fold("DESCRIPTION:" + "ä".repeat(60))
        val parts = folded.split("\r\n")
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(parts.drop(1).all { it.startsWith(" ") })
        assertEquals("DESCRIPTION:" + "ä".repeat(60), parts.first() + parts.drop(1).joinToString("") { it.drop(1) })
    }
}
