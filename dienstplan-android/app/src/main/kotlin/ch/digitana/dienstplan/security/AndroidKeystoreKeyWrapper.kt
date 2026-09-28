package ch.digitana.dienstplan.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ch.digitana.dienstplan.core.data.KeyWrapper
import ch.digitana.dienstplan.core.data.SecureStorageException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Schützt den Datenschlüssel mit einem AES-256-GCM-Schlüssel im Android Keystore.
 * Der Keystore-Schlüssel ist nicht exportierbar und hardwaregestützt (TEE, wenn vorhanden
 * StrongBox). Er wird nur beim Start benutzt, um den Datenschlüssel zu entpacken.
 *
 * Bewusst ohne Nutzer-Authentisierung und ohne setUnlockedDeviceRequired: Beides würde den
 * Plan bei jedem Öffnen hinter eine zusätzliche Sperre legen bzw. ist auf manchen Geräten
 * fehlerhaft umgesetzt. Die Displaysperre des Geräts bleibt die erste Schutzschicht.
 */
class AndroidKeystoreKeyWrapper(
    private val preferStrongBox: Boolean,
    private val alias: String = "dienstplan.master.v1",
) : KeyWrapper {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun wrap(plainKey: ByteArray): ByteArray {
        try {
            val key = existingKey() ?: createKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key) // Keystore wählt die Nonce selbst
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(plainKey)
            val iv = cipher.iv
            return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException(SecureStorageException.Reason.KEY_UNAVAILABLE, "Keystore: ${e.javaClass.simpleName}", e)
        }
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val key = try {
            existingKey()
        } catch (e: GeneralSecurityException) {
            null
        } ?: throw SecureStorageException(SecureStorageException.Reason.KEY_UNAVAILABLE, "Keystore-Schlüssel fehlt")
        if (wrapped.size < 2 || wrapped[0] != FORMAT_VERSION) {
            throw SecureStorageException(SecureStorageException.Reason.CORRUPTED, "Unbekanntes Schlüsselformat")
        }
        val ivLength = wrapped[1].toInt()
        if (ivLength !in 12..16 || wrapped.size <= 2 + ivLength) {
            throw SecureStorageException(SecureStorageException.Reason.CORRUPTED, "Schlüsseldatei beschädigt")
        }
        try {
            val iv = wrapped.copyOfRange(2, 2 + ivLength)
            val body = wrapped.copyOfRange(2 + ivLength, wrapped.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            return cipher.doFinal(body)
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException(SecureStorageException.Reason.KEY_UNAVAILABLE, "Entschlüsseln fehlgeschlagen", e)
        }
    }

    override fun destroy() {
        runCatching { keyStore.deleteEntry(alias) }
    }

    private fun existingKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey {
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val key = generate(strongBox = true)
                if (selfTest(key)) return key
            } catch (e: Exception) {
                // StrongBoxUnavailableException oder fehlerhafte Umsetzung: TEE verwenden.
            }
            runCatching { keyStore.deleteEntry(alias) }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Manche StrongBox-Umsetzungen erzeugen Schlüssel, die danach nicht funktionieren. */
    private fun selfTest(key: SecretKey): Boolean = runCatching {
        val probe = ByteArray(32) { it.toByte() }
        val encrypt = Cipher.getInstance(TRANSFORMATION)
        encrypt.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = encrypt.doFinal(probe)
        val decrypt = Cipher.getInstance(TRANSFORMATION)
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypt.iv))
        decrypt.doFinal(ciphertext).contentEquals(probe)
    }.getOrDefault(false)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
        val AAD = "ch.digitana.dienstplan/dek/v1".toByteArray(Charsets.UTF_8)
    }
}
