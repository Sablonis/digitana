package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.EntryValidator
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Persistierter Plan: alle Buckets, Stand der hybriden Uhr und die eigenen Änderungen, die
 * noch kein Relay bestätigt hat (Schlüssel → Zeitstempel des Eintrags).
 */
data class PlanSnapshot(val state: PlanState, val clock: Long, val pending: Map<String, Long> = emptyMap())

/** Lokaler Speicher für den Plan (verschlüsselt in der App). */
interface PlanStore {
    fun load(): PlanSnapshot?
    fun save(snapshot: PlanSnapshot)
    fun clear()
}

/**
 * JSON-Format der lokalen Datei. Auch lokale Daten werden beim Laden geprüft,
 * damit eine beschädigte Datei keine ungültigen Einträge in den Sync bringt.
 */
object PlanSnapshotCodec {
    private const val VERSION = 2
    private val json = Json { isLenient = false }

    fun encode(snapshot: PlanSnapshot): ByteArray {
        val root = buildJsonObject {
            put("v", VERSION)
            put("clock", snapshot.clock)
            putJsonArray("pending") {
                for ((key, timestamp) in snapshot.pending.toSortedMap()) {
                    addJsonArray {
                        add(key)
                        add(timestamp)
                    }
                }
            }
            putJsonObject("buckets") {
                for ((bucket, map) in snapshot.state.buckets) {
                    if (map.isEmpty()) continue
                    putJsonArray(bucket) {
                        for ((key, entry) in map.entries) {
                            addJsonArray {
                                add(key)
                                add(entry.value)
                                add(entry.timestamp)
                                add(entry.device)
                            }
                        }
                    }
                }
            }
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /** @throws IllegalArgumentException bei unlesbarem Format. */
    fun decode(bytes: ByteArray): PlanSnapshot {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Planformat ungültig")
        val version = (root["v"] as? JsonPrimitive)?.content
        require(version == "1" || version == VERSION.toString()) { "Unbekannte Planversion" }
        val clock = (root["clock"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val pending = HashMap<String, Long>()
        (root["pending"] as? JsonArray)?.forEach { item ->
            val fields = item as? JsonArray ?: return@forEach
            if (fields.size != 2) return@forEach
            val key = (fields[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            val timestamp = (fields[1] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: return@forEach
            if (PlanKeys.parse(key) != null && timestamp > 0) pending[key] = timestamp
        }
        val bucketsJson = root["buckets"] as? JsonObject ?: throw IllegalArgumentException("Buckets fehlen")
        val buckets = HashMap<String, LwwMap>()
        for ((bucket, value) in bucketsJson) {
            if (!Buckets.isValid(bucket)) continue
            val items = value as? JsonArray ?: continue
            val entries = HashMap<String, Entry>()
            for (item in items) {
                val fields = item as? JsonArray ?: continue
                if (fields.size != 4) continue
                val key = (fields[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val entryValue = (fields[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val timestamp = (fields[2] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: continue
                val device = (fields[3] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val parsed = PlanKeys.parse(key) ?: continue
                if (Buckets.forKey(parsed) != bucket) continue
                if (!EntryValidator.isValidValue(parsed, entryValue)) continue
                if (timestamp <= 0 || !EntryValidator.isValidDeviceId(device)) continue
                entries[key] = Entry(entryValue, timestamp, device)
            }
            if (entries.isNotEmpty()) buckets[bucket] = LwwMap.of(entries)
        }
        return PlanSnapshot(PlanState.of(buckets), clock, pending)
    }
}
