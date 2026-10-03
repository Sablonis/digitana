package ch.digitana.dienstplan.core.crdt

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlanLockTest {

    private val admin = "00000000000000aa"
    private val other = "00000000000000bb"
    private val member = "1111111111111111"
    private val oct31 = LocalDate.of(2026, 10, 31)
    private val nov30 = LocalDate.of(2026, 11, 30)

    @Test
    fun `Sperre wird in Abschnitten erweitert, verkuerzt und behaelt alte Zeitpunkte`() {
        val first = PlanLocks.lock(null, oct31, 100, setOf(admin), admin)
        assertEquals(listOf(PlanLock.Segment(oct31, 100)), first.segments)
        assertEquals(oct31, first.until)

        // Erweitern: Oktober bleibt ab 100 gesperrt, November erst ab 200.
        val longer = PlanLocks.lock(first, nov30, 200, setOf(admin), admin)
        assertEquals(listOf(PlanLock.Segment(oct31, 100), PlanLock.Segment(nov30, 200)), longer.segments)
        assertEquals(100, longer.since)
        assertEquals(200, longer.changedAt)

        // Ganzer Plan, dann wieder kürzer: Die Zeitpunkte wandern mit.
        val whole = PlanLocks.lock(longer, null, 300, setOf(admin), admin)
        assertNull(whole.until)
        assertEquals(PlanLock.Segment(null, 300), whole.segments.last())
        val shorter = PlanLocks.lock(whole, LocalDate.of(2026, 11, 15), 400, setOf(admin), admin)
        assertEquals(listOf(PlanLock.Segment(oct31, 100), PlanLock.Segment(LocalDate.of(2026, 11, 15), 200)), shorter.segments)
        val oneDay = PlanLocks.lock(shorter, LocalDate.of(2026, 10, 1), 500, setOf(admin), admin)
        assertEquals(listOf(PlanLock.Segment(LocalDate.of(2026, 10, 1), 100)), oneDay.segments)

        // Nichts geändert: dieselbe Sperre (kein überflüssiger Commit).
        assertSame(longer, PlanLocks.lock(longer, nov30, 600, setOf(admin), other))
        assertSame(longer, PlanLocks.lock(longer, LocalDate.of(2026, 11, 30), 700, setOf(admin), admin))
    }

    @Test
    fun `viele Erweiterungen legen die aeltesten Abschnitte zusammen`() {
        var lock: PlanLock? = null
        var day = LocalDate.of(2026, 1, 31)
        for (i in 1..20) {
            lock = PlanLocks.lock(lock, day, i * 10L, setOf(admin), admin)
            day = day.plusMonths(1)
        }
        lock!!
        assertEquals(PlanLocks.MAX_SEGMENTS, lock.segments.size)
        // Der älteste verbleibende Abschnitt übernimmt den jüngeren Zeitpunkt der zusammengelegten.
        assertEquals(90, lock.segments.first().since)
        assertEquals(200, lock.changedAt)
    }

    @Test
    fun `neue Admins kommen dazu, fruehere bleiben`() {
        val lock = PlanLocks.lock(null, null, 100, setOf(admin), admin)
        val more = PlanLocks.withAdmins(lock, setOf(other))
        assertEquals(setOf(admin, other), more.admins)
        assertSame(more, PlanLocks.withAdmins(more, setOf(admin)))
        val relocked = PlanLocks.lock(more, oct31, 200, setOf(other), other)
        assertEquals(setOf(admin, other), relocked.admins)
    }

    @Test
    fun `Codec liest, was er schreibt, und haelt Fremdes fuer offen`() {
        val lock = PlanLock(listOf(PlanLock.Segment(oct31, 1_759_000_000_000), PlanLock.Segment(null, 1_759_100_000_000)), setOf(other, admin), admin)
        val text = PlanLockCodec.encode(lock)
        assertEquals(
            """{"v":1,"lock":{"seg":[{"until":"2026-10-31","since":1759000000000},{"since":1759100000000}],"admins":["00000000000000aa","00000000000000bb"],"by":"00000000000000aa"}}""",
            text,
        )
        assertEquals(lock, PlanLockCodec.decode(text))
        assertEquals("", PlanLockCodec.encode(null))
        listOf(
            "",
            "Team der Pflege",
            "{}",
            """{"v":2,"lock":{"seg":[{"since":5}],"admins":[]}}""",
            """{"v":"1","lock":{"seg":[{"since":5}],"admins":[]}}""",
            """{"v":1}""",
            """{"v":1,"lock":{"seg":[],"admins":[]}}""",
            """{"v":1,"lock":{"seg":[{"since":0}],"admins":[]}}""",
            """{"v":1,"lock":{"seg":[{"since":"5"}],"admins":[]}}""",
            """{"v":1,"lock":{"seg":[{"until":"2026-02-30","since":5}],"admins":[]}}""",
            """{"v":1,"lock":{"seg":[{"until":"1999-12-31","since":5}],"admins":[]}}""",
            """{"v":1,"lock":{"seg":[{"since":5},{"until":"2026-10-31","since":6}],"admins":[]}}""",
            """{"v":1,"lock":{"seg":[{"until":"2026-10-31","since":5},{"until":"2026-10-01","since":6}],"admins":[]}}""",
            """{"v":1,"lock":{"seg":[{"since":5}],"admins":["ABC"]}}""",
            """{"v":1,"lock":{"seg":[{"since":5}]}}""",
            """{"v":1,"lock":{"seg":[{"since":5}],"admins":[],"by":7}}""",
            """{"v":1,"lock":{"seg":[[[[[{"since":5}]]]]],"admins":[]}}""",
            "{\"v\":1,\"lock\":{\"seg\":[{\"since\":5}],\"admins\":[],\"x\":\"" + "a".repeat(5000) + "\"}}",
            """{"v":1,"lock":""",
        ).forEach { assertNull(PlanLockCodec.decode(it), it.take(60)) }
        // Unbekannte zusätzliche Felder stören nicht.
        assertEquals(
            PlanLock(listOf(PlanLock.Segment(null, 5)), emptySet()),
            PlanLockCodec.decode("""{"v":1,"note":"x","lock":{"seg":[{"since":5,"x":1}],"admins":[]}}"""),
        )
    }

    @Test
    fun `Regeln fuer Eintraege unter einer Sperre`() {
        val lock = PlanLock(listOf(PlanLock.Segment(oct31, 1000), PlanLock.Segment(nov30, 2000)), setOf(admin))
        val access = PlanAccess(lock, setOf(other))
        val anna = "0123456789abcdef"
        val octShift = PlanKeys.shift(anna, LocalDate.of(2026, 10, 5))
        val novShift = PlanKeys.shift(anna, LocalDate.of(2026, 11, 5))
        val decShift = PlanKeys.shift(anna, LocalDate.of(2026, 12, 5))

        // Vor dem Abschnitt eingetragen: gilt; danach nur von Admins (auch früheren aus der Sperre).
        assertTrue(access.allows(octShift, Entry("F", 1000, member)))
        assertFalse(access.allows(octShift, Entry("F", 1001, member)))
        assertTrue(access.allows(octShift, Entry("F", 5000, admin)))
        assertTrue(access.allows(octShift, Entry("F", 5000, other)))
        assertTrue(access.allows(novShift, Entry("F", 1500, member)))
        assertFalse(access.allows(novShift, Entry("F", 2500, member)))
        assertTrue(access.allows(decShift, Entry("F", 9000, member)))

        // Schichtarten und das Löschen von Personen gelten ab dem Beginn der Sperre.
        assertFalse(access.allows(PlanKeys.shiftType("F"), Entry("", 1500, member)))
        assertTrue(access.allows(PlanKeys.shiftType("F"), Entry("", 900, member)))
        assertFalse(access.allows(PlanKeys.member(anna), Entry("", 1500, member)))
        assertTrue(access.allows(PlanKeys.member(anna), Entry("Anna B.", 1500, member)))

        // Wünsche, Notizen und Rhythmen bleiben offen.
        assertTrue(access.allows(PlanKeys.wish(anna, LocalDate.of(2026, 10, 5)), Entry("WF", 9000, member)))
        assertTrue(access.allows(PlanKeys.memberNote(anna, LocalDate.of(2026, 10, 5)), Entry("Tausch", 9000, member)))
        assertTrue(access.allows(PlanKeys.dayNote(LocalDate.of(2026, 10, 5)), Entry("Sitzung", 9000, member)))
        assertTrue(access.allows(PlanKeys.pattern("0000abcd"), Entry("", 9000, member)))

        // Lokal: Nur aktuelle Admins dürfen Geschütztes ändern, ein früherer Admin nicht mehr.
        assertTrue(access.mayWrite(other, octShift, "S"))
        assertFalse(access.mayWrite(admin, octShift, "S"))
        assertFalse(access.mayWrite(member, octShift, ""))
        assertTrue(access.mayWrite(member, decShift, "S"))
        assertFalse(access.mayWrite(member, PlanKeys.member(anna), ""))
        assertTrue(access.mayWrite(member, PlanKeys.member(anna), "Anna"))
        assertFalse(access.mayEditShift(member, LocalDate.of(2026, 11, 30)))
        assertTrue(access.mayEditShift(member, LocalDate.of(2026, 12, 1)))
        assertFalse(access.mayEditShiftTypes(member))
        assertTrue(access.mayEditShiftTypes(other))
        assertTrue(PlanAccess.OPEN.mayWrite(member, octShift, ""))
        assertTrue(PlanAccess.OPEN.allows(octShift, Entry("F", 5000, member)))
    }
}
