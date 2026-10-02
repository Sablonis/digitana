package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Canton
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feiertage nach Kanton, Soll-Stunden, Saldo und Kennzahlen. */
class WorkTimeTest {

    private val dev = "00000000000000aa"
    private val anna = "000000000000000a"
    private val ben = "000000000000000b"
    private var ts = 1L

    private fun PlanState.set(key: String, value: String) = withEntry(key, Entry(value, ts++, dev))

    private fun names(canton: Canton, year: Int) = SwissHolidays.of(canton, year).map { "${it.date}:${it.name}" }

    @Test
    fun `Ostern`() {
        val expected = mapOf(
            2000 to LocalDate.of(2000, 4, 23), 2024 to LocalDate.of(2024, 3, 31), 2025 to LocalDate.of(2025, 4, 20),
            2026 to LocalDate.of(2026, 4, 5), 2027 to LocalDate.of(2027, 3, 28), 2038 to LocalDate.of(2038, 4, 25),
            2100 to LocalDate.of(2100, 3, 28),
        )
        for ((year, date) in expected) assertEquals(date, SwissHolidays.easter(year), "$year")
    }

    @Test
    fun `Feiertage nach Kanton`() {
        assertEquals(
            listOf(
                "2026-01-01:Neujahr", "2026-01-02:Berchtoldstag", "2026-04-03:Karfreitag", "2026-04-05:Ostern",
                "2026-04-06:Ostermontag", "2026-05-01:Tag der Arbeit", "2026-05-14:Auffahrt", "2026-05-24:Pfingsten",
                "2026-05-25:Pfingstmontag", "2026-08-01:Bundesfeiertag", "2026-09-20:Bettag", "2026-12-25:Weihnachten",
                "2026-12-26:Stephanstag",
            ),
            names(Canton.ZH, 2026),
        )
        val ticino = SwissHolidays.of(Canton.TI, 2026).map { it.kind }
        assertFalse(SwissHoliday.GOOD_FRIDAY in ticino)
        assertTrue(SwissHoliday.PETER_PAUL in ticino && SwissHoliday.CORPUS_CHRISTI in ticino)
        // Genf: Genfer Bettag (Donnerstag nach dem ersten Sonntag im September) und 31. Dezember.
        val geneva = names(Canton.GE, 2026)
        assertTrue("2026-09-10:Genfer Bettag" in geneva && "2026-12-31:Wiederherstellung der Republik" in geneva)
        assertFalse(geneva.any { it.endsWith("Stephanstag") || it.endsWith(":Bettag") })
        assertTrue("2026-09-21:Bettagsmontag" in names(Canton.VD, 2026))
        // Näfelser Fahrt: 2026 wäre der erste Donnerstag der Gründonnerstag, also eine Woche später.
        assertTrue("2026-04-09:Näfelser Fahrt" in names(Canton.GL, 2026))
        assertTrue("2025-04-03:Näfelser Fahrt" in names(Canton.GL, 2025))
        // Neuenburg: Berchtolds- und Stephanstag nur am Montag.
        assertTrue("2023-01-02:Berchtoldstag" in names(Canton.NE, 2023))
        assertFalse(names(Canton.NE, 2026).any { it.endsWith("Berchtoldstag") || it.endsWith("Stephanstag") })
        assertTrue("2022-12-26:Stephanstag" in names(Canton.NE, 2022))
        // Uri: Stephanstag nicht am Montag oder Freitag.
        assertTrue("2026-12-26:Stephanstag" in names(Canton.UR, 2026))
        assertFalse("2025-12-26:Stephanstag" in names(Canton.UR, 2025))
        for (canton in Canton.entries) {
            val all = SwissHolidays.of(canton, 2026)
            assertTrue(all.any { it.kind == SwissHoliday.NEW_YEAR } && all.any { it.kind == SwissHoliday.CHRISTMAS }, canton.name)
            assertEquals(all.sortedBy { it.date }, all)
        }
        assertEquals(emptyMap(), SwissHolidays.between(null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
        val span = SwissHolidays.between(Canton.BE, LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 5))
        assertEquals(listOf("2026-12-25", "2026-12-26", "2027-01-01", "2027-01-02"), span.keys.map { it.toString() })
    }

    @Test
    fun `Soll nach Pensum und Feiertagen`() {
        assertEquals(504, WorkTime.dailyTargetMinutes(PlanRules.DEFAULT_WEEK_MINUTES, 100))
        assertEquals(403, WorkTime.dailyTargetMinutes(PlanRules.DEFAULT_WEEK_MINUTES, 80))
        val october = YearMonth.of(2026, 10) // 22 Werktage, ohne Feiertage in Zürich
        var state = PlanState.EMPTY.set(PlanKeys.member(anna), "Anna").set(PlanKeys.member(ben), "Ben")
            .set(PlanKeys.setting(PlanRules.CANTON), "ZH")
        assertNull(WorkTime.balance(state, anna, october).targetMinutes) // ohne Pensum kein Soll
        state = state.set(PlanKeys.pensum(anna), "100").set(PlanKeys.pensum(ben), "80")
        assertEquals(22 * 504, WorkTime.balance(state, anna, october).targetMinutes)
        assertEquals(22 * 403, WorkTime.balance(state, ben, october).targetMinutes)
        // Mai 2026 in Zürich: 21 Werktage, davon 3 Feiertage (1. Mai, Auffahrt, Pfingstmontag).
        assertEquals(18 * 504, WorkTime.balance(state, anna, YearMonth.of(2026, 5)).targetMinutes)
        // Andere Wochenstunden des Teams.
        assertEquals(22 * 480, WorkTime.balance(state.set(PlanKeys.setting(PlanRules.HOURS), "2400"), anna, october).targetMinutes)

        // Dienste: 10 × Früh (je 8 h) und 2 Tage Urlaub ohne Anrechnung → Tagessoll angerechnet.
        for (day in listOf(1, 2, 5, 6, 7, 8, 9, 12, 13, 14)) state = state.set(PlanKeys.shift(anna, october.atDay(day)), "F")
        state = state.set(PlanKeys.shift(anna, october.atDay(15)), "U").set(PlanKeys.shift(anna, october.atDay(17)), "U") // Do und Sa
        val balance = WorkTime.balance(state, anna, october)
        assertEquals(10 * 480 + 504, balance.plannedMinutes) // Urlaub am Samstag zählt nicht
        assertEquals(10 * 480 + 504 - 22 * 504, balance.deltaMinutes)

        // Mit fester Anrechnung der Schichtart gilt diese.
        val vacation = ShiftType("0000000f", "FE", "Ferien", kind = ShiftKind.ABSENCE, color = 4, creditMinutes = 300)
        state = state.set(PlanKeys.shiftType(vacation.id), ShiftTypes.encode(vacation)).set(PlanKeys.shift(anna, october.atDay(16)), vacation.id)
        assertEquals(10 * 480 + 504 + 300, WorkTime.balance(state, anna, october).plannedMinutes)
    }

    @Test
    fun `Saldo seit Jahresbeginn zaehlt nur Monate mit Eintraegen`() {
        var state = PlanState.EMPTY.set(PlanKeys.member(anna), "Anna").set(PlanKeys.pensum(anna), "100")
        assertEquals(0, WorkTime.yearToDateDelta(state, anna, YearMonth.of(2026, 10)))
        state = state.set(PlanKeys.shift(anna, LocalDate.of(2026, 9, 1)), "F")
        val september = WorkTime.balance(state, anna, YearMonth.of(2026, 9))
        assertEquals(september.deltaMinutes, WorkTime.yearToDateDelta(state, anna, YearMonth.of(2026, 10)))
        assertNull(WorkTime.yearToDateDelta(state.set(PlanKeys.pensum(anna), ""), anna, YearMonth.of(2026, 10)))
    }

    @Test
    fun `Feiertage und abgegebene Dienste im Wochenmodell`() {
        val christmasWeek = WeekId.of(LocalDate.of(2026, 12, 21))
        var state = PlanState.EMPTY.set(PlanKeys.member(anna), "Anna")
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 12, 23)), "F")
            .set(PlanKeys.offer(anna, LocalDate.of(2026, 12, 23)), "F")
        assertTrue(WeekModel.build(state, christmasWeek, LocalDate.of(2026, 12, 21)).days.all { it.holiday == null })
        state = state.set(PlanKeys.setting(PlanRules.CANTON), "ZH")
        val model = WeekModel.build(state, christmasWeek, LocalDate.of(2026, 12, 21))
        assertEquals(listOf("Weihnachten", "Stephanstag"), model.days.mapNotNull { it.holiday?.name })
        assertTrue(model.rows.single().cells[2].offered)
        // Ein Angebot für einen Dienst, den die Person nicht mehr hat, zählt nicht.
        val changed = state.set(PlanKeys.shift(anna, LocalDate.of(2026, 12, 23)), "S")
        assertFalse(WeekModel.build(changed, christmasWeek, LocalDate.of(2026, 12, 21)).rows.single().cells[2].offered)
        val mine = MyShiftsModel.build(state.set(PlanKeys.pensum(anna), "100"), anna, LocalDate.of(2026, 12, 21))!!
        assertEquals(LocalDate.of(2026, 12, 25), mine.holidays.keys.first())
        assertEquals(WorkTime.balance(state.set(PlanKeys.pensum(anna), "100"), anna, YearMonth.of(2026, 12)), mine.monthBalance)
    }

    @Test
    fun `Nachtdienste und Kennzahlen`() {
        assertTrue(NightWork.isNight(ShiftTypes.default("N")))
        assertFalse(NightWork.isNight(ShiftTypes.default("S")))
        assertFalse(NightWork.isNight(ShiftTypes.default("F")))
        assertEquals(5 * 60, NightWork.minutes(ShiftType("0000000a", "A", "Abend", LocalTime.of(20, 0), LocalTime.of(4, 0), color = 1)))
        assertEquals(60, NightWork.minutes(ShiftType("0000000b", "B", "Spät", LocalTime.of(16, 0), LocalTime.of(0, 0), color = 1)))
        assertEquals(60, NightWork.minutes(ShiftType("0000000c", "C", "Früh", LocalTime.of(5, 0), LocalTime.of(13, 0), color = 1)))
        assertEquals(0, NightWork.minutes(ShiftTypes.default("U")!!))

        val state = PlanState.EMPTY.set(PlanKeys.member(anna), "Anna").set(PlanKeys.member(ben), "Ben")
            .set(PlanKeys.setting(PlanRules.CANTON), "ZH")
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 12, 25)), "N") // Freitag, Feiertag, Nacht
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 12, 26)), "F") // Samstag, Feiertag
            .set(PlanKeys.shift(anna, LocalDate.of(2026, 12, 28)), "X")
            .set(PlanKeys.shift(ben, LocalDate.of(2026, 12, 28)), "S")
            .set(PlanKeys.wish(ben, LocalDate.of(2026, 12, 28)), Wish.DAY_OFF.code) // nicht erfüllt
            .set(PlanKeys.wish(ben, LocalDate.of(2026, 12, 29)), Wish.WORK.code) // offen
        val stats = PlanStats.of(state, YearMonth.of(2026, 12))
        val a = stats.members.first { it.member.id == anna }
        assertEquals(2, a.workShifts)
        assertEquals(1, a.weekendShifts)
        assertEquals(1, a.nightShifts)
        assertEquals(2, a.holidayShifts)
        val b = stats.members.first { it.member.id == ben }
        assertEquals(2, b.wishes)
        assertEquals(0, b.wishesFulfilled)
        assertEquals(1, b.wishesUnmet)
        assertEquals(0.5, stats.averageWeekend)
        assertEquals(setOf(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26)), stats.holidays.keys)
    }
}
