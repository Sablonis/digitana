package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.PlanAccess
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanLock
import ch.digitana.dienstplan.core.crdt.PlanLocks
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalTime
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
        repo.setShift(id, date, "F")
        repo.setShift(id, date, "S") // gleiche Millisekunde → trotzdem neuer Zeitstempel
        val state = repo.state.value
        assertEquals("Anna Muster", state.members().single().name)
        val entry = state.entry(PlanKeys.shift(id, date))!!
        assertEquals("S", entry.value)
        assertEquals("00000000000000aa", entry.device)
        assertEquals(now + 2, entry.timestamp)
    }

    @Test
    fun `gleichzeitige Aenderungen gehen nicht verloren`() = runTest {
        val repo = repository()
        val id = repo.addMember("Anna")
        val monday = LocalDate.of(2026, 9, 21)
        val types = listOf("F", "S", "N", "X", "U")
        coroutineScope { types.forEachIndexed { i, type -> launch { repo.setShift(id, monday.plusDays(i.toLong()), type) } } }
        assertEquals(types, (0L..4L).map { repo.state.value.shift(id, monday.plusDays(it)) })
        repo.setShift(id, monday, null)
        assertNull(repo.state.value.shift(id, monday))
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
        repo.setShift(id, date, "N")
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
        repo.setShift(anna, source.days[0], "F")
        repo.setShift(anna, source.days[6], "U")
        repo.setShift(ben, target.days[2], "N") // wird geleert
        repo.setShift(anna, target.days[0], "F") // schon gleich → nicht neu schreiben
        val before = repo.state.value.entry(PlanKeys.shift(anna, target.days[0]))

        assertTrue(repo.hasEntries(target))
        val batch = repo.copyWeekToNext(source)

        val state = repo.state.value
        assertEquals(2, batch.changed) // Anna So = U, Ben Mi geleert
        assertEquals(before, state.entry(PlanKeys.shift(anna, target.days[0])))
        assertEquals("U", state.shift(anna, target.days[6]))
        assertNull(state.shift(ben, target.days[2]))
        for (i in 0..6) {
            assertEquals(state.shift(anna, source.days[i]), state.shift(anna, target.days[i]))
            assertEquals(state.shift(ben, source.days[i]), state.shift(ben, target.days[i]))
        }

        // Rückgängig: nur Felder, die seither niemand geändert hat.
        repo.setShift(anna, target.days[6], "X")
        assertEquals(1, repo.undo(batch))
        assertEquals("N", repo.state.value.shift(ben, target.days[2]))
        assertEquals("X", repo.state.value.shift(anna, target.days[6]))
    }

    @Test
    fun `Merge von Relays zieht die Uhr nach und meldet Aenderungen`() = runTest {
        val repo = repository()
        val key = PlanKeys.member("0000000000000001")
        val remote = Entry("Remote", now + 60_000, "00000000000000bb")
        assertTrue(repo.mergeRemote(Buckets.TEAM, mapOf(key to remote)).changed)
        assertFalse(repo.mergeRemote(Buckets.TEAM, mapOf(key to remote)).changed)
        repo.renameMember("0000000000000001", "Lokal")
        val entry = repo.state.value.entry(key)!!
        assertEquals("Lokal", entry.value)
        assertEquals(now + 60_001, entry.timestamp) // überholt den empfangenen Zeitstempel
    }

    @Test
    fun `speichert entprellt und laedt wieder`() = runTest {
        val repo = repository()
        val id = repo.addMember("Anna")
        repeat(5) { repo.setShift(id, LocalDate.of(2026, 9, 21 + it), "F") }
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
        reloaded.setShift(id, LocalDate.of(2026, 9, 21), "N")
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
        repo.setShift(id, date, "F")
        val pending = repo.pendingEntries()
        assertEquals(setOf(PlanKeys.member(id), PlanKeys.shift(id, date)), pending.keys)

        // Bestätigung eines älteren Stands lässt die neuere Änderung ausstehend.
        val sentShift = pending.getValue(PlanKeys.shift(id, date)).timestamp
        repo.setShift(id, date, "N")
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

    @Test
    fun `Notizen, Wuensche, Schichtarten und Rhythmen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val monday = LocalDate.of(2026, 10, 5)

        repo.setDayNote(monday, "  Teamsitzung   14 Uhr ")
        repo.setMemberNote(anna, monday, "Schlüssel holen")
        assertEquals("Teamsitzung 14 Uhr", repo.state.value.dayNote(monday))
        assertEquals("Schlüssel holen", repo.state.value.memberNote(anna, monday))
        repo.setDayNote(monday, "   ")
        assertNull(repo.state.value.dayNote(monday))
        val tooLong = assertThrows<InvalidInputException> { repo.setMemberNote(anna, monday, "x".repeat(201)) }
        assertEquals(NameProblem.TOO_LONG, tooLong.problem)
        assertThrows<InvalidInputException> { repo.setDayNote(monday, "a\u202eb") }

        repo.setWish(anna, monday, Wish.DAY_OFF)
        assertEquals(Wish.DAY_OFF, repo.state.value.wish(anna, monday))
        repo.setWish(anna, monday, null)
        assertNull(repo.state.value.wish(anna, monday))

        val typeId = repo.newShiftTypeId()
        val type = ShiftType(typeId, "T", "Tagdienst", LocalTime.of(7, 0), LocalTime.of(16, 0), color = 5)
        repo.saveShiftType(type)
        assertEquals(type, repo.state.value.shiftTypes[typeId])
        repo.saveShiftType(type.copy(archived = true))
        assertTrue(repo.state.value.shiftTypes.active.none { it.id == typeId })
        repo.saveShiftType(ShiftTypes.default("F")!!.copy(name = "Frühdienst"))
        assertEquals("Frühdienst", repo.state.value.shiftTypes["F"]?.name)
        repo.resetShiftType("F")
        assertEquals("Früh", repo.state.value.shiftTypes["F"]?.name)
        assertThrows<IllegalArgumentException> { repo.resetShiftType(typeId) }
        assertThrows<IllegalArgumentException> { repo.setShift(anna, monday, "Q") }

        val pattern = ShiftPattern(repo.newPatternId(), "Tagwoche", listOf(typeId, typeId, typeId, typeId, typeId, null, null))
        repo.savePattern(pattern)
        assertEquals(listOf(pattern), repo.state.value.patterns())
        assertEquals(10, repo.applyPattern(pattern, listOf(anna), monday, weeks = 2, overwrite = false).changed)
        assertEquals(typeId, repo.state.value.shift(anna, monday.plusDays(8)))
        assertEquals(0, repo.applyPattern(pattern, listOf(anna), monday, weeks = 2, overwrite = true).changed) // schon gleich
        repo.deletePattern(pattern.id)
        assertTrue(repo.state.value.patterns().isEmpty())
    }

    @Test
    fun `Sperre schuetzt Schichten, Schichtarten und das Loeschen von Personen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        val october = LocalDate.of(2026, 10, 5)
        val december = LocalDate.of(2026, 12, 7)
        repo.setShift(anna, october, "F")
        val admin = "00000000000000bb"
        val lock = PlanLocks.lock(null, LocalDate.of(2026, 10, 31), repo.lockTimestamp(), setOf(admin), admin)
        assertEquals(0, repo.setAccess(PlanAccess(lock, setOf(admin))))

        // Mitglied: gesperrte Tage, Schichtarten und Löschen gehen nicht; alles andere schon.
        assertThrows<PlanLockedException> { repo.setShift(anna, october, "S") }
        assertThrows<PlanLockedException> { repo.copyWeekToNext(WeekId.of(october)) }
        assertThrows<PlanLockedException> { repo.saveShiftType(ShiftTypes.default("F")!!.copy(name = "Frühdienst")) }
        assertThrows<PlanLockedException> { repo.deleteMember(ben) }
        assertEquals("F", repo.state.value.shift(anna, october))
        repo.setShift(anna, december, "S")
        repo.setWish(ben, october, Wish.DAY_OFF)
        repo.setMemberNote(anna, october, "Tausch mit Ben?")
        repo.renameMember(ben, "Ben K.")
        repo.addMember("Chiara")
        // Unverändertes zu „schreiben“ ist kein Verstoss (z. B. Woche kopieren ohne Unterschied).
        repo.setShift(anna, october, "F")

        // Als Admin geht alles.
        repo.deviceId = admin
        repo.setShift(anna, october, "N")
        repo.deleteMember(ben)
        assertEquals("N", repo.state.value.shift(anna, october))
    }

    @Test
    fun `neue Sperre entfernt spaetere Eintraege von Mitgliedern und meldet eigene`() = runTest {
        val repo = repository()
        val me = "00000000000000aa"
        val admin = "00000000000000bb"
        val other = "00000000000000cc"
        val anna = "0123456789abcdef"
        val day = LocalDate.of(2026, 10, 5)
        val bucket = WeekId.of(day).bucketName
        val key = PlanKeys.shift(anna, day)
        val noteKey = PlanKeys.memberNote(anna, day)
        // Vor der Sperre (Zeitpunkt 1000) bzw. danach eingetragen.
        repo.mergeRemote(bucket, mapOf(key to Entry("F", 900, other), noteKey to Entry("Notiz", 2000, other)))
        repo.setShift(anna, day.plusDays(1), "S") // eigene Änderung, jünger als die Sperre
        val ownKey = PlanKeys.shift(anna, day.plusDays(1))
        assertTrue(ownKey in repo.pendingEntries())
        val lock = PlanLock(listOf(PlanLock.Segment(LocalDate.of(2026, 10, 31), 1000)), setOf(admin), admin)
        val discarded = backgroundScope.async { repo.discarded.first() }
        runCurrent()

        assertEquals(1, repo.setAccess(PlanAccess(lock, setOf(admin))))
        assertEquals(1, discarded.await())
        assertNull(repo.state.value.entry(ownKey))
        assertTrue(ownKey !in repo.pendingEntries())
        assertEquals("F", repo.state.value.shift(anna, day), "älterer Eintrag bleibt")
        assertEquals("Notiz", repo.state.value.memberNote(anna, day), "Notizen sind nicht gesperrt")

        // Empfang: spätere Einträge von Mitgliedern verworfen, vom Admin übernommen.
        val rejected = repo.mergeRemote(bucket, mapOf(key to Entry("N", 3000, other)))
        assertEquals(1, rejected.rejected)
        assertFalse(rejected.changed)
        val fromAdmin = repo.mergeRemote(bucket, mapOf(key to Entry("U", 3000, admin), noteKey to Entry("Neu", 3001, other)))
        assertEquals(0, fromAdmin.rejected)
        assertEquals("U", repo.state.value.shift(anna, day))
        // Was der Abgleich nachliefert, kommt wieder rein, falls es vor der Sperre lag.
        repo.mergeRemote(bucket, mapOf(ownKey to Entry("X", 950, other)))
        assertEquals("X", repo.state.value.shift(anna, day.plusDays(1)))

        // Eigene neue Einträge sind jünger als jeder Abschnitt, auch wenn die eigene Uhr nachgeht.
        now = 10
        repo.setShift(anna, LocalDate.of(2026, 11, 2), "F")
        assertTrue(repo.state.value.entry(PlanKeys.shift(anna, LocalDate.of(2026, 11, 2)))!!.timestamp > 1000)

        // Öffnen entfernt nichts.
        assertEquals(0, repo.setAccess(PlanAccess.OPEN))
        repo.setShift(anna, day, "S")
        assertEquals("S", repo.state.value.shift(anna, day))
        assertEquals(me, repo.state.value.entry(key)!!.device)
    }

    @Test
    fun `Wuensche gehoeren der Person, Geraete lassen sich zuordnen`() = runTest {
        val repo = repository()
        val anna = repo.addMember("Anna")
        val ben = repo.addMember("Ben")
        val day = LocalDate.of(2026, 10, 5)
        // Noch niemand ist zugeordnet: Alle dürfen alle Wünsche eintragen.
        repo.setWish(ben, day, Wish.WORK)
        repo.setDeviceOwner(anna)
        assertEquals(mapOf("00000000000000aa" to anna), repo.state.value.deviceOwners())
        repo.setWish(anna, day, Wish.shift("F"))
        assertEquals(Wish.shift("F"), repo.state.value.wish(anna, day))
        // Ben hat ein eigenes Gerät: Seine Wünsche ändert nur er (oder ein Admin).
        repo.mergeRemote(Buckets.TEAM, mapOf(PlanKeys.deviceOwner("00000000000000bb") to Entry(ben, now, "00000000000000bb")))
        assertThrows<WishNotAllowedException> { repo.setWish(ben, day, null) }
        assertEquals(Wish.WORK, repo.state.value.wish(ben, day))
        repo.setAccess(PlanAccess(null, setOf("00000000000000aa")))
        repo.setWish(ben, day, null)
        assertNull(repo.state.value.wish(ben, day))
        repo.setDeviceOwner(null)
        assertEquals(mapOf("00000000000000bb" to ben), repo.state.value.deviceOwners())
        assertThrows<IllegalArgumentException> { repo.setDeviceOwner("Anna") }
    }
}
