package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.crdt.EntryValidator.Problem
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Schichtarten, Notizen, Wünsche und Rhythmen: Format und strenge Prüfung. */
class PlanExtensionsTest {

    private val now = 1_790_000_000_000L
    private val id = "0123456789abcdef"
    private val device = "fedcba9876543210"
    private val week = WeekId.of(LocalDate.of(2026, 9, 21)).bucketName // 2026-W39

    private fun check(bucket: String, key: String, value: String) =
        EntryValidator.check(bucket, key, Entry(value, now, device), now)

    @Test
    fun `Schichtart hin und zurueck`() {
        val type = ShiftType(
            id = "0a1b2c3d", code = "TD", name = "Tagdienst", start = LocalTime.of(7, 0), end = LocalTime.of(16, 30),
            breakMinutes = 30, kind = ShiftKind.WORK, color = 7, creditMinutes = 0, archived = true,
        )
        val value = ShiftTypes.encode(type)
        assertEquals("v1|TD|Tagdienst|07:00|16:30|30|W|7|0|a", value)
        assertEquals(type, ShiftTypes.decode("0a1b2c3d", value))
        assertNull(check(Buckets.TEAM, "s|0a1b2c3d", value))
        for (default in ShiftTypes.DEFAULTS) {
            assertEquals(default, ShiftTypes.decode(default.id, ShiftTypes.encode(default)))
        }
    }

    @Test
    fun `Stunden aus Zeiten, Pause und Anrechnung`() {
        val early = ShiftTypes.default("F")!!
        assertEquals(8 * 60, early.paidMinutes)
        assertEquals(8 * 60, ShiftTypes.default("N")!!.paidMinutes) // 22–6 Uhr über Mitternacht
        assertEquals(0, ShiftTypes.default("X")!!.paidMinutes)
        val day = ShiftType("0a1b2c3d", "T", "Tag", LocalTime.of(7, 0), LocalTime.of(16, 30), breakMinutes = 30)
        assertEquals(9 * 60, day.paidMinutes)
        val full = ShiftType("0a1b2c3e", "24", "Pikett", LocalTime.of(8, 0), LocalTime.of(8, 0))
        assertEquals(24 * 60, full.durationMinutes) // gleiche Zeiten = 24 h
        val vacation = ShiftType("0a1b2c3f", "FE", "Ferien", kind = ShiftKind.ABSENCE, creditMinutes = 504)
        assertEquals(504, vacation.paidMinutes)
        assertFalse(vacation.countsForCoverage)
        val tooLongBreak = ShiftType("0a1b2c40", "K", "Kurz", LocalTime.of(8, 0), LocalTime.of(9, 0), breakMinutes = 90)
        assertEquals(0, tooLongBreak.paidMinutes)
    }

    @Test
    fun `kaputte Schichtarten werden verworfen`() {
        val good = "v1|TD|Tagdienst|07:00|16:30|30|W|7|0|"
        assertNull(check(Buckets.TEAM, "s|0a1b2c3d", good))
        val bad = listOf(
            "v2|TD|Tagdienst|07:00|16:30|30|W|7|0|", // unbekannte Version
            "v1|TD|Tagdienst|07:00|16:30|30|W|7|0", // Feld fehlt
            "v1|TD|Tagdienst|07:00|16:30|30|W|7|0||", // Feld zu viel
            "v1|td|Tagdienst|07:00|16:30|30|W|7|0|", // Kleinbuchstaben
            "v1|TDXX|Tagdienst|07:00|16:30|30|W|7|0|", // Kürzel zu lang
            "v1||Tagdienst|07:00|16:30|30|W|7|0|", // Kürzel leer
            "v1|TD||07:00|16:30|30|W|7|0|", // Name leer
            "v1|TD|${"a".repeat(31)}|07:00|16:30|30|W|7|0|", // Name zu lang
            "v1|TD|Tag\u0000dienst|07:00|16:30|30|W|7|0|", // Steuerzeichen
            "v1|TD|Tag‮dienst|07:00|16:30|30|W|7|0|", // Bidi
            "v1|TD|Tagdienst|7:00|16:30|30|W|7|0|", // Zeitformat
            "v1|TD|Tagdienst|24:00|16:30|30|W|7|0|", // Stunde
            "v1|TD|Tagdienst|07:60|16:30|30|W|7|0|", // Minute
            "v1|TD|Tagdienst|07:00||30|W|7|0|", // nur Beginn
            "v1|TD|Tagdienst||16:30|30|W|7|0|", // nur Ende
            "v1|TD|Tagdienst|07:00|16:30|030|W|7|0|", // führende Null
            "v1|TD|Tagdienst|07:00|16:30|-5|W|7|0|", // negativ
            "v1|TD|Tagdienst|07:00|16:30|481|W|7|0|", // Pause zu lang
            "v1|TD|Tagdienst|07:00|16:30|30|Q|7|0|", // Art
            "v1|TD|Tagdienst|07:00|16:30|30|W|12|0|", // Farbe
            "v1|TD|Tagdienst|07:00|16:30|30|W|7|1441|", // Anrechnung
            "v1|TD|Tagdienst|07:00|16:30|30|W|7|0|x", // Flag
            "v1|TD|Tagdienst|07:00|16:30|30|W|7|0|a ", // Leerzeichen
            "v1|TD|Tagdienst|07:00|16:30|30|W|7|0|" + "a".repeat(300), // zu lang
        )
        for (value in bad) assertEquals(Problem.VALUE, check(Buckets.TEAM, "s|0a1b2c3d", value), value)
        assertNull(check(Buckets.TEAM, "s|F", "")) // zurück zum Standard
        assertEquals(Problem.WRONG_BUCKET, check(week, "s|0a1b2c3d", good))
    }

    @Test
    fun `Schluessel der neuen Eintraege`() {
        assertEquals(PlanKey.ShiftType("F"), PlanKeys.parse("s|F"))
        assertEquals(PlanKey.ShiftType("0a1b2c3d"), PlanKeys.parse("s|0a1b2c3d"))
        assertEquals(PlanKey.Pattern("0a1b2c3d"), PlanKeys.parse("r|0a1b2c3d"))
        assertEquals(PlanKey.DayNote(LocalDate.of(2026, 9, 21)), PlanKeys.parse("n|2026-09-21"))
        assertEquals(PlanKey.MemberNote(id, LocalDate.of(2026, 9, 21)), PlanKeys.parse("n|$id|2026-09-21"))
        assertEquals(PlanKey.Wish(id, LocalDate.of(2026, 9, 21)), PlanKeys.parse("w|$id|2026-09-21"))
        val bad = listOf(
            "s|Q", "s|f", "s|FF", "s|0A1B2C3D", "s|0a1b2c3", "s|0a1b2c3d0", "s|", "r|F", "r|0a1b2c3", "n|", "n|2026-02-30",
            "n|1999-12-31", "n|$id", "n|$id|", "n|$id|2026-13-01", "w|$id", "w|2026-09-21", "w|$id|2101-01-01", "W|$id|2026-09-21",
        )
        for (key in bad) assertNull(PlanKeys.parse(key), key)
        assertEquals(Buckets.TEAM, Buckets.forKey(PlanKey.ShiftType("F")))
        assertEquals(Buckets.TEAM, Buckets.forKey(PlanKey.Pattern("0a1b2c3d")))
        assertEquals(week, Buckets.forKey(PlanKey.DayNote(LocalDate.of(2026, 9, 27))))
        assertEquals(week, Buckets.forKey(PlanKey.MemberNote(id, LocalDate.of(2026, 9, 21))))
        assertEquals(week, Buckets.forKey(PlanKey.Wish(id, LocalDate.of(2026, 9, 21))))
        assertEquals(PlanKey.DeviceOwner(id), PlanKeys.parse("u|$id"))
        assertEquals(Buckets.TEAM, Buckets.forKey(PlanKey.DeviceOwner(id)))
        for (key in listOf("u|", "u|ABCDEF0123456789", "u|$id|2026-09-21", "U|$id")) assertNull(PlanKeys.parse(key), key)
    }

    @Test
    fun `Zuordnung von Geraeten zu Personen`() {
        assertNull(check(Buckets.TEAM, "u|$id", "0123456789abcdef"))
        assertNull(check(Buckets.TEAM, "u|$id", ""))
        for (value in listOf("Anna", "0123456789ABCDEF", "0123456789abcde")) {
            assertEquals(Problem.VALUE, check(Buckets.TEAM, "u|$id", value), value)
        }
        assertEquals(Problem.WRONG_BUCKET, check(week, "u|$id", "0123456789abcdef"))
    }

    @Test
    fun `Felder verweisen auf gueltige Schichtart-IDs`() {
        assertNull(check(week, "z|$id|2026-09-21", "0a1b2c3d")) // eigene Art, auch wenn noch unbekannt
        for (value in listOf("0A1B2C3D", "0a1b2c3", "0a1b2c3d0", "Q", "f", "F ", "0a1b2c3g")) {
            assertEquals(Problem.VALUE, check(week, "z|$id|2026-09-21", value), value)
        }
    }

    @Test
    fun `Notizen und Wuensche`() {
        assertNull(check(week, "n|2026-09-21", "Teamsitzung 14 Uhr | Raum 2"))
        assertNull(check(week, "n|$id|2026-09-21", "a".repeat(200)))
        assertNull(check(week, "n|$id|2026-09-21", ""))
        assertEquals(Problem.VALUE, check(week, "n|$id|2026-09-21", "a".repeat(201)))
        for (note in listOf("Zeile\nZeile", "\u001b[31m", "   ", "⁦x", "a​")) {
            assertEquals(Problem.VALUE, check(week, "n|2026-09-21", note), note)
        }
        for (wish in Wish.SIMPLE) assertNull(check(week, "w|$id|2026-09-21", wish.code))
        assertNull(check(week, "w|$id|2026-09-21", "WA:F"))
        assertNull(check(week, "w|$id|2026-09-21", "WA:0a1b2c3d"))
        assertEquals(Wish.shift("0a1b2c3d"), Wish.fromCode("WA:0a1b2c3d"))
        assertEquals("WA:N", Wish.shift("N").code)
        for (value in listOf("wf", "WF ", "XX", "Wunschfrei", "F", "WA:", "WA:Q", "WF:F", "WA:0A1B2C3D", "WA:F:S", "WA:0a1b2c3d0")) {
            assertEquals(Problem.VALUE, check(week, "w|$id|2026-09-21", value), value)
        }
        assertEquals(Problem.WRONG_BUCKET, check("2026-W40", "w|$id|2026-09-21", "WF"))
        assertEquals(Problem.WRONG_BUCKET, check(Buckets.TEAM, "n|2026-09-21", "x"))
    }

    @Test
    fun `Rhythmen`() {
        val pattern = ShiftPattern("0a1b2c3d", "Frühwoche", listOf("F", "F", "F", "F", "F", null, null))
        val value = ShiftPatterns.encode(pattern)
        assertEquals("v1|Frühwoche|F,F,F,F,F,,", value)
        assertEquals(pattern, ShiftPatterns.decode("0a1b2c3d", value))
        assertNull(check(Buckets.TEAM, "r|0a1b2c3d", value))
        val long = ShiftPattern("0a1b2c3d", "Acht Wochen", List(56) { if (it % 2 == 0) "0a1b2c3e" else null })
        assertEquals(long, ShiftPatterns.decode("0a1b2c3d", ShiftPatterns.encode(long)))
        val bad = listOf(
            "v1|Frühwoche|F,F,F", // keine ganze Woche
            "v1|Frühwoche|" + List(63) { "F" }.joinToString(","), // länger als 8 Wochen
            "v1||F,F,F,F,F,,", // ohne Namen
            "v1|Früh|woche|F,F,F,F,F,,", // Trennzeichen im Namen
            "v1|Frühwoche|F,F,F,F,Q,,", // ungültige Art
            "v1|Frühwoche|F,F,F,F,F,, ", // Leerzeichen
            "v0|Frühwoche|F,F,F,F,F,,",
        )
        for (v in bad) assertEquals(Problem.VALUE, check(Buckets.TEAM, "r|0a1b2c3d", v), v)
    }

    @Test
    fun `eigene Schichtarten ueberschreiben Standardarten`() {
        var ts = 1L
        fun PlanState.set(key: String, value: String) = withEntry(key, Entry(value, ts++, device))
        val custom = ShiftType("0a1b2c3d", "T", "Tag", LocalTime.of(7, 0), LocalTime.of(16, 0), color = 5)
        val changedEarly = ShiftTypes.default("F")!!.copy(name = "Frühdienst", start = LocalTime.of(5, 30), end = LocalTime.of(13, 30))
        val hiddenNight = ShiftTypes.default("N")!!.copy(archived = true)
        val state = PlanState.EMPTY
            .set(PlanKeys.shiftType(custom.id), ShiftTypes.encode(custom))
            .set(PlanKeys.shiftType("F"), ShiftTypes.encode(changedEarly))
            .set(PlanKeys.shiftType("N"), ShiftTypes.encode(hiddenNight))
            .set(PlanKeys.shiftType("S"), ShiftTypes.encode(ShiftTypes.default("S")!!.copy(name = "x"))).set(PlanKeys.shiftType("S"), "")
        val types = state.shiftTypes
        assertEquals(listOf("F", "T", "S", "N", "X", "U"), types.all.map { it.code }) // Arbeit nach Beginn, dann frei, abwesend
        assertEquals(listOf("F", "T", "S", "X", "U"), types.active.map { it.code })
        assertEquals("Frühdienst", types["F"]?.name)
        assertEquals("Spät", types["S"]?.name) // zurückgesetzt
        assertNotNull(types["N"]) // archiviert, aber weiter bekannt
        assertNull(types["0a1b2c3e"])
        assertTrue(types.contains("0a1b2c3d"))
    }
}
