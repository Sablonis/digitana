package ch.digitana.dienstplan.core.testing

import ch.digitana.dienstplan.core.crypto.Schnorr
import ch.digitana.dienstplan.core.nostr.Nip01
import ch.digitana.dienstplan.core.nostr.NostrEvent
import ch.digitana.dienstplan.core.util.Hex

/** Signieren und Prüfen von Nostr-Events in Tests (unabhängig von der MLS-Bibliothek). */
object TestEvents {

    fun sign(secretKey: ByteArray, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val pubkey = Hex.encode(Schnorr.xOnlyPublicKey(secretKey))
        val id = Nip01.computeId(pubkey, createdAt, kind, tags, content)
        val sig = Schnorr.sign(id, secretKey)
        return NostrEvent(Hex.encode(id), pubkey, createdAt, kind, tags, content, Hex.encode(sig))
    }

    /** Prüft Format, ID (neu berechnet) und BIP-340-Signatur. */
    fun verify(event: NostrEvent): Boolean {
        if (!Nip01.hasValidId(event) || !Hex.isLowerHex(event.sig, 128)) return false
        return Schnorr.verify(Hex.decode(event.sig), Hex.decode(event.id), Hex.decode(event.pubkey))
    }
}
