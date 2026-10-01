package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.data.SecureFileStore
import ch.digitana.dienstplan.core.util.Hex
import ch.digitana.dienstplan.mls.isValidIdentitySecret
import ch.digitana.dienstplan.mls.publicKeyOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Schlüssel dieses Geräts: der Identitätsschlüssel (secp256k1, signiert KeyPackages und
 * ist die Identität in der MLS-Gruppe) und der Schlüssel der SQLCipher-Datenbank mit dem
 * MLS-Zustand. Beide liegen nur verschlüsselt im [SecureFileStore] (Keystore) und verlassen
 * das Gerät nie. Nach „Team verlassen“ werden sie gelöscht; ein neues Team bekommt neue.
 */
class DeviceKeys(identity: ByteArray, databaseKey: ByteArray) {
    private val identityBytes = identity.copyOf()
    private val databaseKeyBytes = databaseKey.copyOf()

    init {
        require(identity.size == SIZE && isValidIdentitySecret(identity)) { "Identitätsschlüssel ungültig" }
        require(databaseKey.size == SIZE) { "Datenbankschlüssel muss $SIZE Bytes lang sein" }
    }

    fun identity(): ByteArray = identityBytes.copyOf()

    fun databaseKey(): ByteArray = databaseKeyBytes.copyOf()

    /** Öffentlicher Schlüssel (64 Hex-Zeichen). */
    val publicKey: String by lazy { publicKeyOf(identityBytes) }

    /** Geräte-ID in den Planeinträgen: die ersten 16 Hex-Zeichen des öffentlichen Schlüssels. */
    val deviceId: String get() = deviceIdOf(publicKey)

    /** Gibt die Schlüssel bewusst nicht aus (Logs, Absturzberichte). */
    override fun toString(): String = "DeviceKeys(***)"

    companion object {
        const val SIZE = 32

        fun generate(): DeviceKeys {
            while (true) {
                // Fast jede Zufallszahl ist ein gültiger Schlüssel; die Schleife ist eine Formalität.
                val identity = SecureRandomBytes.next(SIZE)
                if (isValidIdentitySecret(identity)) return DeviceKeys(identity, SecureRandomBytes.next(SIZE)).also { identity.fill(0) }
            }
        }

        fun deviceIdOf(publicKey: String): String = publicKey.take(16)
    }
}

interface DeviceKeyStore {
    fun load(): DeviceKeys?
    fun save(keys: DeviceKeys)
    fun clear()
}

object DeviceKeysCodec {
    private const val VERSION = 1
    private val json = Json { isLenient = false }

    fun encode(keys: DeviceKeys): ByteArray {
        val identity = keys.identity()
        val databaseKey = keys.databaseKey()
        try {
            return buildJsonObject {
                put("v", VERSION)
                put("identity", Hex.encode(identity))
                put("db", Hex.encode(databaseKey))
            }.toString().toByteArray(Charsets.UTF_8)
        } finally {
            identity.fill(0)
            databaseKey.fill(0)
        }
    }

    fun decode(bytes: ByteArray): DeviceKeys {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Schlüsselformat ungültig")
        fun hex(name: String): ByteArray {
            val text = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(text != null && Hex.isLowerHex(text, DeviceKeys.SIZE * 2)) { "$name ungültig" }
            return Hex.decode(text)
        }
        require((root["v"] as? JsonPrimitive)?.content == VERSION.toString()) { "Unbekannte Schlüsselversion" }
        val identity = hex("identity")
        val databaseKey = hex("db")
        try {
            return DeviceKeys(identity, databaseKey)
        } finally {
            identity.fill(0)
            databaseKey.fill(0)
        }
    }
}

/** [DeviceKeyStore] auf Basis von [SecureFileStore]. */
class EncryptedDeviceKeyStore(private val files: SecureFileStore) : DeviceKeyStore {
    override fun load(): DeviceKeys? = files.read(FILE)?.let { DeviceKeysCodec.decode(it) }
    override fun save(keys: DeviceKeys) = files.write(FILE, DeviceKeysCodec.encode(keys))
    override fun clear() = files.delete(FILE)

    private companion object {
        const val FILE = "device.bin"
    }
}
