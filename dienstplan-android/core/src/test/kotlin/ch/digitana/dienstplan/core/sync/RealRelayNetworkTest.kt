package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.Shift
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.testing.SimulatedDevice
import ch.digitana.dienstplan.core.testing.SimulatedDevice.Companion.awaitCondition
import ch.digitana.dienstplan.core.testing.SimulatedDevice.Companion.awaitConvergence
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrationstest über die echten öffentlichen Relays (wss://relay.damus.io, wss://nos.lol,
 * wss://relay.primal.net): drei simulierte Geräte, gleichzeitige Änderungen, ein Konflikt auf
 * demselben Feld und ein später beitretendes Gerät. Jeder Lauf nutzt ein neues Zufallsteam.
 *
 * Start: `./gradlew :core:networkTest` (direkte Internetverbindung nötig).
 * Andere Relays: `./gradlew :core:networkTest -Pdienstplan.relays=wss://a,wss://b,wss://c`.
 */
@Tag("network")
@Timeout(240)
class RealRelayNetworkTest {

    private val relays = System.getProperty("dienstplan.relays")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: RelayUrls.DEFAULT
    private val secret = TeamSecret.generate()
    private val devices = mutableListOf<SimulatedDevice>()
    private val config = SyncConfig(okTimeoutMillis = 20_000, backoffBaseMillis = 500, backoffMaxMillis = 5_000)

    private fun device(name: String) =
        SimulatedDevice(name, secret, relays, SecureHttp.newClient(), config = config).also { devices += it }

    @AfterEach
    fun tearDown() = runBlocking {
        devices.forEach { it.stop() }
    }

    @Test
    fun `drei Geraete ueber echte Relays landen beim identischen Stand`() = runBlocking {
        val a = device("A")
        val b = device("B")
        a.start()
        b.start()
        assertTrue(awaitCondition(60_000) { a.engine.status.value.isLive && b.engine.status.value.isLive }, "keine Verbindung zu den Relays")

        val week = WeekId.of(LocalDate.now())
        val anna = a.repository.addMember("Anna")
        val ben = a.repository.addMember("Ben")
        assertTrue(awaitCondition(60_000) { b.state.members().size == 2 }, "B sieht die Mitarbeitenden nicht")

        val conflictKey = PlanKeys.shift(anna, week.days[2])
        var writtenByA: Entry? = null
        var writtenByB: Entry? = null
        coroutineScope {
            launch {
                a.repository.setShift(anna, week.days[0], Shift.FRUEH)
                a.repository.setShift(anna, week.days[2], Shift.NACHT)
                writtenByA = a.state.entry(conflictKey)
            }
            launch {
                b.repository.setShift(ben, week.days[1], Shift.SPAET)
                b.repository.setShift(anna, week.days[2], Shift.URLAUB)
                writtenByB = b.state.entry(conflictKey)
            }
        }
        assertTrue(awaitConvergence(listOf(a, b), 120_000), "A und B konvergieren nicht")
        assertEquals(Entry.newer(writtenByA, writtenByB), a.state.entry(conflictKey))

        val c = device("C")
        c.start()
        assertTrue(awaitConvergence(listOf(a, b, c), 120_000), "Später beigetretenes Gerät C hat nicht den vollen Stand")
        assertEquals(a.state, b.state)
        assertEquals(a.state, c.state)

        for (d in devices) {
            println("${d.name}: " + d.engine.diagnostics.value.joinToString { "${it.url} ${it.state} ${it.lastError ?: ""}" })
        }
    }
}
