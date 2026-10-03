package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Canton
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanAccess
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanLock
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.ShiftOffer
import ch.digitana.dienstplan.core.crdt.SwapRequest
import ch.digitana.dienstplan.core.crdt.SwapStatus
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.plan.PlanSuggestion
import ch.digitana.dienstplan.core.plan.RestRules
import ch.digitana.dienstplan.core.plan.ShiftTrades
import ch.digitana.dienstplan.core.plan.TradeProblem
import ch.digitana.dienstplan.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offene Dienste, Abgeben, Tauschen, Plan-Vorschlag, Rückgängig und Markierungen. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanTradesTest {

    private class MemoryPlanStore : PlanStore {
        var snapshot: PlanSnapshot? = null
        override fun load() = snapshot
        override fun save(snapshot: PlanSnapshot) {
            this.snapshot = PlanSnapshotCodec.decode(PlanSnapshotCodec.encode(snapshot))
        }
        override fun clear() {
            snapshot = null
        }
    }

    private var now = 1_790_000_000_000L
    private val store = MemoryPlanStore()
    private val me = "00000000000000aa"
    private val admin = "00000000000000bb"
    private val other = "00000000000000cc"
    private val monday = LocalDate.of(2026, 10, 5)

    private fun TestScope.repository(): PlanRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PlanRepository(store, backgroundScope, Clock { now }, dispatcher, saveDelayMillis = 100).apply { deviceId = me }
    }

    private fun lockedUntilEndOfOctober(since: Long) =
        PlanAccess(PlanLock(listOf(PlanLock.Segment(LocalDate.of(2026, 10, 31), since)), setOf(admin), admin), setOf(admin))

    @Test
    fun `offene Dienste uebernehmen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        repo.setTargets("F", listOf(2, 0, 0, 0, 0, 0, 0))
        repo.setShift(anna, monday, "F")
        val open = ShiftTrades.openShifts(repo.state.value, monday, monday.plusDays(6))
        assertEquals(1, open.size)
        assertEquals(1, open.single().missing)
        assertEquals("F", open.single().type.id)
        assertEquals(TradeProblem.BUSY, assertThrows<TradeException> { repo.claimOpenShift(anna, monday, "F") }.problem)
        repo.claimOpenShift(ben, monday, "F")
        assertEquals("F", repo.state.value.shift(ben, monday))
        assertTrue(ShiftTrades.openShifts(repo.state.value, monday, monday.plusDays(6)).isEmpty())
        // „Frei“ eingetragen zählt als frei.
        repo.setTargets("F", listOf(2, 1, 0, 0, 0, 0, 0))
        repo.setShift(ben, monday.plusDays(1), "X")
        assertNull(ShiftTrades.claimProblem(repo.state.value, ben, monday.plusDays(1)))
        repo.setShift(anna, monday.plusDays(1), "U")
        assertEquals(TradeProblem.BUSY, ShiftTrades.claimProblem(repo.state.value, anna, monday.plusDays(1)))
    }

    @Test
    fun `Dienst abgeben und uebernehmen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        val tuesday = monday.plusDays(1)
        repo.setDeviceOwner(anna)
        repo.setShift(anna, tuesday, "S")
        assertEquals(TradeProblem.STALE, assertThrows<TradeException> { repo.offerShift(anna, monday) }.problem)
        repo.offerShift(anna, tuesday)
        assertEquals(ShiftOffer("S"), repo.state.value.offer(anna, tuesday))
        val offer = repo.state.value.offers.single()
        assertEquals(TradeProblem.SELF, ShiftTrades.takeProblem(repo.state.value, offer, anna))
        assertEquals(TradeOutcome.DONE, repo.takeOffer(anna, tuesday, ben))
        val state = repo.state.value
        assertEquals("S", state.shift(ben, tuesday))
        assertNull(state.shift(anna, tuesday))
        assertTrue(state.offers.isEmpty())
        // Ein zweites Mal geht nicht: Das Angebot ist weg.
        assertEquals(TradeProblem.STALE, assertThrows<TradeException> { repo.takeOffer(anna, tuesday, ben) }.problem)

        // Ben hat ein eigenes Gerät: Für ihn bietet nur er selbst (oder ein Admin) an.
        repo.mergeRemote(Buckets.TEAM, mapOf(PlanKeys.deviceOwner(other) to Entry(ben, now, other)))
        assertThrows<TradeNotAllowedException> { repo.offerShift(ben, tuesday) }
        // Hat die Person den Dienst nicht mehr, gilt das Angebot nicht mehr.
        repo.setShift(anna, monday, "F")
        repo.offerShift(anna, monday)
        repo.setShift(anna, monday, "N")
        assertFalse(ShiftTrades.isOfferValid(repo.state.value, repo.state.value.offers.single()))
        repo.withdrawOffer(anna, monday)
        assertTrue(repo.state.value.offers.isEmpty())
    }

    @Test
    fun `Uebergabe an einem gesperrten Tag bestaetigt ein Admin`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        val tuesday = monday.plusDays(1)
        repo.setShift(anna, tuesday, "S")
        repo.offerShift(anna, tuesday)
        repo.setAccess(lockedUntilEndOfOctober(repo.lockTimestamp()))
        assertEquals(TradeOutcome.AWAITING_ADMIN, repo.takeOffer(anna, tuesday, ben))
        assertEquals(ShiftOffer("S", ben), repo.state.value.offer(anna, tuesday))
        assertEquals("S", repo.state.value.shift(anna, tuesday), "Schichten bleiben, bis ein Admin bestätigt")
        assertThrows<TradeNotAllowedException> { repo.approveClaim(anna, tuesday) }
        // Die abgebende Person darf die Anmeldung ablehnen; das Angebot ist dann wieder offen.
        repo.rejectClaim(anna, tuesday)
        assertEquals(ShiftOffer("S"), repo.state.value.offer(anna, tuesday))
        repo.takeOffer(anna, tuesday, ben)
        repo.deviceId = admin
        repo.approveClaim(anna, tuesday)
        assertEquals("S", repo.state.value.shift(ben, tuesday))
        assertNull(repo.state.value.shift(anna, tuesday))
        assertNull(repo.state.value.offer(anna, tuesday))
    }

    @Test
    fun `Dienste tauschen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        val thursday = monday.plusDays(3)
        repo.setShift(anna, monday, "F")
        repo.setShift(ben, thursday, "S")
        val candidates = ShiftTrades.candidates(repo.state.value, anna, monday)
        assertTrue(candidates.any { it.memberId == ben && it.date == thursday && it.typeId == "S" })
        assertTrue(candidates.any { it.memberId == ben && it.date == monday && it.typeId == null })

        repo.proposeSwap(anna, monday, ben, thursday)
        val swap = PlanKey.Swap(anna, monday, ben, thursday)
        assertEquals(SwapRequest(SwapStatus.PROPOSED, "F", "S"), repo.state.value.swaps.single().request)
        assertEquals(TradeOutcome.DONE, repo.answerSwap(swap, accept = true))
        var state = repo.state.value
        assertNull(state.shift(anna, monday))
        assertEquals("S", state.shift(anna, thursday))
        assertEquals("F", state.shift(ben, monday))
        assertNull(state.shift(ben, thursday))
        assertEquals(SwapStatus.DONE, state.swaps.single().request.status)
        assertEquals(TradeProblem.STALE, assertThrows<TradeException> { repo.answerSwap(swap, accept = true) }.problem)

        // Am selben Tag tauschen beide ihre Schicht.
        val tuesday = monday.plusDays(1)
        repo.setShift(anna, tuesday, "F")
        repo.setShift(ben, tuesday, "N")
        repo.proposeSwap(anna, tuesday, ben, tuesday)
        repo.answerSwap(PlanKey.Swap(anna, tuesday, ben, tuesday), accept = true)
        state = repo.state.value
        assertEquals("N", state.shift(anna, tuesday))
        assertEquals("F", state.shift(ben, tuesday))

        // Veraltet: Annas Dienst hat sich seit dem Vorschlag geändert.
        val wednesday = monday.plusDays(2)
        repo.setShift(anna, wednesday, "S")
        repo.proposeSwap(anna, wednesday, ben, wednesday) // Ben ist frei: Er übernimmt nur
        repo.setShift(anna, wednesday, "F")
        val stale = PlanKey.Swap(anna, wednesday, ben, wednesday)
        assertEquals(TradeProblem.STALE, assertThrows<TradeException> { repo.answerSwap(stale, accept = true) }.problem)
        repo.answerSwap(stale, accept = false)
        assertEquals(SwapStatus.DECLINED, repo.state.value.swaps.first { it.swap == stale }.request.status)
        repo.withdrawSwap(stale)
        assertTrue(repo.state.value.swaps.none { it.swap == stale })
    }

    @Test
    fun `angenommener Tausch an einem gesperrten Tag wartet auf einen Admin`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        repo.setShift(anna, monday, "F")
        repo.proposeSwap(anna, monday, ben, monday)
        repo.setAccess(lockedUntilEndOfOctober(repo.lockTimestamp()))
        val swap = PlanKey.Swap(anna, monday, ben, monday)
        assertEquals(TradeOutcome.AWAITING_ADMIN, repo.answerSwap(swap, accept = true))
        assertEquals(SwapStatus.ACCEPTED, repo.state.value.swaps.single().request.status)
        assertThrows<TradeNotAllowedException> { repo.executeSwap(swap) }
        repo.deviceId = admin
        repo.executeSwap(swap)
        assertEquals("F", repo.state.value.shift(ben, monday))
        assertNull(repo.state.value.shift(anna, monday))
        assertEquals(SwapStatus.DONE, repo.state.value.swaps.single().request.status)
    }

    @Test
    fun `Plan-Vorschlag fuellt offene Dienste fair und nach den Regeln`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        val chiara = repo.addMember("Chiara")
        repo.setTargets("F", listOf(1, 1, 1, 1, 1, 0, 0))
        repo.setTargets("S", listOf(1, 1, 1, 1, 1, 0, 0))
        repo.setWish(anna, monday, Wish.DAY_OFF)
        repo.setWish(ben, monday.plusDays(1), Wish.shift("S"))
        repo.setShift(chiara, monday.plusDays(2), "U") // Urlaub am Mittwoch
        val week = WeekId.of(monday).days
        val suggestion = PlanSuggestion.suggest(repo.state.value, week, me)
        assertEquals(10, suggestion.shifts.size)
        assertTrue(suggestion.gaps.isEmpty())
        assertTrue(suggestion.shifts.none { it.memberId == anna && it.date == monday })
        assertTrue(suggestion.shifts.any { it.memberId == ben && it.date == monday.plusDays(1) && it.type.id == "S" })
        assertTrue(suggestion.shifts.none { it.memberId == chiara && it.date == monday.plusDays(2) })
        val counts = suggestion.shifts.groupingBy { it.memberId }.eachCount()
        assertTrue(counts.values.all { it in 3..4 }, "fair verteilt: $counts")

        val batch = repo.applySuggestion(suggestion)
        assertEquals(10, batch.changed)
        val state = repo.state.value
        for (member in listOf(anna, ben, chiara)) {
            for (date in week) {
                assertNull(RestRules.issueBefore(state, state.shiftTypes, member, date, state.shift(member, date), state.restMinutes), "$member $date")
            }
        }
        assertEquals("U", state.shift(chiara, monday.plusDays(2)), "Vorhandenes bleibt")
        repo.undo(batch)
        assertTrue(ShiftTrades.openShifts(repo.state.value, monday, monday.plusDays(6)).size == 10)

        // Gesperrte Tage lässt der Vorschlag aus.
        val partial = PlanSuggestion.suggest(repo.state.value, week, me) { it != monday }
        assertTrue(partial.shifts.none { it.date == monday })
        assertTrue(partial.gaps.none { it.date == monday })
    }

    @Test
    fun `Rueckgaengig fuer einzelne Aenderungen und ganze Wischbewegungen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val first = repo.setShift(anna, monday, "F")
        val second = repo.setShift(anna, monday, "S")
        val third = repo.setShift(anna, monday.plusDays(1), "N")
        assertEquals(1, first.changed)
        val combined = Batch.combine(listOf(first, second, third))
        assertEquals(2, combined.changed)
        assertEquals(setOf(PlanKeys.shift(anna, monday), PlanKeys.shift(anna, monday.plusDays(1))), combined.keys)
        assertEquals(2, repo.undo(combined))
        assertNull(repo.state.value.shift(anna, monday))
        assertNull(repo.state.value.shift(anna, monday.plusDays(1)))
        assertEquals(0, Batch.combine(listOf(repo.setShift(anna, monday, "F"), repo.setShift(anna, monday, null))).changed)
        val deleted = repo.deleteMember(anna)
        repo.undo(deleted)
        assertEquals("Anna", repo.state.value.members().single().name)
    }

    @Test
    fun `Aenderungen anderer Geraete gelten als neu, bis jemand hinschaut`() = runTest {
        val repo = repository()
        val anna = "0123456789abcdef"
        val bucket = WeekId.of(monday).bucketName
        // Erster Abgleich in einen leeren Plan: nichts ist neu.
        repo.mergeRemote(Buckets.TEAM, mapOf(PlanKeys.member(anna) to Entry("Anna", now - 10, other)))
        assertTrue(repo.unseenKeys.value.isEmpty())
        val key = PlanKeys.shift(anna, monday)
        repo.mergeRemote(bucket, mapOf(key to Entry("F", now, other)))
        assertEquals(setOf(key), repo.unseenKeys.value)
        // Eigene Änderungen sind nie neu und heben die Markierung auf.
        repo.setShift(anna, monday.plusDays(1), "S")
        assertEquals(setOf(key), repo.unseenKeys.value)
        repo.setShift(anna, monday, "N")
        assertTrue(repo.unseenKeys.value.isEmpty())
        repo.mergeRemote(bucket, mapOf(key to Entry("S", now + 100, other), PlanKeys.memberNote(anna, monday) to Entry("Hallo", now + 101, other)))
        assertEquals(2, repo.unseenKeys.value.size)
        repo.markSeen(listOf(key))
        assertEquals(setOf(PlanKeys.memberNote(anna, monday)), repo.unseenKeys.value)

        // Übersteht einen Neustart, verfällt aber nach zwei Wochen.
        advanceTimeBy(200)
        runCurrent()
        val restarted = repository()
        restarted.load()
        assertEquals(setOf(PlanKeys.memberNote(anna, monday)), restarted.unseenKeys.value)
        now += PlanRepository.UNSEEN_MAX_AGE_MILLIS + 1
        val later = repository()
        later.load()
        assertTrue(later.unseenKeys.value.isEmpty())
        restarted.markAllSeen()
        assertTrue(restarted.unseenKeys.value.isEmpty())
    }

    @Test
    fun `ausstehende Schluessel und neue Einstellungen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        repo.setShift(anna, monday, "F")
        val key = PlanKeys.shift(anna, monday)
        assertTrue(key in repo.pendingKeys.value)
        repo.markSent(mapOf(key to repo.state.value.entry(key)!!.timestamp))
        assertFalse(key in repo.pendingKeys.value)

        repo.setPensum(anna, 80)
        repo.setWeekMinutes(41 * 60)
        repo.setCanton(Canton.LU)
        repo.setWishDeadline(YearMonth.of(2026, 11), LocalDate.of(2026, 10, 15))
        val state = repo.state.value
        assertEquals(80, state.pensum(anna))
        assertEquals(2460, state.weekMinutes)
        assertEquals(Canton.LU, state.canton)
        assertEquals(LocalDate.of(2026, 10, 15), state.wishDeadline(YearMonth.of(2026, 11)))
        assertThrows<IllegalArgumentException> { repo.setPensum(anna, 0) }
        assertThrows<IllegalArgumentException> { repo.setWeekMinutes(80 * 60) }
        repo.setPensum(anna, null)
        repo.setCanton(null)
        assertNull(repo.state.value.pensum(anna))
        assertNull(repo.state.value.canton)
        repo.setWeekMinutes(null)
        assertEquals(PlanRules.DEFAULT_WEEK_MINUTES, repo.state.value.weekMinutes)

        // Gesperrt: Pensum und Regeln nur für Admins.
        repo.setAccess(lockedUntilEndOfOctober(repo.lockTimestamp()))
        assertThrows<PlanLockedException> { repo.setPensum(anna, 50) }
        assertThrows<PlanLockedException> { repo.setCanton(Canton.ZH) }
    }
}
