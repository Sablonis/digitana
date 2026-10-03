package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crypto.PacketCipher
import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Schützt den Datenschlüssel (DEK). Auf Android: AES-256-GCM-Schlüssel im Keystore
 * (hardwaregestützt, nicht exportierbar). In Tests: Software-Schlüssel.
 */
interface KeyWrapper {
    fun wrap(plainKey: ByteArray): ByteArray

    /** @throws SecureStorageException wenn der Schlüssel fehlt oder das Paket manipuliert ist. */
    fun unwrap(wrapped: ByteArray): ByteArray

    /** Löscht den Hauptschlüssel endgültig. */
    fun destroy()
}

class SecureStorageException(val reason: Reason, message: String, cause: Throwable? = null) :
    IOException(message, cause) {
    enum class Reason {
        /** Hauptschlüssel fehlt oder passt nicht (z. B. nach Zurücksetzen des Keystores). */
        KEY_UNAVAILABLE,
        /** Datei manipuliert oder beschädigt. */
        CORRUPTED,
    }
}

/**
 * Verschlüsselte Dateien nach dem Umschlagverfahren: Ein zufälliger 256-Bit-Datenschlüssel
 * (DEK) verschlüsselt die Dateien mit AES-256-GCM; der DEK selbst liegt nur mit dem
 * Keystore-Schlüssel verschlüsselt auf der Platte. Der Dateiname ist Teil der AAD, damit
 * sich Dateien nicht gegeneinander austauschen lassen. Geschrieben wird atomar
 * (temporäre Datei, fsync, Umbenennen).
 */
class SecureFileStore(private val directory: File, private val keyWrapper: KeyWrapper) {

    private var dataCipher: PacketCipher? = null

    @Synchronized
    fun write(name: String, plaintext: ByteArray) {
        requireValidName(name)
        val ciphertext = cipher(createIfMissing = true).encrypt(plaintext, aad(name))
        atomicWrite(File(directory, name), FILE_MAGIC + ciphertext)
    }

    /** @return `null`, wenn die Datei nicht existiert. */
    @Synchronized
    fun read(name: String): ByteArray? {
        requireValidName(name)
        val file = File(directory, name)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < FILE_MAGIC.size || !bytes.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            throw SecureStorageException(SecureStorageException.Reason.CORRUPTED, "Unbekanntes Dateiformat")
        }
        val body = bytes.copyOfRange(FILE_MAGIC.size, bytes.size)
        return cipher(createIfMissing = false).decrypt(body, aad(name))
            ?: throw SecureStorageException(SecureStorageException.Reason.CORRUPTED, "Datei manipuliert oder beschädigt")
    }

    @Synchronized
    fun delete(name: String) {
        requireValidName(name)
        File(directory, name).delete()
    }

    /** Löscht alle Dateien, den DEK und den Hauptschlüssel. */
    @Synchronized
    fun wipe() {
        dataCipher = null
        directory.listFiles()?.forEach { it.delete() }
        runCatching { keyWrapper.destroy() }
    }

    private fun cipher(createIfMissing: Boolean): PacketCipher {
        dataCipher?.let { return it }
        directory.mkdirs()
        val keyFile = File(directory, KEY_FILE)
        val dek: ByteArray = if (keyFile.exists()) {
            val bytes = keyFile.readBytes()
            if (bytes.size < KEY_MAGIC.size || !bytes.copyOfRange(0, KEY_MAGIC.size).contentEquals(KEY_MAGIC)) {
                throw SecureStorageException(SecureStorageException.Reason.CORRUPTED, "Schlüsseldatei beschädigt")
            }
            keyWrapper.unwrap(bytes.copyOfRange(KEY_MAGIC.size, bytes.size))
        } else {
            if (!createIfMissing) {
                throw SecureStorageException(SecureStorageException.Reason.KEY_UNAVAILABLE, "Datenschlüssel fehlt")
            }
            val fresh = SecureRandomBytes.next(PacketCipher.KEY_SIZE)
            atomicWrite(keyFile, KEY_MAGIC + keyWrapper.wrap(fresh))
            fresh
        }
        if (dek.size != PacketCipher.KEY_SIZE) {
            throw SecureStorageException(SecureStorageException.Reason.CORRUPTED, "Datenschlüssel hat falsche Länge")
        }
        return PacketCipher(dek).also {
            dek.fill(0)
            dataCipher = it
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        directory.mkdirs()
        val temp = File(directory, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun aad(name: String): ByteArray = "dienstplan-file/v1/$name".toByteArray(Charsets.UTF_8)

    private fun requireValidName(name: String) {
        require(name.matches(Regex("[a-z0-9_.-]{1,40}")) && name != KEY_FILE && !name.endsWith(".tmp")) {
            "Ungültiger Dateiname"
        }
    }

    companion object {
        private const val KEY_FILE = "dek.bin"
        private val FILE_MAGIC = "DPF1".toByteArray(Charsets.US_ASCII)
        private val KEY_MAGIC = "DPK1".toByteArray(Charsets.US_ASCII)
    }
}

/** [PlanStore] auf Basis von [SecureFileStore]. */
class EncryptedPlanStore(private val files: SecureFileStore) : PlanStore {
    override fun load(): PlanSnapshot? = files.read(FILE)?.let { PlanSnapshotCodec.decode(it) }
    override fun save(snapshot: PlanSnapshot) = files.write(FILE, PlanSnapshotCodec.encode(snapshot))
    override fun clear() = files.delete(FILE)

    private companion object {
        const val FILE = "plan.bin"
    }
}
