package ch.digitana.dienstplan.core.crypto

import java.security.SecureRandom

/** Kryptografisch sichere Zufallsbytes (auf Android: Conscrypt/BoringSSL). */
object SecureRandomBytes {
    private val random = SecureRandom()

    fun next(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}
