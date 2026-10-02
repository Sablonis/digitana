package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.util.Hex
import java.security.MessageDigest
import java.util.Collections

/**
 * Unveränderliche Last-Writer-Wins-Map (zustandsbasiertes CRDT).
 *
 * `merge` bildet pro Schlüssel das Maximum nach [Entry.compareTo] und ist damit
 * kommutativ, assoziativ und idempotent: Alle Geräte, die dieselben Einträge
 * kennen, landen unabhängig von Reihenfolge und Wiederholungen beim selben Stand.
 */
class LwwMap private constructor(entries: Map<String, Entry>) {

    val entries: Map<String, Entry> = Collections.unmodifiableMap(entries)

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    operator fun get(key: String): Entry? = entries[key]

    /** Übernimmt [entry], falls er neuer ist als der bekannte Stand; sonst `this`. */
    fun put(key: String, entry: Entry): LwwMap {
        val current = entries[key]
        if (current != null && current >= entry) return this
        val copy = HashMap(entries)
        copy[key] = entry
        return LwwMap(copy)
    }

    fun merge(other: LwwMap): LwwMap = mergeEntries(other.entries).map

    /**
     * Entfernt Schlüssel ganz (nicht als Tombstone). Nur für Einträge, die nach einer neuen
     * Sperre auf keinem Gerät mehr gelten: Ein älterer Eintrag desselben Schlüssels kann
     * danach wieder übernommen werden.
     */
    fun without(keys: Collection<String>): LwwMap {
        if (keys.none { it in entries }) return this
        val copy = HashMap(entries)
        for (key in keys) copy.remove(key)
        return if (copy.isEmpty()) EMPTY else LwwMap(copy)
    }

    /** Führt [incoming] ein und meldet die Schlüssel, deren Wert sich dadurch geändert hat. */
    fun mergeEntries(incoming: Map<String, Entry>): MergeResult {
        var result: HashMap<String, Entry>? = null
        var changed: MutableSet<String>? = null
        for ((key, entry) in incoming) {
            val current = (result ?: entries)[key]
            if (current == null || entry > current) {
                if (result == null) result = HashMap(entries)
                result[key] = entry
                if (changed == null) changed = HashSet()
                changed += key
            }
        }
        return if (result == null) {
            MergeResult(this, emptySet())
        } else {
            MergeResult(LwwMap(result), changed ?: emptySet())
        }
    }

    /**
     * SHA-256 über alle Einträge in Schlüsselreihenfolge. Zwei Maps haben genau dann
     * denselben Digest, wenn sie dieselben Einträge enthalten. Die Sync-Engine vergleicht
     * damit den lokalen Stand mit dem, was ein Relay gespeichert hat.
     */
    val digest: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val md = MessageDigest.getInstance("SHA-256")
        for (key in entries.keys.sorted()) {
            val entry = entries.getValue(key)
            md.updateField(key.toByteArray(Charsets.UTF_8))
            md.updateField(entry.value.toByteArray(Charsets.UTF_8))
            md.updateField(entry.timestamp.toString().toByteArray(Charsets.US_ASCII))
            md.updateField(entry.device.toByteArray(Charsets.UTF_8))
        }
        Hex.encode(md.digest())
    }

    override fun equals(other: Any?): Boolean = other is LwwMap && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "LwwMap(size=$size)"

    companion object {
        val EMPTY = LwwMap(emptyMap())

        fun of(entries: Map<String, Entry>): LwwMap =
            if (entries.isEmpty()) EMPTY else LwwMap(HashMap(entries))

        /** Längenpräfix verhindert Mehrdeutigkeiten zwischen Feldgrenzen. */
        private fun MessageDigest.updateField(bytes: ByteArray) {
            val n = bytes.size
            update(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            update(bytes)
        }
    }
}

data class MergeResult(val map: LwwMap, val changedKeys: Set<String>)
