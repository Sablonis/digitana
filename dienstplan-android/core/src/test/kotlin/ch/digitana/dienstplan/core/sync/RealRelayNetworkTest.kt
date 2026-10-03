package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.group.GroupSyncConfig
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.testing.GroupDevice
import ch.digitana.dienstplan.core.testing.GroupDevice.Companion.awaitCondition
import ch.digitana.dienstplan.core.testing.GroupDevice.Companion.awaitConvergence
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrationstest über die echten öffentlichen Relays (wss://relay.damus.io, wss://nos.lol,
 * wss://relay.primal.net) mit echter MLS-Verschlüsselung: Team gründen, zwei Geräte per
 * Beitrittscode hinzufügen, gleichzeitige Änderungen mit Konflikt. Jeder Lauf nutzt neue
 * Schlüssel und ein neues Team.
 *
 * Start: `./gradlew :core:networkTest` (direkte Internetverbindung nötig).
 * Andere Relays: `./gradlew :core:networkTest -Pdienstplan.relays=wss://a,wss://b,wss://c`.
 */
@Tag("network")
@Timeout(300)
class RealRelayNetworkTest {

    @TempDir
    lateinit var dir: File

    private val relays = System.getProperty("dienstplan.relays")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: RelayUrls.DEFAULT
    private val devices = mutableListOf<GroupDevice>()
    private val config = GroupDevice.FAST.copy(
        okTimeoutMillis = 20_000,
        backoffBaseMillis = 500,
        backoffMaxMillis = 5_000,
        maxEventsPerSecond = GroupSyncConfig().maxEventsPerSecond,
        commitSettleMillis = GroupSyncConfig().commitSettleMillis,
        opTimeoutMillis = 45_000,
        keyPackageQueryMillis = 10_000,
    )

    private suspend fun device(name: String): GroupDevice =
        GroupDevice(name, relays, dir, config = config, client = SecureHttp.newClient()).also {
            devices += it
            it.load()
        }

    @AfterEach
    fun tearDown() = runBlocking {
        for (d in devices) {
            println("${d.name}: " + (d.engine?.diagnostics?.value?.joinToString { "${it.url} ${it.state} ${it.lastError ?: ""}" } ?: "–"))
            runCatching { d.close() }
        }
    }

    private suspend fun join(admin: GroupDevice, newcomer: GroupDevice) {
        newcomer.startJoining()
        newcomer.startEngine()
        assertTrue(awaitCondition(60_000) { (newcomer.teamState as? TeamState.Joining)?.published == true }, "KeyPackage nicht veröffentlicht")
        admin.engine!!.addDevice(newcomer.publicKey)
        val invite = assertNotNull(newcomer.awaitInvite(90_000), "keine Einladung")
        newcomer.accept(invite)
        assertIs<TeamState.Member>(newcomer.teamState)
    }

    @Test
    fun `drei Geraete ueber echte Relays landen beim identischen Stand`() = runBlocking {
        val a = device("A")
        a.createTeam("Netztest")
        a.startEngine()
        assertTrue(awaitCondition(60_000) { a.engine!!.status.value.isLive }, "keine Verbindung zu den Relays")
        val week = WeekId.of(LocalDate.now())
        val anna = a.plan.addMember("Anna")
        val ben = a.plan.addMember("Ben")

        val b = device("B")
        join(a, b)
        assertTrue(awaitCondition(90_000) { b.state.members().size == 2 }, "B sieht die Mitarbeitenden nicht")

        val conflictKey = PlanKeys.shift(anna, week.days[2])
        var writtenByA: Entry? = null
        var writtenByB: Entry? = null
        coroutineScope {
            launch {
                a.plan.setShift(anna, week.days[0], "F")
                a.plan.setShift(anna, week.days[2], "N")
                writtenByA = a.state.entry(conflictKey)
            }
            launch {
                b.plan.setShift(ben, week.days[1], "S")
                b.plan.setShift(anna, week.days[2], "U")
                writtenByB = b.state.entry(conflictKey)
            }
        }
        assertTrue(awaitConvergence(listOf(a, b), 120_000), "A und B konvergieren nicht")
        assertEquals(Entry.newer(writtenByA, writtenByB), a.state.entry(conflictKey))

        val c = device("C")
        join(a, c)
        assertTrue(awaitConvergence(listOf(a, b, c), 120_000), "Später beigetretenes Gerät C hat nicht den vollen Stand")
        assertEquals(a.state, c.state)
    }
}
