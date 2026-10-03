package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.util.Hex

/**
 * Ein Schichtrhythmus, z. B. zwei Wochen „F F F F F – –, S S S S S – –“. [days] enthält pro
 * Tag die ID einer Schichtart oder null (Feld bleibt leer). Ein Rhythmus beginnt immer an
 * einem Montag und dauert ganze Wochen.
 */
data class ShiftPattern(
    val id: String,
    val name: String,
    val days: List<String?>,
) {
    init {
        require(ShiftPatterns.isValidId(id)) { "Ungültige Rhythmus-ID" }
        require(ShiftPatterns.isValidName(name)) { "Ungültiger Name" }
        require(days.size in 1..ShiftPatterns.MAX_DAYS && days.size % 7 == 0) { "Ungültige Länge" }
        require(days.all { it == null || ShiftTypes.isValidId(it) }) { "Ungültige Schichtart" }
    }

    val weeks: Int get() = days.size / 7
}

/** Format im Plan: `v1|<name>|<id1>,<id2>,…` mit leerem Feld für „frei lassen“. */
object ShiftPatterns {
    const val MAX_WEEKS = 8
    const val MAX_DAYS = MAX_WEEKS * 7
    const val MAX_NAME_LENGTH = 30

    private val ID = Regex("[0-9a-f]{8}")
    private const val PREFIX = "v1"

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun isValidName(name: String): Boolean = '|' !in name && Names.isValid(name, MAX_NAME_LENGTH)

    fun newId(existing: Set<String>): String {
        while (true) {
            val id = Hex.encode(SecureRandomBytes.next(4))
            if (id !in existing) return id
        }
    }

    fun encode(pattern: ShiftPattern): String =
        "$PREFIX|${pattern.name}|${pattern.days.joinToString(",") { it ?: "" }}"

    fun decode(id: String, value: String): ShiftPattern? {
        if (!isValidId(id) || value.length > MAX_VALUE_LENGTH) return null
        val parts = value.split('|')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val name = parts[1].takeIf(::isValidName) ?: return null
        val days = parts[2].split(',').map { it.ifEmpty { null } }
        if (days.size !in 1..MAX_DAYS || days.size % 7 != 0) return null
        if (days.any { it != null && !ShiftTypes.isValidId(it) }) return null
        return ShiftPattern(id, name, days)
    }

    fun isValidValue(id: String, value: String): Boolean = decode(id, value) != null

    private const val MAX_VALUE_LENGTH = 1000
}
