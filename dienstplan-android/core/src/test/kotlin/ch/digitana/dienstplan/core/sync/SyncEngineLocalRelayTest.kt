package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.Shift
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crypto.TeamKeys
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.testing.FakeRelay
import ch.digitana.dienstplan.core.testing.ManualClock
import ch.digitana.dienstplan.core.testing.SimulatedDevice
import ch.digitana.dienstplan.core.testing.SimulatedDevice.Companion.awaitCondition
import ch.digitana.dienstplan.core.testing.SimulatedDevice.Companion.awaitConvergence
import ch.digitana.dienstplan.core.testing.TestTls
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrationstest mit drei lokalen Nostr-Relays über echtes TLS (localhost):
 * mehrere Geräte, gleichzeitige Änderungen, Konflikte, später Beitritt, Ausfälle.
 */
@Timeout(120)
class SyncEngineLocalRelayTest {

    private val relays = List(3) { FakeRelay("R$it").start() }
    private val urls = relays.map { it.url }
    private val secret = TeamSecret.generate()
    private val keys = TeamKeys.derive(secret)
    private val devices = mutableListOf<SimulatedDevice>()
    private val week = WeekId.of(LocalDate.of(2026, 9, 21))

    private fun device(name: String, config: SyncConfig = SimulatedDevice.FAST, clock: ch.digitana.dienstplan.core.util.Clock = ch.digitana.dienstplan.core.util.Clock.System) =
        SimulatedDevice(name, secret, urls, TestTls.client(), clock, config).also { devices += it }

    @AfterEach
    fun tearDown() = runBlocking {
        devices.forEach { it.stop() }
        relays.forEach { it.close() }
    }

    /** Liest alle Events eines Relays mit dem Team-Schlüssel und führt sie zusammen. */
    private fun relayState(relay: FakeRelay): PlanState {
        val buckets = HashMap<String, LwwMap>()
        for (event in relay.storedEvents()) {
            val dTag = event.tags.single()[1]
            val result = BucketCodec.decrypt(keys.packetCipher, dTag, event.content, System.currentTimeMillis(), keys::dTag)
            if (result is BucketCodec.Result.Ok) buckets[result.bucket] = LwwMap.of(result.entries)
        }
        return PlanState.of(buckets)
    }

    private suspend fun awaitLive(vararg ds: SimulatedDevice, relays: Int = 3) =
        assertTrue(awaitCondition(15_000) { ds.all { it.engine.status.value.liveRelays == relays } }, "nicht alle Relays live")

    @Test
    fun `drei Geraete - gleichzeitige Aenderungen, Konflikt auf demselben Feld, spaeter Beitritt`() = runBlocking {
        val a = device("A")
        val b = device("B")
        a.start()
        b.start()
        awaitLive(a, b)

        val anna = a.repository.addMember("Anna")
        val ben = a.repository.addMember("Ben")
        assertTrue(awaitCondition(15_000) { b.state.members().size == 2 })

        val conflictKey = PlanKeys.shift(anna, week.days[2])
        var writtenByA: Entry? = null
        var writtenByB: Entry? = null
        coroutineScope {
            launch {
                a.repository.setShift(anna, week.days[0], Shift.FRUEH)
                a.repository.setShift(anna, week.days[2], Shift.NACHT)
                writtenByA = a.state.entry(conflictKey)
                a.repository.addMember("Chiara")
            }
            launch {
                b.repository.setShift(ben, week.days[1], Shift.SPAET)
                b.repository.setShift(anna, week.days[2], Shift.URLAUB)
                writtenByB = b.state.entry(conflictKey)
                b.repository.addMember("Dario")
            }
        }
        assertTrue(awaitConvergence(listOf(a, b), 30_000), "A und B konvergieren nicht")

        val winner = Entry.newer(writtenByA, writtenByB)
        assertEquals(winner, a.state.entry(conflictKey), "Last-Writer-Wins mit (Zeitstempel, Geräte-ID)")
        assertEquals(setOf("Anna", "Ben", "Chiara", "Dario"), a.state.members().map { it.name }.toSet())
        assertEquals(Shift.FRUEH, a.state.shift(anna, week.days[0]))
        assertEquals(Shift.SPAET, a.state.shift(ben, week.days[1]))

        // Später beitretendes Gerät
        val c = device("C")
        c.start()
        assertTrue(awaitConvergence(listOf(a, b, c), 30_000), "C holt den Stand nicht vollständig")
        assertEquals(a.state, c.state)
        assertEquals(a.state, b.state)

        // Jedes Relay hat den vollständigen Stand (Anti-Entropie).
        for (relay in relays) {
            assertTrue(awaitCondition(10_000) { relayState(relay) == a.state }, "$relay unvollständig")
        }
    }

    @Test
    fun `Konflikt offline auf demselben Feld - neuerer Eintrag gewinnt ueberall`() = runBlocking {
        val a = device("A")
        val b = device("B")
        a.start()
        b.start()
        awaitLive(a, b)
        val anna = a.repository.addMember("Anna")
        assertTrue(awaitCondition(15_000) { b.state.members().size == 1 })

        relays.forEach {
            it.refuseConnections = true
            it.dropConnections()
        }
        assertTrue(awaitCondition(15_000) { !a.engine.status.value.isLive && !b.engine.status.value.isLive })

        val key = PlanKeys.shift(anna, week.days[3])
        a.repository.setShift(anna, week.days[3], Shift.FRUEH)
        val fromA = a.state.entry(key)!!
        Thread.sleep(5)
        b.repository.setShift(anna, week.days[3], Shift.NACHT)
        val fromB = b.state.entry(key)!!
        b.repository.setShift(anna, week.days[4], Shift.FREI) // weitere Offline-Änderung

        relays.forEach { it.refuseConnections = false }
        a.engine.reconnectNow()
        b.engine.reconnectNow()
        assertTrue(awaitConvergence(listOf(a, b), 30_000))
        assertEquals(Entry.newer(fromA, fromB), a.state.entry(key))
        assertEquals(Shift.FREI, a.state.shift(anna, week.days[4]))
    }

    @Test
    fun `Relay verliert Daten und wird per Anti-Entropie wieder befuellt`() = runBlocking {
        val a = device("A")
        a.start()
        awaitLive(a)
        val anna = a.repository.addMember("Anna")
        a.repository.setShift(anna, week.days[0], Shift.FRUEH)
        a.repository.setShift(anna, week.next().days[0], Shift.SPAET)
        assertTrue(awaitCondition(15_000) { relays.all { relayState(it) == a.state } })

        relays[1].wipe()
        relays[1].dropConnections()
        assertTrue(awaitCondition(15_000) { relays[1].storedEvents().size == 3 && relayState(relays[1]) == a.state })
    }

    @Test
    fun `Relay zuerst nicht erreichbar, spaeter nachgefuellt`() = runBlocking {
        relays[2].refuseConnections = true
        val a = device("A")
        a.start()
        awaitLive(a, relays = 2)
        val diagnostics = a.engine.diagnostics.value.first { it.url == urls[2] }
        assertTrue(diagnostics.state != RelayConnectionState.LIVE)
        assertTrue(awaitCondition(10_000) { a.engine.diagnostics.value.first { it.url == urls[2] }.lastError != null })

        val anna = a.repository.addMember("Anna")
        a.repository.setShift(anna, week.days[5], Shift.URLAUB)
        assertTrue(awaitCondition(15_000) { a.engine.status.value.pendingBuckets == 0 })

        relays[2].refuseConnections = false
        a.engine.reconnectNow()
        awaitLive(a, relays = 3)
        assertTrue(awaitCondition(15_000) { relayState(relays[2]) == a.state })
    }

    @Test
    fun `gleiche Sekunde auf zwei Geraeten - Relays ersetzen nur mit neuerem created_at, alle konvergieren`() = runBlocking {
        // Beide Geräte stehen auf derselben Sekunde; Veröffentlichungen im selben Bucket
        // kollidieren, das Relay behält nach NIP-01 nur eines. Erst die Wiederholung mit
        // neuerem created_at bzw. die Live-Regel bringt alle auf denselben Stand.
        val clock = ManualClock(System.currentTimeMillis() / 1000 * 1000)
        val a = device("A", clock = clock)
        val b = device("B", clock = clock)
        a.start()
        b.start()
        awaitLive(a, b)
        coroutineScope {
            launch { a.repository.addMember("Anna") }
            launch { b.repository.addMember("Ben") }
        }
        // Uhr weiterlaufen lassen, damit Wiederholungen möglich sind
        val ticker = launch {
            while (true) {
                kotlinx.coroutines.delay(200)
                clock.advance(1_000)
            }
        }
        try {
            assertTrue(awaitConvergence(listOf(a, b), 30_000))
            assertEquals(setOf("Anna", "Ben"), a.state.members().map { it.name }.toSet())
            for (relay in relays) {
                assertTrue(awaitCondition(10_000) { relayState(relay) == a.state }, "$relay unvollständig")
                assertEquals(1, relay.storedEvents().size, "pro Bucket genau ein Event")
            }
        } finally {
            ticker.cancel()
        }
    }

    @Test
    fun `Paging holt aeltere Events, wenn ein Relay pro Abfrage begrenzt`() = runBlocking {
        relays.forEach { it.maxLimit = 3 }
        val clock = ManualClock(System.currentTimeMillis())
        val config = SimulatedDevice.FAST.copy(pageSize = 3, pagingThreshold = 3)
        val a = device("A", config, clock)
        a.start()
        awaitLive(a)
        val anna = a.repository.addMember("Anna")
        assertTrue(awaitCondition(10_000) { relays.all { it.storedEvents().size == 1 } })
        repeat(8) { i ->
            clock.advance(1_000) // jedes Event in einer eigenen Sekunde
            a.repository.setShift(anna, week.days[0].plusWeeks(i.toLong()), Shift.FRUEH)
            assertTrue(awaitCondition(10_000) { relays.all { it.storedEvents().size == i + 2 } })
        }
        val createdAts = relays[0].storedEvents().map { it.createdAt }
        assertEquals(9, createdAts.toSet().size, "Testvoraussetzung: jedes Event in eigener Sekunde")
        assertTrue(awaitCondition(10_000) { relays.all { it.storedEvents().size == 9 } })

        val c = device("C", config)
        c.start()
        val converged = awaitConvergence(listOf(a, c), 30_000)
        assertTrue(
            converged,
            "C hat nicht alle Seiten geladen: A=${a.state.buckets.size} C=${c.state.buckets.size} " +
                "A=${a.engine.status.value} C=${c.engine.status.value} C-Diag=${c.engine.diagnostics.value}",
        )
        assertEquals(9, c.state.buckets.size)
    }
}
