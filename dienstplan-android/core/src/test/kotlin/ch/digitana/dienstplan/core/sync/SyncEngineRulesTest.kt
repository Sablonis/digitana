package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crypto.PacketCipher
import ch.digitana.dienstplan.core.crypto.TeamKeys
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.nostr.NostrEvent
import ch.digitana.dienstplan.core.testing.ManualClock
import ch.digitana.dienstplan.core.testing.ScriptedTransport
import ch.digitana.dienstplan.core.testing.ScriptedTransport.Reply
import ch.digitana.dienstplan.core.testing.SimulatedDevice
import ch.digitana.dienstplan.core.testing.SimulatedDevice.Companion.awaitCondition
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deterministische Tests der Sync-Regeln mit einer Relay-Attrappe und manueller Uhr:
 * OK-Auswertung, Wiederholungen, Sekundenregel, Anti-Entropie, Live-Events, Eingabeprüfung.
 */
@Timeout(60)
class SyncEngineRulesTest {

    private val secret = TeamSecret(ByteArray(32) { 3 })
    private val keys = TeamKeys.derive(secret)
    private val clock = ManualClock(1_790_000_000_000L)
    private val transport = ScriptedTransport()
    private var device: SimulatedDevice? = null
    private val otherDevice = "00000000000000bb"

    @AfterEach
    fun tearDown() = runBlocking {
        device?.stop()
        transport.shutdown()
        Unit
    }

    private suspend fun startDevice(prepare: suspend (SimulatedDevice) -> Unit = {}): SimulatedDevice {
        val d = SimulatedDevice("A", secret, listOf("wss://relay.test"), transport, clock)
        prepare(d)
        device = d
        d.start()
        assertTrue(awaitCondition(5_000) { d.engine.status.value.isLive }, "Relay nicht live")
        return d
    }

    private fun decrypt(event: NostrEvent): BucketCodec.Result.Ok {
        val dTag = event.tags.single()[1]
        val result = BucketCodec.decrypt(keys.packetCipher, dTag, event.content, clock.millis, keys::dTag)
        assertIs<BucketCodec.Result.Ok>(result)
        return result
    }

    private fun foreignTeamEvent(entries: Map<String, Entry>, createdAt: Long, bucket: String = Buckets.TEAM): NostrEvent {
        val dTag = keys.dTag(bucket)
        val content = BucketCodec.encrypt(keys.packetCipher, dTag, bucket, LwwMap.of(entries))
        return Nip01.sign(keys, createdAt, NostrEvent.KIND_APP_DATA, listOf(listOf("d", dTag)), content)
    }

    private val second get() = clock.millis / 1000

    @Test
    fun `abgelehntes Event wird in der naechsten Sekunde mit neuerem created_at wiederholt`() = runBlocking {
        transport.onEvent = { _, attempt -> if (attempt == 1) Reply.Reject("replaced: have newer event") else Reply.Accept }
        val d = startDevice()
        d.repository.addMember("Anna")
        assertTrue(awaitCondition(5_000) { transport.published.size == 1 })
        delay(400)
        assertEquals(1, transport.published.size, "keine zweite Veröffentlichung in derselben Sekunde")
        clock.advance(1_000)
        assertTrue(awaitCondition(5_000) { transport.published.size == 2 })
        val (first, retry) = transport.published
        assertTrue(retry.createdAt > first.createdAt)
        assertEquals(first.tags, retry.tags)
        assertTrue(awaitCondition(5_000) { d.engine.status.value.pendingBuckets == 0 })
        assertEquals(1, d.engine.diagnostics.value.single().eventsRejected)
        assertEquals(1, d.engine.diagnostics.value.single().eventsAccepted)
    }

    @Test
    fun `hoechstens fuenf Versuche pro Stand, eine neue Aenderung startet neu`() = runBlocking {
        transport.onEvent = { _, _ -> Reply.Reject("error: internal") }
        val d = startDevice()
        d.repository.addMember("Anna")
        repeat(10) {
            clock.advance(1_000)
            delay(250)
        }
        assertEquals(5, transport.published.size)
        assertTrue(d.engine.diagnostics.value.single().lastError!!.contains("error: internal"))
        assertEquals(0, d.engine.status.value.pendingBuckets, "aufgegebene Buckets zählen nicht als ausstehend")

        d.repository.addMember("Ben")
        repeat(3) {
            clock.advance(1_000)
            delay(250)
        }
        assertTrue(transport.published.size > 5)
    }

    @Test
    fun `dauerhafte Ablehnung wird nicht wiederholt`() = runBlocking {
        transport.onEvent = { _, _ -> Reply.Reject("blocked: pubkey not allowed") }
        val d = startDevice()
        d.repository.addMember("Anna")
        repeat(4) {
            clock.advance(1_000)
            delay(200)
        }
        assertEquals(1, transport.published.size)
    }

    @Test
    fun `hoechstens ein Event pro Bucket und Sekunde, created_at driftet nicht`() = runBlocking {
        val d = startDevice()
        val start = second
        repeat(10) { d.repository.addMember("Person $it") }
        delay(500)
        assertEquals(1, transport.published.size)

        clock.advance(1_000)
        assertTrue(awaitCondition(5_000) { transport.published.size == 2 })
        delay(300)
        assertEquals(2, transport.published.size)
        assertEquals(10, decrypt(transport.published[1]).entries.size, "zweites Event enthält alle Änderungen")

        repeat(3) {
            d.repository.addMember("Später $it")
            clock.advance(1_000)
            delay(300)
        }
        assertTrue(awaitCondition(5_000) { d.engine.status.value.pendingBuckets == 0 })
        val createdAts = transport.published.map { it.createdAt }
        assertEquals(createdAts.sorted(), createdAts, "streng steigend")
        assertEquals(createdAts.size, createdAts.toSet().size)
        assertTrue(createdAts.last() <= second, "created_at liegt nie in der Zukunft: ${createdAts.last()} > $second")
        assertEquals(start, createdAts.first())
    }

    @Test
    fun `Live-Event ohne lokal bekannte Eintraege fuehrt zu erneutem Senden des Zusammengefuehrten`() = runBlocking {
        val d = startDevice()
        d.repository.addMember("Anna")
        assertTrue(awaitCondition(5_000) { transport.published.size == 1 && d.engine.status.value.pendingBuckets == 0 })

        clock.advance(2_000)
        val ben = PlanKeys.member("00000000000000b0")
        val live = foreignTeamEvent(mapOf(ben to Entry("Ben", clock.millis, otherDevice)), createdAt = second)
        transport.pushLive(live)
        assertTrue(awaitCondition(5_000) { d.state.members().size == 2 })
        assertTrue(awaitCondition(5_000) { transport.published.size == 2 })
        val merged = transport.published[1]
        assertTrue(merged.createdAt > live.createdAt)
        assertEquals(setOf("Anna", "Ben"), decrypt(merged).entries.values.map { it.value }.toSet())
    }

    @Test
    fun `Live-Event mit vollstaendigem Stand loest keinen Versand aus`() = runBlocking {
        val d = startDevice()
        d.repository.addMember("Anna")
        assertTrue(awaitCondition(5_000) { transport.published.size == 1 && d.engine.status.value.pendingBuckets == 0 })
        clock.advance(2_000)
        val sameState = foreignTeamEvent(d.state.bucket(Buckets.TEAM).entries, createdAt = second)
        transport.pushLive(sameState)
        delay(600)
        assertEquals(1, transport.published.size)
    }

    @Test
    fun `Anti-Entropie nach EOSE - Relay mit altem Stand bekommt den zusammengefuehrten`() = runBlocking {
        val ben = PlanKeys.member("00000000000000b0")
        transport.storedOnConnect += foreignTeamEvent(mapOf(ben to Entry("Ben", clock.millis - 50_000, otherDevice)), createdAt = second - 60)
        val d = startDevice { it.repository.addMember("Anna") }
        assertTrue(awaitCondition(5_000) { transport.published.size == 1 })
        assertEquals(setOf("Anna", "Ben"), decrypt(transport.published[0]).entries.values.map { it.value }.toSet())
        assertEquals(setOf("Anna", "Ben"), d.state.members().map { it.name }.toSet())
    }

    @Test
    fun `Anti-Entropie nach EOSE - gleicher Stand wird nicht erneut gesendet`() = runBlocking {
        val anna = PlanKeys.member("00000000000000a0")
        val entries = mapOf(anna to Entry("Anna", clock.millis - 50_000, otherDevice))
        transport.storedOnConnect += foreignTeamEvent(entries, createdAt = second - 60)
        val d = startDevice()
        assertTrue(awaitCondition(5_000) { d.state.members().size == 1 })
        delay(600)
        assertEquals(0, transport.published.size)
        assertEquals(0, d.engine.status.value.pendingBuckets)
    }

    @Test
    fun `fremde, manipulierte und nicht entschluesselbare Events werden still verworfen`() = runBlocking {
        val stranger = TeamKeys.derive(TeamSecret(ByteArray(32) { 9 }))
        val dTag = keys.dTag(Buckets.TEAM)
        val garbageContent = java.util.Base64.getEncoder().encodeToString(
            PacketCipher(ByteArray(32) { 1 }).encrypt("{\"v\":2,\"b\":\"team\",\"e\":[]}".toByteArray(), BucketCodec.associatedData(dTag)),
        )
        val undecryptable = Nip01.sign(keys, second - 30, NostrEvent.KIND_APP_DATA, listOf(listOf("d", dTag)), garbageContent)
        val valid = foreignTeamEvent(mapOf(PlanKeys.member("00000000000000a0") to Entry("Anna", clock.millis - 1_000, otherDevice)), second - 20)
        val badSignature = valid.copy(sig = valid.sig.reversed())
        val foreignKey = Nip01.sign(stranger, second - 10, NostrEvent.KIND_APP_DATA, listOf(listOf("d", dTag)), valid.content)
        val wrongKind = foreignTeamEvent(emptyMap(), second).let { Nip01.sign(keys, it.createdAt, 1, it.tags, it.content) }
        transport.storedOnConnect += listOf(undecryptable, badSignature, foreignKey, wrongKind)

        val d = startDevice { it.repository.addMember("Lokal") }
        assertTrue(awaitCondition(5_000) { transport.published.size == 1 })
        val diagnostics = d.engine.diagnostics.value.single()
        assertEquals(1, diagnostics.undecryptable)
        assertEquals(3, diagnostics.invalid)
        assertEquals(listOf("Lokal"), d.state.members().map { it.name })
        // Das unlesbare Event wird durch den eigenen Stand ersetzt: neueres created_at.
        assertTrue(transport.published[0].createdAt > undecryptable.createdAt)
    }

    @Test
    fun `fehlendes OK zaehlt als Fehlversuch`() = runBlocking {
        transport.onEvent = { _, attempt -> if (attempt == 1) Reply.Silent else Reply.Accept }
        val d = startDevice()
        d.repository.addMember("Anna")
        assertTrue(awaitCondition(5_000) { transport.published.size == 1 })
        clock.advance(SimulatedDevice.FAST.okTimeoutMillis + 1_000)
        assertTrue(awaitCondition(5_000) { transport.published.size == 2 })
        assertTrue(awaitCondition(5_000) { d.engine.status.value.pendingBuckets == 0 })
        assertTrue(d.engine.diagnostics.value.single().lastError!!.contains("OK"))
    }

    @Test
    fun `Gesamtbudget pro Sekunde, Team-Bucket zuerst`() = runBlocking {
        val d = SimulatedDevice("A", secret, listOf("wss://relay.test"), transport, clock, SimulatedDevice.FAST.copy(maxEventsPerSecond = 5))
        device = d
        // Offline vorbereitet: Team plus elf Wochen, danach erst verbinden (wie nach „Übernehmen“).
        val anna = d.repository.addMember("Anna")
        repeat(11) { d.repository.setShift(anna, java.time.LocalDate.of(2026, 1, 5).plusWeeks(it.toLong()), ch.digitana.dienstplan.core.crdt.Shift.FRUEH) }
        d.start()
        assertTrue(awaitCondition(5_000) { transport.published.size == 5 })
        delay(400)
        assertEquals(5, transport.published.size)
        assertEquals(keys.dTag(Buckets.TEAM), transport.published.first().tags.single()[1], "Team-Bucket zuerst")
        clock.advance(1_000)
        assertTrue(awaitCondition(5_000) { transport.published.size == 10 })
        clock.advance(1_000)
        assertTrue(awaitCondition(5_000) { transport.published.size == 12 })
        assertEquals(3, transport.published.map { it.createdAt }.toSet().size)
        assertTrue(awaitCondition(5_000) { d.engine.status.value.pendingBuckets == 0 })
    }

    @Test
    fun `Paging endet, auch wenn das Relay alles auf einmal liefert`() = runBlocking {
        repeat(25) { i ->
            val date = java.time.LocalDate.of(2026, 1, 5).plusWeeks(i.toLong())
            val bucket = ch.digitana.dienstplan.core.crdt.WeekId.of(date).bucketName
            val key = PlanKeys.shift("00000000000000a0", date)
            transport.storedOnConnect += foreignTeamEvent(mapOf(key to Entry("F", clock.millis - 10_000, otherDevice)), second - 100 + i, bucket)
        }
        val d = startDevice()
        assertEquals(25, d.state.entryCount)
        delay(300)
        assertEquals(0, transport.published.size)
    }
}
