package ch.digitana.dienstplan.core.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.subtle.AesGcmJce
import java.security.GeneralSecurityException

/**
 * AES-256-GCM mit zufälliger 96-Bit-Nonce pro Nachricht (Google Tink).
 * Format: Nonce (12 Bytes) || Chiffrat || Tag (16 Bytes).
 *
 * Die Zusatzdaten (AAD) binden ein Paket an seinen Kontext, z. B. an den
 * d-Tag eines Buckets, damit es nicht unter einem anderen Namen eingespielt
 * werden kann.
 */
class PacketCipher(key: ByteArray) {
    private val aead: Aead

    init {
        require(key.size == KEY_SIZE) { "AES-256 braucht einen 32-Byte-Schlüssel" }
        aead = AesGcmJce(key)
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        aead.encrypt(plaintext, associatedData)

    /**
     * Entschlüsselt und prüft das Tag. Gibt `null` zurück, wenn das Paket
     * manipuliert ist, von einem fremden Schlüssel stammt oder zu kurz ist.
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray? {
        if (ciphertext.size < MIN_CIPHERTEXT_SIZE) return null
        return try {
            aead.decrypt(ciphertext, associatedData)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val MIN_CIPHERTEXT_SIZE = NONCE_SIZE + TAG_SIZE
    }
}
