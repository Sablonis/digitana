package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crypto.PacketCipher
import ch.digitana.dienstplan.core.crypto.TeamSecret
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureFileStoreTest {

    /** Software-Ersatz für den Android-Keystore. */
    private class SoftwareKeyWrapper : KeyWrapper {
        var key: ByteArray? = ByteArray(32) { 42 }
        override fun wrap(plainKey: ByteArray) = PacketCipher(key!!).encrypt(plainKey, AAD)
        override fun unwrap(wrapped: ByteArray): ByteArray {
            val k = key ?: throw SecureStorageException(SecureStorageException.Reason.KEY_UNAVAILABLE, "kein Schlüssel")
            return PacketCipher(k).decrypt(wrapped, AAD)
                ?: throw SecureStorageException(SecureStorageException.Reason.KEY_UNAVAILABLE, "Schlüssel passt nicht")
        }
        override fun destroy() {
            key = null
        }
        companion object {
            val AAD = "test".toByteArray()
        }
    }

    @TempDir
    lateinit var dir: File

    @Test
    fun `Hin und zurueck, Klartext liegt nie auf der Platte`() {
        val store = SecureFileStore(dir, SoftwareKeyWrapper())
        val secret = "geheimes-team-DP2-xyz".toByteArray()
        store.write("team.bin", secret)
        assertContentEquals(secret, store.read("team.bin"))
        dir.listFiles()!!.forEach { file ->
            val content = file.readBytes().decodeToString()
            assertFalse(content.contains("geheimes"), "Klartext in ${file.name}")
        }
        assertTrue(File(dir, "dek.bin").exists())
        assertFalse(File(dir, "team.bin.tmp").exists())
    }

    @Test
    fun `nach Neustart mit gleichem Keystore-Schluessel lesbar`() {
        val wrapper = SoftwareKeyWrapper()
        SecureFileStore(dir, wrapper).write("plan.bin", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), SecureFileStore(dir, wrapper).read("plan.bin"))
    }

    @Test
    fun `manipulierte Datei wird erkannt`() {
        val store = SecureFileStore(dir, SoftwareKeyWrapper())
        store.write("plan.bin", ByteArray(100) { 7 })
        val file = File(dir, "plan.bin")
        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val error = assertThrows<SecureStorageException> { SecureFileStore(dir, SoftwareKeyWrapper()).read("plan.bin") }
        assertEquals(SecureStorageException.Reason.CORRUPTED, error.reason)
    }

    @Test
    fun `vertauschte Dateien werden erkannt (Dateiname in der AAD)`() {
        val store = SecureFileStore(dir, SoftwareKeyWrapper())
        store.write("team.bin", "team".toByteArray())
        store.write("plan.bin", "plan".toByteArray())
        File(dir, "team.bin").copyTo(File(dir, "plan.bin"), overwrite = true)
        val error = assertThrows<SecureStorageException> { SecureFileStore(dir, SoftwareKeyWrapper()).read("plan.bin") }
        assertEquals(SecureStorageException.Reason.CORRUPTED, error.reason)
    }

    @Test
    fun `fehlender Keystore-Schluessel wird gemeldet`() {
        val wrapper = SoftwareKeyWrapper()
        SecureFileStore(dir, wrapper).write("team.bin", "x".toByteArray())
        wrapper.key = ByteArray(32) { 1 } // z. B. Keystore zurückgesetzt
        val error = assertThrows<SecureStorageException> { SecureFileStore(dir, wrapper).read("team.bin") }
        assertEquals(SecureStorageException.Reason.KEY_UNAVAILABLE, error.reason)
        File(dir, "dek.bin").delete()
        val missing = assertThrows<SecureStorageException> { SecureFileStore(dir, wrapper).read("team.bin") }
        assertEquals(SecureStorageException.Reason.KEY_UNAVAILABLE, missing.reason)
    }

    @Test
    fun `wipe loescht alles inklusive Hauptschluessel`() {
        val wrapper = SoftwareKeyWrapper()
        val store = SecureFileStore(dir, wrapper)
        store.write("team.bin", "x".toByteArray())
        store.wipe()
        assertTrue(dir.listFiles()!!.isEmpty())
        assertNull(wrapper.key)
        assertNull(store.read("team.bin"))
    }

    @Test
    fun `Team-Speicher im Rundlauf`() {
        val files = SecureFileStore(dir, SoftwareKeyWrapper())
        val teams = EncryptedTeamStore(files)
        assertNull(teams.load())
        val team = Team(TeamSecret.generate(), "0123456789abcdef", 1234)
        teams.save(team)
        assertEquals(team, teams.load())
        teams.clear()
        assertNull(teams.load())
    }

    @Test
    fun `ungueltige Dateinamen werden abgelehnt`() {
        val store = SecureFileStore(dir, SoftwareKeyWrapper())
        for (name in listOf("../x", "dek.bin", "a/b", "", "x.tmp", "A.bin")) {
            assertThrows<IllegalArgumentException>(name) { store.write(name, ByteArray(1)) }
        }
    }
}
