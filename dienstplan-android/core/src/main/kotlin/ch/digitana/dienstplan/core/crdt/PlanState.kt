package ch.digitana.dienstplan.core.crdt

import java.text.Collator
import java.time.LocalDate
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

    /** ID der Schichtart in diesem Feld; null = leer. */
    fun shift(memberId: String, date: LocalDate): String? =
        value(PlanKeys.shift(memberId, date)).ifEmpty { null }

    fun dayNote(date: LocalDate): String? = value(PlanKeys.dayNote(date)).ifEmpty { null }

    fun memberNote(memberId: String, date: LocalDate): String? =
        value(PlanKeys.memberNote(memberId, date)).ifEmpty { null }

    fun wish(memberId: String, date: LocalDate): Wish? = Wish.fromCode(value(PlanKeys.wish(memberId, date)))

    /**
     * Alle Schichtarten: Standardarten (überschrieben, falls das Team sie geändert hat) und
     * eigene. Ungültige Definitionen kommen nicht vor, weil sie beim Empfang verworfen werden.
     */
    val shiftTypes: ShiftTypeSet by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val defined = HashMap<String, ShiftType>()
        for (type in ShiftTypes.DEFAULTS) defined[type.id] = type
        for ((key, entry) in bucket(Buckets.TEAM).entries) {
            val parsed = PlanKeys.parse(key) as? PlanKey.ShiftType ?: continue
            if (entry.value.isEmpty()) continue
            ShiftTypes.decode(parsed.typeId, entry.value)?.let { defined[it.id] = it }
        }
        ShiftTypeSet(defined.values)
    }

    /** Mindestruhezeit zwischen zwei Diensten in Minuten; 0 = keine Warnung. */
    val restMinutes: Int
        get() = value(PlanKeys.setting(PlanRules.REST)).toIntOrNull() ?: PlanRules.DEFAULT_REST_MINUTES

    /** Soll-Besetzung pro Schichtart (Montag zuerst); nur Arten mit mindestens einem Soll. */
    val targets: Map<String, List<Int>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        bucket(Buckets.TEAM).entries.mapNotNull { (key, entry) ->
            val parsed = PlanKeys.parse(key) as? PlanKey.Target ?: return@mapNotNull null
            val targets = PlanRules.decodeTargets(entry.value) ?: return@mapNotNull null
            if (targets.all { it == 0 }) null else parsed.typeId to targets
        }.toMap()
    }

    /** Gespeicherte Rhythmen, alphabetisch. */
    fun patterns(): List<ShiftPattern> {
        val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.SECONDARY }
        return bucket(Buckets.TEAM).entries.mapNotNull { (key, entry) ->
            val parsed = PlanKeys.parse(key) as? PlanKey.Pattern ?: return@mapNotNull null
            if (entry.value.isEmpty()) null else ShiftPatterns.decode(parsed.patternId, entry.value)
        }.sortedWith { a, b ->
            val byName = collator.compare(a.name, b.name)
            if (byName != 0) byName else a.id.compareTo(b.id)
        }
    }

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

    /** Behält nur die Einträge, für die [keep] true liefert, und meldet die entfernten. */
    fun filter(keep: (String, Entry) -> Boolean): PlanFilter {
        var buckets: HashMap<String, LwwMap>? = null
        val removed = HashMap<String, Entry>()
        for ((name, map) in bucketMap) {
            val drop = map.entries.filter { (key, entry) -> !keep(key, entry) }
            if (drop.isEmpty()) continue
            removed.putAll(drop)
            val copy = buckets ?: HashMap(bucketMap).also { buckets = it }
            val kept = map.without(drop.keys)
            if (kept.isEmpty()) copy.remove(name) else copy[name] = kept
        }
        val result = buckets ?: return PlanFilter(this, emptyMap())
        return PlanFilter(PlanState(result), removed)
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

    /** Zuordnung der Geräte zu Personen (Geräte-ID → Personen-ID), ohne aufgehobene. */
    fun deviceOwners(): Map<String, String> =
        bucket(Buckets.TEAM).entries.mapNotNull { (key, entry) ->
            val parsed = PlanKeys.parse(key) as? PlanKey.DeviceOwner ?: return@mapNotNull null
            if (entry.value.isEmpty()) null else parsed.deviceId to entry.value
        }.toMap()

    /**
     * Wer einen Eintrag geschrieben hat: die Person, der das Gerät gehört („Das bin ich“),
     * sonst der Gerätename; null, wenn beides unbekannt ist.
     */
    fun authorName(device: String): String? {
        val owner = deviceOwners()[device]
        if (owner != null) members().firstOrNull { it.id == owner }?.let { return it.name }
        return deviceLabels()[device]
    }

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

data class PlanFilter(val state: PlanState, val removed: Map<String, Entry>)
