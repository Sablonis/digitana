package ch.digitana.dienstplan.core.crypto

import ch.digitana.dienstplan.core.util.Hex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Aus dem Team-Geheimnis per HKDF-SHA256 abgeleitete, voneinander unabhängige Schlüssel:
 *
 *  - Nostr-Signaturschlüssel (secp256k1, BIP-340) – alle Geräte eines Teams signieren
 *    mit derselben Identität.
 *  - AES-256-GCM-Schlüssel für den Inhalt der Events.
 *  - HMAC-SHA256-Schlüssel für die d-Tags (verschleierte Bucket-Namen).
 *
 * Jede Ableitung nutzt ein eigenes `info`, damit ein Schlüssel nichts über
 * die anderen verrät (Domänentrennung).
 */
class TeamKeys private constructor(
    private val signingKey: ByteArray,
    private val publicKey: ByteArray,
    private val hmacKey: ByteArray,
    aesKey: ByteArray,
) {
    /** x-only-Public-Key des Teams als 64 Hex-Zeichen (Nostr `pubkey`). */
    val publicKeyHex: String = Hex.encode(publicKey)

    val packetCipher: PacketCipher = PacketCipher(aesKey)

    /** Signiert eine 32-Byte-Event-ID und prüft die eigene Signatur (Schutz vor Rechenfehlern). */
    fun sign(message32: ByteArray): ByteArray {
        val signature = Schnorr.sign(message32, signingKey)
        check(Schnorr.verify(signature, message32, publicKey)) { "Signatur-Selbsttest fehlgeschlagen" }
        return signature
    }

    /** d-Tag eines Buckets: HMAC-SHA256 über den Bucket-Namen, 64 Hex-Zeichen. */
    fun dTag(bucket: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(hmacKey, HMAC_ALGORITHM))
        return Hex.encode(mac.doFinal((D_TAG_PREFIX + bucket).toByteArray(Charsets.UTF_8)))
    }

    override fun toString(): String = "TeamKeys(pubkey=$publicKeyHex)"

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val D_TAG_PREFIX = "dienstplan/bucket/"
        private val SALT = "ch.digitana.dienstplan/DP2/hkdf-salt".toByteArray(Charsets.UTF_8)
        private const val INFO_NOSTR = "dienstplan/v2/nostr-secp256k1"
        private const val INFO_AES = "dienstplan/v2/aes-256-gcm"
        private const val INFO_HMAC = "dienstplan/v2/d-tag-hmac-sha256"

        fun derive(secret: TeamSecret): TeamKeys {
            val ikm = secret.bytes()
            try {
                val signingKey = deriveSigningKey(ikm)
                val publicKey = Schnorr.xOnlyPublicKey(signingKey)
                val aesKey = Hkdf.sha256(ikm, SALT, INFO_AES.toByteArray(Charsets.UTF_8), 32)
                val hmacKey = Hkdf.sha256(ikm, SALT, INFO_HMAC.toByteArray(Charsets.UTF_8), 32)
                return TeamKeys(signingKey, publicKey, hmacKey, aesKey)
            } finally {
                ikm.fill(0)
            }
        }

        /**
         * Ein HKDF-Ausgabewert ist mit Wahrscheinlichkeit ~2^-128 kein gültiger
         * secp256k1-Skalar (0 oder ≥ n). Dann wird mit Zähler im `info` neu abgeleitet.
         */
        private fun deriveSigningKey(ikm: ByteArray): ByteArray {
            for (counter in 0 until 256) {
                val info = if (counter == 0) INFO_NOSTR else "$INFO_NOSTR/$counter"
                val candidate = Hkdf.sha256(ikm, SALT, info.toByteArray(Charsets.UTF_8), 32)
                if (Schnorr.isValidSecretKey(candidate)) return candidate
            }
            error("Kein gültiger secp256k1-Schlüssel ableitbar")
        }
    }
}
