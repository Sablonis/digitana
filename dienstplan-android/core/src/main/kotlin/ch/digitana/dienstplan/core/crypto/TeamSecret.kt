package ch.digitana.dienstplan.core.crypto

import java.security.MessageDigest

/**
 * Das 32-Byte-Team-Geheimnis. Aus ihm werden alle Schlüssel abgeleitet;
 * es verlässt das Gerät nur als Einladungscode.
 */
class TeamSecret(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "Team-Geheimnis muss $SIZE Bytes lang sein" }
    }

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is TeamSecret && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /** Gibt das Geheimnis bewusst nicht aus (Logs, Absturzberichte). */
    override fun toString(): String = "TeamSecret(***)"

    companion object {
        const val SIZE = 32

        fun generate(): TeamSecret = TeamSecret(SecureRandomBytes.next(SIZE))
    }
}
