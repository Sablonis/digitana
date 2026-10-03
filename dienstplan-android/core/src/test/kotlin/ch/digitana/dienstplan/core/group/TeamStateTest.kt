package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.data.SecureFileStore
import ch.digitana.dienstplan.core.testing.SoftwareKeyWrapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TeamStateTest {

    @TempDir
    lateinit var dir: File

    private val relays = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")

    @Test
    fun `gespeicherter Teamzustand hin und zurueck`() {
        val record = GroupRecord(
            mode = GroupMode.MEMBER,
            groupId = "ab".repeat(16),
            nostrGroupId = "cd".repeat(32),
            cursor = 1_790_000_000,
            floor = 1_789_000_000,
            lastOverviewAt = 1_790_000_000_123,
            joinSince = 1_788_000_000,
            keyPackage = """{"kind":30443}""",
            keyPackagePublished = true,
            pendingCommit = "ef".repeat(32),
        )
        assertEquals(record, GroupRecordCodec.decode(GroupRecordCodec.encode(record)))
        val joining = GroupRecord(GroupMode.JOINING, joinSince = 5)
        assertEquals(joining, GroupRecordCodec.decode(GroupRecordCodec.encode(joining)))
        assertThrows<IllegalArgumentException> { GroupRecordCodec.decode("""{"v":1,"mode":"chef"}""".toByteArray()) }
        assertThrows<IllegalArgumentException> { GroupRecordCodec.decode("""{"v":2,"mode":"member"}""".toByteArray()) }
        assertThrows<IllegalArgumentException> { GroupRecord(GroupMode.MEMBER) }
        assertThrows<IllegalArgumentException> { GroupRecord(GroupMode.MEMBER, groupId = "XYZ", nostrGroupId = "cd".repeat(32)) }
    }

    @Test
    fun `Geraeteschluessel hin und zurueck, ohne Ausgabe im Klartext`() {
        val keys = DeviceKeys.generate()
        val restored = DeviceKeysCodec.decode(DeviceKeysCodec.encode(keys))
        assertEquals(keys.publicKey, restored.publicKey)
        assertEquals(keys.publicKey.take(16), restored.deviceId)
        assertEquals("DeviceKeys(***)", keys.toString())
        assertThrows<IllegalArgumentException> { DeviceKeys(ByteArray(32), ByteArray(32)) }
        assertThrows<IllegalArgumentException> { DeviceKeysCodec.decode("""{"v":1,"identity":"00","db":"00"}""".toByteArray()) }
    }

    private fun repository(): TeamRepository {
        val files = SecureFileStore(File(dir, "secure"), sharedWrapper)
        return TeamRepository(EncryptedDeviceKeyStore(files), EncryptedGroupRecordStore(files), File(dir, "mls"), relays)
    }

    private val sharedWrapper = SoftwareKeyWrapper()

    @Test
    fun `Team gruenden, neu laden, zuruecksetzen`() = runBlocking {
        val first = repository()
        first.load()
        assertIs<TeamState.None>(first.state.value)
        val team = first.createTeam("Pflege")
        val member = assertIs<TeamState.Member>(first.state.value)
        assertEquals(listOf(member.me), team.admins)
        assertTrue(member.isAdmin)
        assertThrows<TeamStateException> { first.startJoining() }
        first.close()

        val second = repository()
        second.load()
        val reloaded = assertIs<TeamState.Member>(second.state.value)
        assertEquals(team.groupId, reloaded.team.groupId)
        assertEquals("Pflege", reloaded.team.name)
        assertEquals(member.me, reloaded.me)

        second.reset()
        assertIs<TeamState.None>(second.state.value)
        assertFalse(File(dir, "mls").exists())
        second.close()
        val third = repository()
        third.load()
        assertIs<TeamState.None>(third.state.value)
        third.close()
    }

    @Test
    fun `Beitritt ueberlebt einen Neustart mit demselben Code`() = runBlocking {
        val first = repository()
        first.load()
        val joining = first.startJoining()
        assertEquals(JoinCode.ParseResult.Valid(joining.publicKey), JoinCode.parse(joining.code))
        assertFalse(joining.published)
        assertTrue(first.record.value!!.keyPackage!!.contains("30443"))
        first.close()

        val second = repository()
        second.load()
        assertEquals(joining.code, assertIs<TeamState.Joining>(second.state.value).code)
        assertEquals(joining, second.startJoining())
        second.close()
    }
}
