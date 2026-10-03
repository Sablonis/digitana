package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.crypto.PacketCipher
import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.data.KeyWrapper
import ch.digitana.dienstplan.core.data.SecureStorageException

/** Software-Ersatz für den Android-Keystore in Tests. */
class SoftwareKeyWrapper : KeyWrapper {
    @Volatile private var key: ByteArray? = SecureRandomBytes.next(32)

    override fun wrap(plainKey: ByteArray): ByteArray = PacketCipher(currentKey()).encrypt(plainKey, AAD)

    override fun unwrap(wrapped: ByteArray): ByteArray =
        PacketCipher(currentKey()).decrypt(wrapped, AAD)
            ?: throw SecureStorageException(SecureStorageException.Reason.KEY_UNAVAILABLE, "Schlüssel passt nicht")

    override fun destroy() {
        key = null
    }

    private fun currentKey(): ByteArray =
        key ?: SecureRandomBytes.next(32).also { key = it }

    private companion object {
        val AAD = "test-keystore".toByteArray()
    }
}
