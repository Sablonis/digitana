package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.EntryValidator
import ch.digitana.dienstplan.core.crdt.EntryValidator.Problem
import ch.digitana.dienstplan.core.crdt.PlanAccess
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanLock
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.WeekId
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ruhezeit zwischen Diensten und Soll-Besetzung. */
class PlanRulesTest {

    private val dev = "00000000000000aa"
    private val anna = "000000000000000a"
    private val ben = "000000000000000b"
    private var ts = 1L
    private val week = WeekId(2026, 41) // Montag, 5. Oktober 2026
    private fun day(i: Int): LocalDate = week.monday.plusDays(i.toLong())

    private fun PlanState.set(key: String, value: String) = withEntry(key, Entry(value, ts++, dev))

    private val base = PlanState.EMPTY
        .set(PlanKeys.member(anna), "Anna")
        .set(PlanKeys.member(ben), "Ben")

    private fun PlanState.shift(memberId: String, i: Int, typeId: String) = set(PlanKeys.shift(memberId, day(i)), typeId)

    private fun restBefore(state: PlanState, i: Int, minimum: Int = state.restMinutes): RestIssue? =
        RestRules.issueBefore(state, state.shiftTypes, anna, day(i), state.shift(anna, day(i)), minimum)

    @Test
    fun `Formate der Regeln`() {
        val team = Buckets.TEAM
        fun check(key: String, value: String, bucket: String = team) = EntryValidator.check(bucket, key, Entry(value, 5, dev), 1_000)
        assertEquals(PlanKey.Setting("rest"), PlanKeys.parse("c|rest"))
        assertEquals(PlanKey.Target("F"), PlanKeys.parse("b|F"))
        assertEquals(PlanKey.Target("0a1b2c3d"), PlanKeys.parse("b|0a1b2c3d"))
        for (key in listOf("c|", "c|Rest", "c|maxdays", "b|", "b|Q", "b|0A1B2C3D", "b|0a1b2c3", "B|F")) assertNull(PlanKeys.parse(key), key)
        for (value in listOf("", "0", "480", "660", "960")) assertNull(check("c|rest", value), value)
        for (value in listOf("961", "-1", "011", "11h", " 660", "6600")) assertEquals(Problem.VALUE, check("c|rest", value), value)
        for (value in listOf("", "3,3,3,3,3,2,2", "0,0,0,0,0,0,0", "99,0,1,0,0,0,10")) assertNull(check("b|F", value), value)
        for (value in listOf("3,3,3", "3,3,3,3,3,2,100", "03,3,3,3,3,2,2", "3, 3,3,3,3,2,2", "3,3,3,3,3,2,2,", "a,3,3,3,3,2,2")) {
            assertEquals(Problem.VALUE, check("b|F", value), value)
        }
        assertEquals(Problem.WRONG_BUCKET, check("c|rest", "660", week.bucketName))
        assertEquals(Problem.WRONG_BUCKET, check("b|F", "1,1,1,1,1,1,1", week.bucketName))
        assertEquals(listOf(3, 3, 3, 3, 3, 2, 2), PlanRules.decodeTargets(PlanRules.encodeTargets(listOf(3, 3, 3, 3, 3, 2, 2))))
    }

    @Test
    fun `Ruhezeit zwischen Diensten, auch ueber Mitternacht`() {
        // Spät (14–22) am Montag, Früh (6–14) am Dienstag: nur 8 Stunden.
        var state = base.shift(anna, 0, "S").shift(anna, 1, "F")
        assertEquals(PlanRules.DEFAULT_REST_MINUTES, state.restMinutes)
        val issue = assertNotNull(restBefore(state, 1))
        assertEquals(8 * 60, issue.restMinutes)
        assertEquals(day(0), issue.otherDate)
        assertEquals("S", issue.other.id)
        assertTrue(issue.otherBefore)
        assertNull(restBefore(state, 0))

        // Nacht (22–6) und danach Früh: keine Ruhe; danach Spät: 8 Stunden.
        state = base.shift(anna, 0, "N").shift(anna, 1, "F")
        assertEquals(0, restBefore(state, 1)?.restMinutes)
        state = base.shift(anna, 0, "N").shift(anna, 1, "S")
        assertEquals(8 * 60, restBefore(state, 1)?.restMinutes)

        // Früh auf Früh (16 h) und Spät, frei, Früh (32 h) sind in Ordnung.
        assertNull(restBefore(base.shift(anna, 0, "F").shift(anna, 1, "F"), 1))
        assertNull(restBefore(base.shift(anna, 0, "S").shift(anna, 1, "X").shift(anna, 2, "F"), 2))

        // Langer Dienst am Vorvortag: 20–19 Uhr (23 h), dann ein freier Tag, dann 5 Uhr.
        val long = ShiftType("0000000a", "L", "Lang", LocalTime.of(20, 0), LocalTime.of(19, 0), color = 5)
        val early = ShiftType("0000000b", "E", "Frühfrüh", LocalTime.of(5, 0), LocalTime.of(13, 0), color = 6)
        state = base.set(PlanKeys.shiftType(long.id), ShiftTypes.encode(long)).set(PlanKeys.shiftType(early.id), ShiftTypes.encode(early))
            .shift(anna, 0, long.id).shift(anna, 2, early.id)
        val fromTwoDaysBefore = assertNotNull(restBefore(state, 2))
        assertEquals(10 * 60, fromTwoDaysBefore.restMinutes)
        assertEquals(day(0), fromTwoDaysBefore.otherDate)

        // Danach: Spät heute würde vor dem Früh morgen zu knapp.
        state = base.shift(anna, 1, "F")
        val after = assertNotNull(RestRules.issueFor(state, state.shiftTypes, anna, day(0), "S", state.restMinutes))
        assertFalse(after.otherBefore)
        assertEquals(day(1), after.otherDate)
        assertNull(RestRules.issueFor(state, state.shiftTypes, anna, day(0), "F", state.restMinutes))
        assertNull(RestRules.issueFor(state, state.shiftTypes, anna, day(0), "X", state.restMinutes))
    }

    @Test
    fun `eigene Grenze, abgeschaltet und im Wochenmodell`() {
        val middle = ShiftType("0000000c", "M", "Mittel", LocalTime.of(8, 0), LocalTime.of(16, 0), color = 7)
        val withMiddle = base.set(PlanKeys.shiftType(middle.id), ShiftTypes.encode(middle))
            .shift(anna, 0, "S").shift(anna, 1, middle.id) // 10 Stunden Ruhe
        assertEquals(10 * 60, restBefore(withMiddle, 1)?.restMinutes)
        val nine = withMiddle.set(PlanKeys.setting(PlanRules.REST), "540")
        assertEquals(540, nine.restMinutes)
        assertNull(restBefore(nine, 1))
        val off = withMiddle.set(PlanKeys.setting(PlanRules.REST), "0")
        assertNull(restBefore(off, 1))

        val model = WeekModel.build(withMiddle.shift(ben, 2, "N").shift(ben, 3, "F"), week, day(0))
        val annaRow = model.rows.first { it.member.id == anna }
        assertNotNull(annaRow.cells[1].rest)
        assertNull(annaRow.cells[0].rest)
        assertEquals(2, model.restIssueCount)
        assertEquals(0, WeekModel.build(off, week, day(0)).restIssueCount)
    }

    @Test
    fun `Soll-Besetzung pro Wochentag`() {
        val state = base
            .set(PlanKeys.target("F"), "2,2,2,2,2,1,1")
            .set(PlanKeys.target("N"), "0,0,0,0,0,0,0") // nur Nullen = kein Soll
            .shift(anna, 0, "F").shift(ben, 0, "F") // Montag: 2 von 2
            .shift(anna, 1, "F") // Dienstag: 1 von 2
            .shift(anna, 5, "F").shift(ben, 5, "F") // Samstag: 2 von 1
        assertEquals(mapOf("F" to listOf(2, 2, 2, 2, 2, 1, 1)), state.targets)
        val model = WeekModel.build(state, week, day(0))
        val monday = model.coverage[0]
        assertTrue(monday.hasTargets)
        assertEquals(2, monday.target("F"))
        assertEquals(0, monday.target("S"))
        assertEquals(0, monday.shortfall)
        val tuesday = model.coverage[1]
        assertEquals(1, tuesday.shortfall)
        assertEquals(listOf("F"), tuesday.understaffed.map { it.id })
        assertEquals(2, tuesday.totalTarget)
        val saturday = model.coverage[5]
        assertEquals(1, saturday.target("F"))
        assertEquals(0, saturday.shortfall) // mehr als das Soll ist kein Mangel
        // Mittwoch bis Freitag fehlen je 2, Sonntag 1: vier Tage unter dem Soll (plus Dienstag).
        assertEquals(5, model.understaffedCount)
        assertFalse(WeekModel.build(base, week, day(0)).coverage[0].hasTargets)
    }

    @Test
    fun `Regeln sind waehrend einer Sperre geschuetzt`() {
        val admin = "00000000000000bb"
        val member = "00000000000000cc"
        val access = PlanAccess(PlanLock(listOf(PlanLock.Segment(null, 1000)), setOf(admin)), setOf(admin))
        val rest = PlanKeys.setting(PlanRules.REST)
        val target = PlanKeys.target("F")
        assertFalse(access.allows(rest, Entry("540", 2000, member)))
        assertTrue(access.allows(rest, Entry("540", 900, member)))
        assertFalse(access.allows(target, Entry("1,1,1,1,1,1,1", 2000, member)))
        assertTrue(access.allows(target, Entry("1,1,1,1,1,1,1", 2000, admin)))
        assertFalse(access.mayWrite(member, rest, "540"))
        assertTrue(access.mayWrite(admin, target, ""))
    }
}
