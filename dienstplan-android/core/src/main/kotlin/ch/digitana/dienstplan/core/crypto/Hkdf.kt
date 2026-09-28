package ch.digitana.dienstplan.core.crypto

import com.google.crypto.tink.subtle.Hkdf as TinkHkdf

/** HKDF-SHA256 nach RFC 5869 (Extract + Expand) aus Google Tink. */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        TinkHkdf.computeHkdf("HMACSHA256", ikm, salt, info, length)
}
