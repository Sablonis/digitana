package ch.digitana.dienstplan.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.digitana.dienstplan.core.data.SecureFileStore
import ch.digitana.dienstplan.core.data.SecureStorageException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Läuft auf einem Gerät oder Emulator: ./gradlew connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreKeyWrapperTest {

    private val alias = "dienstplan.test.${System.nanoTime()}"
    private val wrapper = AndroidKeystoreKeyWrapper(preferStrongBox = false, alias = alias)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "keystore-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        wrapper.destroy()
        directory.deleteRecursively()
    }

    @Test
    fun datenschluesselHinUndZurueck() {
        val dek = ByteArray(32) { it.toByte() }
        val wrapped = wrapper.wrap(dek)
        assertFalse(wrapped.copyOfRange(wrapped.size - 32, wrapped.size).contentEquals(dek))
        assertArrayEquals(dek, wrapper.unwrap(wrapped))
    }

    @Test
    fun manipuliertesPaketWirdAbgelehnt() {
        val wrapped = wrapper.wrap(ByteArray(32) { 7 })
        wrapped[wrapped.size - 1] = (wrapped[wrapped.size - 1].toInt() xor 1).toByte()
        assertThrows(SecureStorageException::class.java) { wrapper.unwrap(wrapped) }
    }

    @Test
    fun geloeschterSchluesselWirdGemeldet() {
        val wrapped = wrapper.wrap(ByteArray(32) { 7 })
        wrapper.destroy()
        val error = assertThrows(SecureStorageException::class.java) { wrapper.unwrap(wrapped) }
        assertEquals(SecureStorageException.Reason.KEY_UNAVAILABLE, error.reason)
    }

    @Test
    fun strongBoxWirdGenutztOderFaelltSauberZurueck() {
        val strongBox = AndroidKeystoreKeyWrapper(preferStrongBox = true, alias = "$alias.sb")
        try {
            val dek = ByteArray(32) { (it * 3).toByte() }
            assertArrayEquals(dek, strongBox.unwrap(strongBox.wrap(dek)))
        } finally {
            strongBox.destroy()
        }
    }

    @Test
    fun verschluesselteDateienAufDemGeraet() {
        val store = SecureFileStore(directory, wrapper)
        store.write("plan.bin", "geheimer Plan".toByteArray())
        assertArrayEquals("geheimer Plan".toByteArray(), SecureFileStore(directory, wrapper).read("plan.bin"))
        directory.listFiles()!!.forEach { file ->
            assertFalse(file.readBytes().decodeToString().contains("geheimer"))
        }
    }
}
