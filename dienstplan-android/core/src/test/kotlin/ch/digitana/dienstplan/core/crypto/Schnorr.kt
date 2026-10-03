package ch.digitana.dienstplan.core.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * BIP-340-Schnorr-Signaturen über secp256k1 (libsecp256k1 via ACINQ secp256k1-kmp), nur für
 * Tests: Die App signiert und prüft über die MLS-Bibliothek (rust-nostr); hier prüfen das
 * Test-Relay und die NIP-01-Tests unabhängig davon. Die Bibliothek signiert nur 32-Byte-
 * Nachrichten – bei Nostr ist das immer die Event-ID.
 */
object Schnorr {
    const val SECRET_KEY_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64
    const val MESSAGE_SIZE = 32

    fun isValidSecretKey(secretKey: ByteArray): Boolean =
        secretKey.size == SECRET_KEY_SIZE &&
            runCatching { Secp256k1.secKeyVerify(secretKey) }.getOrDefault(false)

    /** x-only-Public-Key (32 Bytes) nach BIP-340. */
    fun xOnlyPublicKey(secretKey: ByteArray): ByteArray {
        require(isValidSecretKey(secretKey)) { "Ungültiger secp256k1-Schlüssel" }
        val uncompressed = Secp256k1.pubkeyCreate(secretKey) // 0x04 || X || Y
        check(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte())
        return uncompressed.copyOfRange(1, 1 + PUBLIC_KEY_SIZE)
    }

    /**
     * Signiert [message32]. [auxRand32] ist frische Zufälligkeit nach BIP-340
     * (Schutz gegen Seitenkanäle); für Testvektoren wird sie explizit vorgegeben.
     */
    fun sign(
        message32: ByteArray,
        secretKey: ByteArray,
        auxRand32: ByteArray? = SecureRandomBytes.next(32),
    ): ByteArray {
        require(message32.size == MESSAGE_SIZE) { "Nachricht muss 32 Bytes lang sein" }
        require(secretKey.size == SECRET_KEY_SIZE) { "Schlüssel muss 32 Bytes lang sein" }
        return Secp256k1.signSchnorr(message32, secretKey, auxRand32)
    }

    /**
     * Prüft eine Signatur. Gibt bei jeder ungültigen Eingabe `false` zurück –
     * auch dann, wenn libsecp256k1 den Public Key gar nicht erst parsen kann.
     */
    fun verify(signature64: ByteArray, message32: ByteArray, xOnlyPublicKey32: ByteArray): Boolean {
        if (signature64.size != SIGNATURE_SIZE ||
            message32.size != MESSAGE_SIZE ||
            xOnlyPublicKey32.size != PUBLIC_KEY_SIZE
        ) {
            return false
        }
        return try {
            Secp256k1.verifySchnorr(signature64, message32, xOnlyPublicKey32)
        } catch (e: RuntimeException) {
            // Secp256k1Exception (z. B. Punkt nicht auf der Kurve) oder IllegalArgumentException
            false
        }
    }
}
