package ch.digitana.dienstplan.core.crdt

import java.text.Collator
import java.util.Locale

data class Member(val id: String, val name: String)

/**
 * Der komplette Plan als Menge von Buckets, jeder eine [LwwMap].
 * Unveränderlich; jede Änderung liefert einen neuen Stand.
 */
class PlanState private constructor(private val bucketMap: Map<String, LwwMap>) {

    val buckets: Map<String, LwwMap> get() = bucketMap

    fun bucket(name: String): LwwMap = bucketMap[name] ?: LwwMap.EMPTY

    fun entry(key: String): Entry? {
        val parsed = PlanKeys.parse(key) ?: return null
        return bucketMap[Buckets.forKey(parsed)]?.get(key)
    }

    /** Aktueller Wert eines Schlüssels; leer, wenn unbekannt oder gelöscht. */
    fun value(key: String): String = entry(key)?.value ?: ""

    fun shift(memberId: String, date: java.time.LocalDate): Shift? =
        Shift.fromCode(value(PlanKeys.shift(memberId, date)))

    fun withEntry(key: String, entry: Entry): PlanState {
        val parsed = requireNotNull(PlanKeys.parse(key)) { "Ungültiger Schlüssel" }
        val name = Buckets.forKey(parsed)
        val before = bucketMap[name]
        val after = (before ?: LwwMap.EMPTY).put(key, entry)
        if (after === before || (before == null && after.isEmpty())) return this
        return PlanState(bucketMap + (name to after))
    }

    fun merge(bucketName: String, incoming: Map<String, Entry>): PlanMerge {
        if (incoming.isEmpty()) return PlanMerge(this, emptySet())
        val result = bucket(bucketName).mergeEntries(incoming)
        if (result.changedKeys.isEmpty()) return PlanMerge(this, emptySet())
        return PlanMerge(PlanState(bucketMap + (bucketName to result.map)), result.changedKeys)
    }

    /** Aktive (nicht gelöschte) Mitarbeitende, alphabetisch nach deutscher Sortierung. */
    fun members(): List<Member> {
        val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.SECONDARY }
        return bucket(Buckets.TEAM).entries.mapNotNull { (key, entry) ->
            val parsed = PlanKeys.parse(key) as? PlanKey.Member ?: return@mapNotNull null
            if (entry.value.isEmpty()) null else Member(parsed.memberId, entry.value)
        }.sortedWith { a, b ->
            val byName = collator.compare(a.name, b.name)
            if (byName != 0) byName else a.id.compareTo(b.id)
        }
    }

    /** Gerätenamen (Geräte-ID → Name), ohne gelöschte. */
    fun deviceLabels(): Map<String, String> =
        bucket(Buckets.TEAM).entries.mapNotNull { (key, entry) ->
            val parsed = PlanKeys.parse(key) as? PlanKey.Device ?: return@mapNotNull null
            if (entry.value.isEmpty()) null else parsed.deviceId to entry.value
        }.toMap()

    val maxTimestamp: Long
        get() = bucketMap.values.maxOfOrNull { map -> map.entries.values.maxOfOrNull { it.timestamp } ?: 0L } ?: 0L

    val entryCount: Int get() = bucketMap.values.sumOf { it.size }

    fun isEmpty(): Boolean = bucketMap.values.all { it.isEmpty() }

    override fun equals(other: Any?): Boolean = other is PlanState && bucketMap == other.bucketMap

    override fun hashCode(): Int = bucketMap.hashCode()

    override fun toString(): String = "PlanState(buckets=${bucketMap.size}, entries=$entryCount)"

    companion object {
        val EMPTY = PlanState(emptyMap())

        fun of(buckets: Map<String, LwwMap>): PlanState =
            PlanState(buckets.filterValues { !it.isEmpty() })
    }
}

data class PlanMerge(val state: PlanState, val changedKeys: Set<String>)
