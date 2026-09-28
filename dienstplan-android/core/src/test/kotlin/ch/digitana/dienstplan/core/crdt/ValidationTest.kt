package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.crdt.EntryValidator.Problem
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Eingabeprüfung mit bösartigen und kaputten Daten. */
class ValidationTest {

    private val now = 1_790_000_000_000L
    private val id = "0123456789abcdef"
    private val device = "fedcba9876543210"
    private val week = WeekId.of(LocalDate.of(2026, 9, 21)).bucketName // 2026-W39

    private fun check(bucket: String, key: String, value: String, ts: Long = now, dev: String = device) =
        EntryValidator.check(bucket, key, Entry(value, ts, dev), now)

    @Test
    fun `gueltige Eintraege`() {
        assertNull(check(Buckets.TEAM, "m|$id", "Anna Muster"))
        assertNull(check(Buckets.TEAM, "m|$id", "")) // gelöscht
        assertNull(check(week, "z|$id|2026-09-21", "F"))
        assertNull(check(week, "z|$id|2026-09-27", "U"))
        assertNull(check(week, "z|$id|2026-09-22", "")) // geleert
        assertNull(check(Buckets.TEAM, "m|$id", "Zoë 😀 Ängström-Łukasz"))
    }

    @Test
    fun `Schluesselformat per Regex`() {
        val bad = listOf(
            "", "m|", "m|$id|x", "m|${id.uppercase()}", "m|0123456789abcde", "m|0123456789abcdef0",
            "M|$id", " m|$id", "m|$id ", "m|$id\n", "m|$id\u0000", "x|$id", "m||$id",
            "z|$id|2026-9-21", "z|$id|2026-09-21 ", "z|$id|2026-09-21T00:00", "z|$id|26-09-21",
            "z|$id|２０２６-09-21", "z|$id|2026–09–21", "z|$id", "z|$id|", "z|../../etc|2026-09-21",
            "m|" + "a".repeat(1000),
        )
        for (key in bad) {
            assertEquals(Problem.KEY_FORMAT, check(Buckets.TEAM, key, "Anna"), "Schlüssel '$key'")
        }
    }

    @Test
    fun `Datum muss existieren und zwischen 2000 und 2100 liegen`() {
        val invalidDates = listOf("2026-02-30", "2026-13-01", "2026-00-10", "2026-04-31", "2025-02-29", "1999-12-31", "2101-01-01", "0000-01-01")
        for (date in invalidDates) {
            assertEquals(Problem.KEY_FORMAT, check(week, "z|$id|$date", "F"), date)
        }
        assertNull(check("2024-W09", "z|$id|2024-02-29", "F")) // Schalttag
        assertNull(check("1999-W52", "z|$id|2000-01-01", "F"))
        assertNull(check("2100-W52", "z|$id|2100-12-31", "F"))
    }

    @Test
    fun `nur erlaubte Kuerzel`() {
        for (value in listOf("f", "Q", "FF", "F ", " F", "0", "null", "Früh", "\u0000", "N\u200b")) {
            assertEquals(Problem.VALUE, check(week, "z|$id|2026-09-21", value), "Kürzel '$value'")
        }
        for (value in listOf("F", "S", "N", "X", "U")) assertNull(check(week, "z|$id|2026-09-21", value))
    }

    @Test
    fun `Namen hoechstens 60 Zeichen ohne Steuerzeichen`() {
        assertNull(check(Buckets.TEAM, "m|$id", "a".repeat(60)))
        assertEquals(Problem.VALUE, check(Buckets.TEAM, "m|$id", "a".repeat(61)))
        assertNull(check(Buckets.TEAM, "m|$id", "😀".repeat(60))) // 60 Zeichen, 120 UTF-16-Einheiten
        assertEquals(Problem.VALUE, check(Buckets.TEAM, "m|$id", "😀".repeat(61)))
        val forbidden = listOf(
            "Anna\u0000", "An\nna", "An\rna", "Anna\t", "\u001b[31mAnna", "Anna\u007f", "Anna\u0085",
            "Anna\u2028", "Anna\u2029", "\u202eannA", "Anna\u2066", "An\u200bna", "\ufeffAnna",
            "\u200eAnna", "Anna\uffff", "   ", "\ud83d", "Anna\udc00", "Anna\ud83d",
        )
        for (name in forbidden) {
            assertEquals(Problem.VALUE, check(Buckets.TEAM, "m|$id", name), "Name ${name.map { it.code.toString(16) }}")
        }
    }

    @Test
    fun `Zeitstempel groesser 0 und hoechstens 24 Stunden in der Zukunft`() {
        val key = "m|$id"
        assertEquals(Problem.TIMESTAMP, check(Buckets.TEAM, key, "A", ts = 0))
        assertEquals(Problem.TIMESTAMP, check(Buckets.TEAM, key, "A", ts = -1))
        assertEquals(Problem.TIMESTAMP, check(Buckets.TEAM, key, "A", ts = Long.MIN_VALUE))
        assertEquals(Problem.TIMESTAMP, check(Buckets.TEAM, key, "A", ts = now + Limits.MAX_FUTURE_MILLIS + 1))
        assertEquals(Problem.TIMESTAMP, check(Buckets.TEAM, key, "A", ts = Long.MAX_VALUE))
        assertNull(check(Buckets.TEAM, key, "A", ts = now + Limits.MAX_FUTURE_MILLIS))
        assertNull(check(Buckets.TEAM, key, "A", ts = 1))
    }

    @Test
    fun `Geraete-ID genau 16 Hex-Kleinbuchstaben`() {
        for (dev in listOf("", "abc", device.uppercase(), device + "0", "g123456789abcdef", "0123456789abcde\n")) {
            assertEquals(Problem.DEVICE, check(Buckets.TEAM, "m|$id", "A", dev = dev), "Gerät '$dev'")
        }
    }

    @Test
    fun `jeder Eintrag muss zu seinem Bucket passen`() {
        assertEquals(Problem.WRONG_BUCKET, check(Buckets.TEAM, "z|$id|2026-09-21", "F"))
        assertEquals(Problem.WRONG_BUCKET, check(week, "m|$id", "Anna"))
        assertEquals(Problem.WRONG_BUCKET, check("2026-W40", "z|$id|2026-09-27", "F")) // Sonntag gehört zu W39
        assertEquals(Problem.WRONG_BUCKET, check("2026-W39", "z|$id|2026-09-28", "F")) // Montag gehört zu W40
    }

    @Test
    fun `Bucket-Namen werden streng geprueft`() {
        assertTrue(Buckets.isValid("team"))
        assertTrue(Buckets.isValid("2026-W01"))
        assertTrue(Buckets.isValid("2026-W53"))
        assertTrue(Buckets.isValid("2020-W53"))
        assertFalse(Buckets.isValid("2021-W53")) // 2021 hat nur 52 Wochen
        for (name in listOf("", "Team", "team ", "2026-W00", "2026-W54", "2026-w39", "2026W39", "1998-W52", "2101-W01", "26-W39", "2026-W3")) {
            assertFalse(Buckets.isValid(name), name)
        }
    }

    @Test
    fun `lokale Eingaben werden normalisiert`() {
        assertEquals("Anna Muster", Names.normalizeInput("  Anna \t  Muster \n"))
        assertEquals(3, Names.length("a😀b"))
    }
}
