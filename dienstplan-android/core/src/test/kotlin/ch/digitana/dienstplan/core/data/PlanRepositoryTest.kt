package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.Shift
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlanRepositoryTest {

    private class MemoryPlanStore : PlanStore {
        var snapshot: PlanSnapshot? = null
        var saves = 0
        override fun load() = snapshot
        override fun save(snapshot: PlanSnapshot) {
            this.snapshot = PlanSnapshotCodec.decode(PlanSnapshotCodec.encode(snapshot)) // Rundlauf mitprüfen
            saves++
        }
        override fun clear() {
            snapshot = null
        }
    }

    private var now = 1_790_000_000_000L
    private val store = MemoryPlanStore()

    private fun TestScope.repository(): PlanRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PlanRepository(store, backgroundScope, Clock { now }, dispatcher, saveDelayMillis = 100).apply {
            deviceId = "00000000000000aa"
        }
    }

    @Test
    fun `lokale Aenderungen tragen HLC-Zeitstempel und Geraete-ID`() = runTest {
        val repo = repository()
        val id = repo.addMember("  Anna   Muster ")
        val date = LocalDate.of(2026, 9, 21)
        repo.setShift(id, date, Shift.FRUEH)
        repo.setShift(id, date, Shift.SPAET) // gleiche Millisekunde → trotzdem neuer Zeitstempel
        val state = repo.state.value
        assertEquals("Anna Muster", state.members().single().name)
        val entry = state.entry(PlanKeys.shift(id, date))!!
        assertEquals("S", entry.value)
        assertEquals("00000000000000aa", entry.device)
        assertEquals(now + 2, entry.timestamp)
    }

    @Test
    fun `schnelles Mehrfachtippen verliert keinen Schritt`() = runTest {
        val repo = repository()
        val id = repo.addMember("Anna")
        val date = LocalDate.of(2026, 9, 21)
        coroutineScope { repeat(3) { launch { repo.cycleShift(id, date) } } }
        assertEquals(Shift.NACHT, repo.state.value.shift(id, date)) // leer → F → S → N
        repeat(3) { repo.cycleShift(id, date) } // → X → U → leer
        assertNull(repo.state.value.shift(id, date))
    }

    @Test
    fun `Namen werden geprueft`() = runTest {
        val repo = repository()
        assertEquals(NameProblem.EMPTY, assertThrows<InvalidInputException> { repo.addMember("   ") }.problem)
        assertEquals(NameProblem.TOO_LONG, assertThrows<InvalidInputException> { repo.addMember("a".repeat(41)) }.problem)
        assertEquals(NameProblem.INVALID_CHARACTERS, assertThrows<InvalidInputException> { repo.addMember("Anna\u202e") }.problem)
        repo.addMember("a".repeat(40))
        repo.addMember("😀".repeat(40))
        assertEquals(2, repo.state.value.members().size)
    }

    @Test
    fun `ohne Team keine Aenderungen`() = runTest {
        val repo = repository().apply { deviceId = null }
        assertThrows<IllegalStateException> { repo.addMember("Anna") }
    }

    @Test
    fun `Loeschen und Leeren sind Tombstones`() = runTest {
        val repo = repository()
        val id = repo.addMember("Anna")
        val date = LocalDate.of(2026, 9, 21)
        repo.setShift(id, date, Shift.NACHT)
        repo.setShift(id, date, null)
        repo.deleteMember(id)
        val state = repo.state.value
        assertTrue(state.members().isEmpty())
        assertEquals("", state.entry(PlanKeys.member(id))!!.value)
        assertEquals("", state.entry(PlanKeys.shift(id, date))!!.value)
        assertNull(state.shift(id, date))
    }

    @Test
    fun `Woche kopieren ersetzt die Folgewoche komplett und schreibt nur Unterschiede`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        val source = WeekId(2026, 39)
        val target = source.next()
        repo.setShift(anna, source.days[0], Shift.FRUEH)
        repo.setShift(anna, source.days[6], Shift.URLAUB)
        repo.setShift(ben, target.days[2], Shift.NACHT) // wird geleert
        repo.setShift(anna, target.days[0], Shift.FRUEH) // schon gleich → nicht neu schreiben
        val before = repo.state.value.entry(PlanKeys.shift(anna, target.days[0]))

        assertTrue(repo.hasEntries(target))
        val changed = repo.copyWeekToNext(source)

        val state = repo.state.value
        assertEquals(2, changed) // Anna So = U, Ben Mi geleert
        assertEquals(before, state.entry(PlanKeys.shift(anna, target.days[0])))
        assertEquals(Shift.URLAUB, state.shift(anna, target.days[6]))
        assertNull(state.shift(ben, target.days[2]))
        for (i in 0..6) {
            assertEquals(state.shift(anna, source.days[i]), state.shift(anna, target.days[i]))
            assertEquals(state.shift(ben, source.days[i]), state.shift(ben, target.days[i]))
        }
    }

    @Test
    fun `Merge von Relays zieht die Uhr nach und meldet Aenderungen`() = runTest {
        val repo = repository()
        val key = PlanKeys.member("0000000000000001")
        val remote = Entry("Remote", now + 60_000, "00000000000000bb")
        assertTrue(repo.mergeRemote(Buckets.TEAM, mapOf(key to remote)))
        assertFalse(repo.mergeRemote(Buckets.TEAM, mapOf(key to remote)))
        repo.renameMember("0000000000000001", "Lokal")
        val entry = repo.state.value.entry(key)!!
        assertEquals("Lokal", entry.value)
        assertEquals(now + 60_001, entry.timestamp) // überholt den empfangenen Zeitstempel
    }

    @Test
    fun `speichert entprellt und laedt wieder`() = runTest {
        val repo = repository()
        val id = repo.addMember("Anna")
        repeat(5) { repo.setShift(id, LocalDate.of(2026, 9, 21 + it), Shift.FRUEH) }
        // Der Speicher-Job läuft im backgroundScope; advanceUntilIdle() würde ihn nicht vorspulen.
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(store.saves in 1..2, "entprellt: ${store.saves}")
        val saved = assertNotNull(store.snapshot)
        assertEquals(repo.state.value, saved.state)

        now -= 10_000_000 // Uhr des Geräts wurde zurückgestellt
        val reloaded = repository()
        reloaded.load()
        assertEquals(repo.state.value, reloaded.state.value)
        reloaded.setShift(id, LocalDate.of(2026, 9, 21), Shift.NACHT)
        val ts = reloaded.state.value.entry(PlanKeys.shift(id, LocalDate.of(2026, 9, 21)))!!.timestamp
        assertTrue(ts > repo.state.value.maxTimestamp, "HLC setzt nach dem Laden über dem höchsten bekannten Zeitstempel fort")
    }

    @Test
    fun `replaceAll verwirft den Stand`() = runTest {
        val repo = repository()
        repo.addMember("Anna")
        repo.replaceAll(PlanState.EMPTY)
        assertTrue(repo.state.value.isEmpty())
        assertTrue(store.snapshot!!.state.isEmpty())
    }

    @Test
    fun `eigene Aenderungen bleiben ausstehend, bis ein Relay sie bestaetigt`() = runTest {
        val repo = repository()
        val id = repo.addMember("Anna")
        val date = LocalDate.of(2026, 9, 21)
        repo.setShift(id, date, Shift.FRUEH)
        val pending = repo.pendingEntries()
        assertEquals(setOf(PlanKeys.member(id), PlanKeys.shift(id, date)), pending.keys)

        // Bestätigung eines älteren Stands lässt die neuere Änderung ausstehend.
        val sentShift = pending.getValue(PlanKeys.shift(id, date)).timestamp
        repo.setShift(id, date, Shift.NACHT)
        repo.markSent(mapOf(PlanKeys.member(id) to pending.getValue(PlanKeys.member(id)).timestamp, PlanKeys.shift(id, date) to sentShift))
        assertEquals(setOf(PlanKeys.shift(id, date)), repo.pendingEntries().keys)
        assertEquals("N", repo.pendingEntries().getValue(PlanKeys.shift(id, date)).value)

        // Ein neuerer Eintrag von aussen überholt die eigene Änderung.
        val key = PlanKeys.shift(id, date)
        repo.mergeRemote(WeekId.of(date).bucketName, mapOf(key to Entry("U", now + 60_000, "00000000000000bb")))
        assertTrue(repo.pendingEntries().isEmpty())
    }

    @Test
    fun `ausstehende Aenderungen ueberstehen einen Neustart`() = runTest {
        val repo = repository()
        val id = repo.addMember("Anna")
        repo.flush()
        val reloaded = repository()
        reloaded.load()
        assertEquals(setOf(PlanKeys.member(id)), reloaded.pendingEntries().keys)
    }

    @Test
    fun `alter Speicherstand ohne ausstehende Aenderungen bleibt lesbar`() {
        val v1 = """{"v":1,"clock":5,"buckets":{"team":[["m|00000000000000aa","Anna",5,"00000000000000aa"]]}}"""
        val snapshot = PlanSnapshotCodec.decode(v1.toByteArray())
        assertEquals(listOf("Anna"), snapshot.state.members().map { it.name })
        assertTrue(snapshot.pending.isEmpty())
    }

    @Test
    fun `lokalen Plan in ein Team uebernehmen und Geraete benennen`() = runTest {
        val repo = repository()
        repo.mergeRemote(Buckets.TEAM, mapOf(PlanKeys.member("00000000000000cc") to Entry("Ben", 7, "00000000000000bb")))
        assertTrue(repo.pendingEntries().isEmpty())
        repo.markAllPending()
        assertEquals(setOf(PlanKeys.member("00000000000000cc")), repo.pendingEntries().keys)

        repo.setDeviceLabel("0123456789abcdef", "  Annas   Handy ")
        assertEquals(mapOf("0123456789abcdef" to "Annas Handy"), repo.state.value.deviceLabels())
        repo.setDeviceLabel("0123456789abcdef", "")
        assertTrue(repo.state.value.deviceLabels().isEmpty())
        assertThrows<InvalidInputException> { repo.setDeviceLabel("0123456789abcdef", "x".repeat(41)) }
    }
}
