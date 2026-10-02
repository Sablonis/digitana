package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.util.Hex
import java.time.LocalTime
import java.util.Locale

/** Art einer Schicht: bestimmt Besetzung und Stunden. */
enum class ShiftKind(val code: String) {
    /** Arbeit: zählt zur Besetzung. */
    WORK("W"),

    /** Frei. */
    OFF("F"),

    /** Abwesend (Urlaub, Kurs, krank …). */
    ABSENCE("A");

    companion object {
        fun fromCode(code: String): ShiftKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Eine Schichtart des Teams. Einträge im Plan verweisen auf die [id]; Kürzel, Name, Zeiten
 * und Farbe lassen sich deshalb ändern, ohne bestehende Einträge umzuschreiben.
 *
 * Die fünf Standardarten haben die IDs F, S, N, X und U (die Kürzel der ersten Version);
 * eigene Arten bekommen eine zufällige ID aus 8 Hex-Zeichen.
 */
data class ShiftType(
    val id: String,
    /** Kurzes Kürzel für die Tabelle, 1–3 Zeichen A–Z/0–9. */
    val code: String,
    val name: String,
    /** Beginn und Ende; beide null = ganztägig ohne Zeiten. */
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val breakMinutes: Int = 0,
    val kind: ShiftKind = ShiftKind.WORK,
    /** Index in der festen Farbpalette (0–11). */
    val color: Int = 0,
    /** Angerechnete Minuten für Arten ohne Zeiten (z. B. Urlaub). */
    val creditMinutes: Int = 0,
    /** Archiviert: wird noch angezeigt, aber nicht mehr zur Auswahl angeboten. */
    val archived: Boolean = false,
) {
    init {
        require(ShiftTypes.isValidId(id)) { "Ungültige Schichtart-ID" }
        require(ShiftTypes.isValidCode(code)) { "Ungültiges Kürzel" }
        require(ShiftTypes.isValidName(name)) { "Ungültiger Name" }
        require((start == null) == (end == null)) { "Beginn und Ende nur gemeinsam" }
        require(breakMinutes in 0..ShiftTypes.MAX_BREAK_MINUTES) { "Ungültige Pause" }
        require(color in 0 until ShiftTypes.COLOR_COUNT) { "Ungültige Farbe" }
        require(creditMinutes in 0..ShiftTypes.MAX_CREDIT_MINUTES) { "Ungültige Anrechnung" }
    }

    val hasTimes: Boolean get() = start != null

    val isBuiltIn: Boolean get() = id in ShiftTypes.BUILT_IN_IDS

    /** Dauer von Beginn bis Ende; über Mitternacht wird ein Tag addiert, gleiche Zeiten = 24 h. */
    val durationMinutes: Int
        get() {
            val s = start ?: return 0
            val e = end ?: return 0
            val diff = e.toSecondOfDay() / 60 - s.toSecondOfDay() / 60
            return if (diff <= 0) diff + MINUTES_PER_DAY else diff
        }

    /** Minuten, die für die Stunden zählen: Dauer minus Pause, ohne Zeiten die Anrechnung. */
    val paidMinutes: Int
        get() = if (hasTimes) maxOf(0, durationMinutes - breakMinutes) else creditMinutes

    val countsForCoverage: Boolean get() = kind == ShiftKind.WORK

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * Regeln und Standardwerte für Schichtarten sowie das Format im Plan:
 * `v1|<kürzel>|<name>|<beginn>|<ende>|<pause>|<art>|<farbe>|<anrechnung>|<flags>`.
 * Beginn/Ende sind `HH:MM` oder beide leer; Flags sind leer oder `a` (archiviert).
 */
object ShiftTypes {
    const val COLOR_COUNT = 12
    const val MAX_NAME_LENGTH = 30
    const val MAX_BREAK_MINUTES = 480
    const val MAX_CREDIT_MINUTES = 24 * 60

    val BUILT_IN_IDS: Set<String> = setOf("F", "S", "N", "X", "U")

    private val ID = Regex("[FSNXU]|[0-9a-f]{8}")
    private val CODE = Regex("[A-Z0-9]{1,3}")
    private val TIME = Regex("([01][0-9]|2[0-3]):([0-5][0-9])")
    private val NUMBER = Regex("0|[1-9][0-9]{0,3}")
    private const val PREFIX = "v1"
    private const val FIELDS = 10

    /** Standardarten der ersten Version: Früh, Spät, Nacht (je 8 h), Frei, Urlaub. */
    val DEFAULTS: List<ShiftType> = listOf(
        ShiftType("F", "F", "Früh", LocalTime.of(6, 0), LocalTime.of(14, 0), color = 0),
        ShiftType("S", "S", "Spät", LocalTime.of(14, 0), LocalTime.of(22, 0), color = 1),
        ShiftType("N", "N", "Nacht", LocalTime.of(22, 0), LocalTime.of(6, 0), color = 2),
        ShiftType("X", "X", "Frei", kind = ShiftKind.OFF, color = 3),
        ShiftType("U", "U", "Urlaub", kind = ShiftKind.ABSENCE, color = 4),
    )

    private val DEFAULTS_BY_ID = DEFAULTS.associateBy { it.id }

    fun default(id: String): ShiftType? = DEFAULTS_BY_ID[id]

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun isValidCode(code: String): Boolean = CODE.matches(code)

    fun isValidName(name: String): Boolean = '|' !in name && Names.isValid(name, MAX_NAME_LENGTH)

    /** Neue zufällige ID für eine eigene Schichtart. */
    fun newId(existing: Set<String>): String {
        while (true) {
            val id = Hex.encode(SecureRandomBytes.next(4))
            if (id !in existing) return id
        }
    }

    fun encode(type: ShiftType): String = listOf(
        PREFIX,
        type.code,
        type.name,
        type.start?.let(::formatTime) ?: "",
        type.end?.let(::formatTime) ?: "",
        type.breakMinutes.toString(),
        type.kind.code,
        type.color.toString(),
        type.creditMinutes.toString(),
        if (type.archived) "a" else "",
    ).joinToString("|")

    /** Liest eine Definition; `null` bei jeder Abweichung vom Format. */
    fun decode(id: String, value: String): ShiftType? {
        if (!isValidId(id) || value.length > MAX_VALUE_LENGTH) return null
        val parts = value.split('|')
        if (parts.size != FIELDS || parts[0] != PREFIX) return null
        val code = parts[1].takeIf(::isValidCode) ?: return null
        val name = parts[2].takeIf(::isValidName) ?: return null
        val start = parseTime(parts[3])
        val end = parseTime(parts[4])
        if ((start == null) != (parts[3].isEmpty()) || (end == null) != (parts[4].isEmpty())) return null
        if ((start == null) != (end == null)) return null
        val breakMinutes = parseNumber(parts[5]) ?: return null
        val kind = ShiftKind.fromCode(parts[6]) ?: return null
        val color = parseNumber(parts[7]) ?: return null
        val credit = parseNumber(parts[8]) ?: return null
        val archived = when (parts[9]) {
            "" -> false
            "a" -> true
            else -> return null
        }
        if (breakMinutes > MAX_BREAK_MINUTES || color >= COLOR_COUNT || credit > MAX_CREDIT_MINUTES) return null
        return ShiftType(id, code, name, start, end, breakMinutes, kind, color, credit, archived)
    }

    fun isValidValue(id: String, value: String): Boolean = decode(id, value) != null

    fun formatTime(time: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

    private fun parseTime(text: String): LocalTime? {
        val match = TIME.matchEntire(text) ?: return null
        return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun parseNumber(text: String): Int? = if (NUMBER.matches(text)) text.toInt() else null

    private const val MAX_VALUE_LENGTH = 200
}

/**
 * Alle Schichtarten eines Plans: die Standardarten (sofern nicht geändert) und die eigenen.
 * Sortiert nach Art (Arbeit, frei, abwesend), Beginn und Kürzel.
 */
class ShiftTypeSet(types: Collection<ShiftType>) {

    val all: List<ShiftType> = types.sortedWith(ORDER)

    private val byId: Map<String, ShiftType> = all.associateBy { it.id }

    /** Zur Auswahl angeboten: alle nicht archivierten. */
    val active: List<ShiftType> = all.filter { !it.archived }

    operator fun get(id: String?): ShiftType? = id?.let { byId[it] }

    fun contains(id: String): Boolean = id in byId

    val ids: Set<String> get() = byId.keys

    override fun equals(other: Any?): Boolean = other is ShiftTypeSet && all == other.all

    override fun hashCode(): Int = all.hashCode()

    override fun toString(): String = "ShiftTypeSet(${all.size})"

    companion object {
        // Vor DEFAULT deklarieren: Die Eigenschaften werden in dieser Reihenfolge initialisiert.
        private val ORDER: Comparator<ShiftType> = compareBy<ShiftType> { it.kind.ordinal }
            .thenBy(nullsLast<LocalTime>()) { it.start }
            .thenBy { it.code }
            .thenBy { it.id }

        val DEFAULT = ShiftTypeSet(ShiftTypes.DEFAULTS)
    }
}
