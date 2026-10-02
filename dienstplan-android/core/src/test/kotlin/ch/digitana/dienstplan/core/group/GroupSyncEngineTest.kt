package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.data.PlanLockedException
import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.testing.FakeRelay
import ch.digitana.dienstplan.core.testing.GroupDevice
import ch.digitana.dienstplan.core.testing.GroupDevice.Companion.awaitCondition
import ch.digitana.dienstplan.core.testing.GroupDevice.Companion.awaitConvergence
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrationstest mit drei lokalen Nostr-Relays (TLS) und echter MLS-Verschlüsselung:
 * Gründen, Beitreten per Code, gleichzeitige Änderungen, Entfernen, Austritt, Neustart.
 */
@Timeout(180)
class GroupSyncEngineTest {

    @TempDir
    lateinit var dir: File

    private val relays = List(3) { FakeRelay("R$it").start() }
    private val urls = relays.map { it.url }
    private val devices = mutableListOf<GroupDevice>()
    private val week = WeekId.of(LocalDate.of(2026, 9, 21))

    private suspend fun device(name: String): GroupDevice =
        GroupDevice(name, urls, dir).also {
            devices += it
            it.load()
        }

    @AfterEach
    fun tearDown() = runBlocking {
        devices.forEach { runCatching { it.close() } }
        relays.forEach { it.close() }
    }

    private suspend fun awaitLive(vararg ds: GroupDevice) =
        assertTrue(awaitCondition(15_000) { ds.all { it.engine!!.status.value.isLive } }, "nicht alle live")

    /** Admin fügt ein Gerät hinzu, das Gerät nimmt die Einladung an. */
    private suspend fun join(admin: GroupDevice, newcomer: GroupDevice) {
        val code = newcomer.startJoining()
        if (newcomer.engine == null) newcomer.startEngine()
        val publicKey = (JoinCode.parse(code) as JoinCode.ParseResult.Valid).publicKey
        assertEquals(newcomer.publicKey, publicKey)
        assertTrue(awaitCondition(15_000) { (newcomer.teamState as? TeamState.Joining)?.published == true }, "KeyPackage nicht veröffentlicht")
        admin.engine!!.addDevice(publicKey)
        val invite = assertNotNull(newcomer.awaitInvite(), "keine Einladung")
        assertEquals((admin.teamState as TeamState.Member).team.name, invite.teamName)
        assertEquals(admin.publicKey, invite.inviter)
        newcomer.accept(invite)
        assertIs<TeamState.Member>(newcomer.teamState)
    }

    private fun member(device: GroupDevice) = device.teamState as TeamState.Member

    @Test
    fun `Gruenden, zwei Geraete hinzufuegen, gleichzeitige Aenderungen konvergieren`() = runBlocking {
        val a = device("A")
        a.createTeam("Pflege")
        a.startEngine()
        awaitLive(a)
        val anna = a.plan.addMember("Anna")
        val ben = a.plan.addMember("Ben")
        a.plan.setShift(anna, week.days[0], "F")

        val b = device("B")
        join(a, b)
        // Das neue Gerät bekommt den ganzen Plan, obwohl es ältere Nachrichten nicht lesen kann.
        assertTrue(awaitCondition(20_000) { b.state.members().size == 2 && b.state.shift(anna, week.days[0]) == "F" }, "B hat den Plan nicht")

        val c = device("C")
        join(a, c)
        assertTrue(awaitConvergence(listOf(a, b, c), 30_000), "A, B, C konvergieren nicht")
        assertEquals(3, member(a).team.members.size)
        assertEquals(listOf(a.publicKey), member(c).team.admins)

        val conflictKey = PlanKeys.shift(anna, week.days[2])
        var writtenByB: Entry? = null
        var writtenByC: Entry? = null
        coroutineScope {
            launch {
                b.plan.setShift(ben, week.days[1], "S")
                b.plan.setShift(anna, week.days[2], "N")
                writtenByB = b.state.entry(conflictKey)
                b.plan.addMember("Chiara")
            }
            launch {
                c.plan.setShift(anna, week.days[2], "U")
                writtenByC = c.state.entry(conflictKey)
                c.plan.addMember("Dario")
            }
            launch { a.plan.copyWeekToNext(week) }
        }
        assertTrue(awaitConvergence(listOf(a, b, c), 30_000), "Änderungen konvergieren nicht")
        assertEquals(Entry.newer(writtenByB, writtenByC), a.state.entry(conflictKey), "Last-Writer-Wins")
        assertEquals(setOf("Anna", "Ben", "Chiara", "Dario"), a.state.members().map { it.name }.toSet())
        assertEquals("S", c.state.shift(ben, week.days[1]))

        // Auf den Relays liegt nichts Lesbares; das KeyPackage von B ist nach dem Beitritt gelöscht.
        for (relay in relays) {
            for (event in relay.storedEvents()) {
                assertFalse(event.content.contains("Anna") || event.content.contains("Chiara"), "Klartext auf $relay")
                assertTrue(event.kind != 30078, "altes Format auf $relay")
            }
        }
        assertTrue(
            awaitCondition(10_000) { relays.all { r -> r.storedEvents(GroupSyncEngine.KIND_KEY_PACKAGE).none { it.pubkey == b.publicKey } } },
            "KeyPackage von B nicht gelöscht",
        )
        // Nach dem Beitritt erneuern die Geräte ihren Schlüssel: alle in derselben, späteren Epoche.
        assertTrue(awaitCondition(20_000) { listOf(a, b, c).map { member(it).team.epoch }.toSet().size == 1 }, "Epochen weichen ab")
    }

    @Test
    fun `entferntes Geraet bekommt keine Aenderungen mehr`() = runBlocking {
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        a.plan.addMember("Anna")
        val b = device("B")
        join(a, b)
        val c = device("C")
        join(a, c)
        assertTrue(awaitConvergence(listOf(a, b, c), 30_000))

        a.engine!!.removeDevice(c.publicKey)
        assertTrue(awaitCondition(15_000) { c.teamState is TeamState.Removed }, "C merkt die Entfernung nicht")
        assertTrue(awaitCondition(15_000) { member(b).team.members.size == 2 }, "B sieht C noch")

        a.plan.addMember("Geheim")
        assertTrue(awaitCondition(15_000) { b.state.members().any { it.name == "Geheim" } })
        Thread.sleep(1_500)
        assertTrue(c.state.members().none { it.name == "Geheim" }, "entferntes Gerät liest weiter mit")
        // Was C schon gesehen hat, bleibt auf C (dokumentierte Grenze).
        assertTrue(c.state.members().any { it.name == "Anna" })
    }

    @Test
    fun `Admin sperrt den Plan, Mitglieder tragen nur noch Wuensche ein, Verstoesse verschwinden ueberall`() = runBlocking {
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        val anna = a.plan.addMember("Anna")
        a.plan.setShift(anna, week.days[0], "F")
        val b = device("B")
        join(a, b)
        assertTrue(awaitConvergence(listOf(a, b), 30_000))

        val notAdmin = assertThrows<TeamOperationException> { b.engine!!.setPlanLock(true, week.sunday) }
        assertEquals(TeamOperationException.Reason.NOT_ADMIN, notAdmin.reason)
        a.engine!!.setPlanLock(true, week.sunday)
        assertEquals(week.sunday, member(a).team.planLock?.until)
        assertTrue(awaitCondition(15_000) { b.plan.access.value.lock?.until == week.sunday }, "B kennt die Sperre nicht")

        // B ist Mitglied: in der gesperrten Woche nur Wünsche, danach alles. A ist Admin.
        assertThrows<PlanLockedException> { b.plan.setShift(anna, week.days[1], "S") }
        b.plan.setWish(anna, week.days[1], Wish.DAY_OFF)
        b.plan.setShift(anna, week.next().days[0], "N")
        a.plan.setShift(anna, week.days[2], "S")
        assertTrue(
            awaitCondition(15_000) {
                a.state.wish(anna, week.days[1]) == Wish.DAY_OFF && a.state.shift(anna, week.next().days[0]) == "N" &&
                    b.state.shift(anna, week.days[2]) == "S"
            },
            "Wunsch, offene Woche oder Admin-Änderung kam nicht an",
        )

        // B ist offline, während A die Sperre auf die nächste Woche ausdehnt, und ändert dort.
        b.stopEngine()
        a.engine!!.setPlanLock(true, week.next().sunday)
        assertEquals(2, member(a).team.planLock?.segments?.size)
        b.plan.setShift(anna, week.next().days[0], "U")
        val discarded = b.scope.async(start = CoroutineStart.UNDISPATCHED) { b.plan.discarded.first() }
        b.restart()
        assertEquals(1, withTimeoutOrNull(20_000) { discarded.await() }, "B meldet die verworfene Änderung nicht")
        assertTrue(awaitConvergence(listOf(a, b), 30_000), "A und B konvergieren nach der Sperre nicht")
        assertEquals("N", a.state.shift(anna, week.next().days[0]))
        assertEquals("N", b.state.shift(anna, week.next().days[0]), "Abgleich holt den gültigen Stand zurück")

        // Öffnen: B darf wieder alles.
        a.engine!!.setPlanLock(false)
        assertTrue(awaitCondition(15_000) { b.plan.access.value.lock == null }, "B merkt das Öffnen nicht")
        b.plan.setShift(anna, week.days[1], "S")
        assertTrue(awaitCondition(15_000) { a.state.shift(anna, week.days[1]) == "S" })
    }

    @Test
    fun `Austritt wird von einem Admin bestaetigt, letzter Admin muss zuerst uebergeben`() = runBlocking {
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        a.plan.addMember("Anna")
        val b = device("B")
        join(a, b)
        val c = device("C")
        join(a, c)
        assertTrue(awaitConvergence(listOf(a, b, c), 30_000))

        c.engine!!.leave()
        assertIs<TeamState.None>(c.teamState)
        assertTrue(awaitCondition(20_000) { member(a).team.members.size == 2 && member(b).team.members.size == 2 }, "Austritt nicht bestätigt")

        val error = assertThrows<TeamOperationException> { a.engine!!.leave() }
        assertEquals(TeamOperationException.Reason.LAST_ADMIN, error.reason)

        a.engine!!.setAdmin(b.publicKey, admin = true)
        assertTrue(awaitCondition(15_000) { b.publicKey in member(b).team.admins }, "B ist kein Admin")
        a.engine!!.leave()
        assertTrue(awaitCondition(20_000) { member(b).team.members == listOf(b.publicKey) }, "Austritt von A nicht bestätigt")
        assertEquals(listOf(b.publicKey), member(b).team.admins)
    }

    @Test
    fun `Aenderungen ohne Verbindung gehen nach dem Neustart raus`() = runBlocking {
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        val anna = a.plan.addMember("Anna")
        val b = device("B")
        join(a, b)
        assertTrue(awaitConvergence(listOf(a, b), 30_000))

        b.stopEngine()
        b.plan.setShift(anna, week.days[4], "N")
        assertEquals(1, b.plan.pendingEntries().size)
        b.restart()
        assertTrue(awaitCondition(20_000) { a.state.shift(anna, week.days[4]) == "N" }, "Offline-Änderung kam nicht an")
        assertTrue(awaitCondition(10_000) { b.plan.pendingEntries().isEmpty() })
    }

    @Test
    fun `verpasste Nachrichten werden ueber die Uebersicht nachgeholt`() = runBlocking {
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        val anna = a.plan.addMember("Anna")
        val b = device("B")
        join(a, b)
        assertTrue(awaitConvergence(listOf(a, b), 30_000))
        // Schlüsselerneuerungen abwarten, damit der Datenverlust unten keine Commits trifft.
        assertTrue(awaitCondition(20_000) { member(a).team.epoch == member(b).team.epoch })
        Thread.sleep(1_000)

        b.stopEngine()
        a.plan.setShift(anna, week.days[3], "S")
        assertTrue(awaitCondition(10_000) { a.plan.pendingEntries().isEmpty() })
        // Die Relays verlieren alles (z. B. Aufbewahrungsfrist abgelaufen).
        relays.forEach { it.wipe() }
        // Übersicht sofort fällig (sonst alle paar Stunden).
        b.team.updateRecord { it.copy(lastOverviewAt = 0) }
        b.restart()
        assertTrue(awaitCondition(20_000) { b.state.shift(anna, week.days[3]) == "S" }, "Verlust nicht repariert")
    }

    @Test
    fun `Einladung kommt auch ueber Relays mit Anmeldung an`() = runBlocking {
        relays.forEach { it.authRequiredKinds = setOf(GroupSyncEngine.KIND_GIFT_WRAP) }
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        a.plan.addMember("Anna")
        val b = device("B")
        join(a, b)
        assertTrue(awaitConvergence(listOf(a, b), 30_000))
        assertTrue(relays.sumOf { it.authentications.get() } > 0, "keine Anmeldung")
    }

    @Test
    fun `Geraet ohne KeyPackage oder doppelt hinzufuegen schlaegt sauber fehl`() = runBlocking {
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        val stranger = device("X")
        stranger.startJoining() // KeyPackage nie veröffentlicht: keine Engine
        val missing = assertThrows<TeamOperationException> { a.engine!!.addDevice(stranger.publicKey) }
        assertEquals(TeamOperationException.Reason.KEY_PACKAGE_NOT_FOUND, missing.reason)
        val self = assertThrows<TeamOperationException> { a.engine!!.addDevice(a.publicKey) }
        assertEquals(TeamOperationException.Reason.ALREADY_MEMBER, self.reason)
    }

    @Test
    fun `unterbrochener eigener Commit wird nach dem Neustart uebernommen oder verworfen`() = runBlocking {
        val a = device("A")
        a.createTeam("Team")
        a.startEngine()
        awaitLive(a)
        val anna = a.plan.addMember("Anna")
        val b = device("B")
        join(a, b)
        assertTrue(awaitConvergence(listOf(a, b), 30_000))
        assertTrue(awaitCondition(20_000) { member(a).team.epoch == member(b).team.epoch })
        val groupId = member(a).team.groupId

        // 1. Absturz nach dem Veröffentlichen: Der Commit liegt auf den Relays und wird übernommen.
        a.stopEngine()
        val before = member(a).team.epoch
        val published = a.team.mls { it.selfUpdate(groupId) }
        val event = Nip01.parseEvent(Json.parseToJsonElement(published))!!
        a.team.updateRecord { it.copy(pendingCommit = event.id) }
        relays.forEach { it.publish(event) }
        a.restart()
        assertTrue(awaitCondition(20_000) { member(a).team.epoch == before + 1 && member(b).team.epoch == before + 1 }, "Commit nicht übernommen")
        assertEquals(null, a.team.record.value!!.pendingCommit)

        // 2. Absturz vor dem Veröffentlichen: Der Commit wird verworfen, die Epoche bleibt.
        a.stopEngine()
        val lost = a.team.mls { it.selfUpdate(groupId) }
        a.team.updateRecord { it.copy(pendingCommit = Nip01.parseEvent(Json.parseToJsonElement(lost))!!.id) }
        a.restart()
        assertTrue(awaitCondition(20_000) { a.team.record.value!!.pendingCommit == null }, "Commit nicht verworfen")
        assertEquals(before + 1, member(a).team.epoch)

        // Danach geht der Austausch normal weiter.
        a.plan.setShift(anna, week.days[5], "F")
        assertTrue(awaitCondition(20_000) { b.state.shift(anna, week.days[5]) == "F" }, "A sendet nicht mehr")
        b.plan.setShift(anna, week.days[6], "S")
        assertTrue(awaitCondition(20_000) { a.state.shift(anna, week.days[6]) == "S" }, "A liest nicht mehr")
    }
}
