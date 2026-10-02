package ch.digitana.dienstplan.core.crdt

/** Harte Grenzen für alles, was von aussen kommt. */
object Limits {
    const val MAX_ENTRIES_PER_EVENT = 5000
    const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
    const val MAX_NAME_LENGTH_REMOTE = 60
    const val MAX_NAME_LENGTH_LOCAL = 40
    const val MAX_FUTURE_MILLIS = 24L * 60 * 60 * 1000
}

/**
 * Prüft einzelne Einträge. Die Regeln sind bewusst feste Listen statt
 * Unicode-Kategorien der Laufzeit: Sonst könnten zwei Geräte mit verschiedenen
 * Android-Versionen denselben Eintrag unterschiedlich bewerten und nie konvergieren.
 */
object EntryValidator {

    enum class Problem { KEY_FORMAT, WRONG_BUCKET, VALUE, TIMESTAMP, DEVICE }

    private val DEVICE_ID = Regex("[0-9a-f]{16}")

    fun isValidDeviceId(device: String): Boolean = DEVICE_ID.matches(device)

    /** Vollständige Prüfung eines empfangenen Eintrags. `null` = gültig. */
    fun check(bucket: String, key: String, entry: Entry, nowMillis: Long): Problem? {
        val parsed = PlanKeys.parse(key) ?: return Problem.KEY_FORMAT
        if (Buckets.forKey(parsed) != bucket) return Problem.WRONG_BUCKET
        if (!isValidValue(parsed, entry.value)) return Problem.VALUE
        if (entry.timestamp <= 0 || entry.timestamp > nowMillis + Limits.MAX_FUTURE_MILLIS) {
            return Problem.TIMESTAMP
        }
        if (!isValidDeviceId(entry.device)) return Problem.DEVICE
        return null
    }

    fun isValidValue(key: PlanKey, value: String): Boolean = value.isEmpty() || when (key) {
        is PlanKey.Member, is PlanKey.Device -> Names.isValid(value, Limits.MAX_NAME_LENGTH_REMOTE)
        // Verweis auf eine Schichtart; ob sie (schon) definiert ist, entscheidet die Anzeige.
        is PlanKey.Shift -> ShiftTypes.isValidId(value)
        is PlanKey.ShiftType -> ShiftTypes.isValidValue(key.typeId, value)
        is PlanKey.Pattern -> ShiftPatterns.isValidValue(key.patternId, value)
        is PlanKey.DayNote, is PlanKey.MemberNote -> Notes.isValid(value)
        is PlanKey.Wish -> Wish.fromCode(value) != null
    }
}

/** Warum eine lokale Namenseingabe abgelehnt wird. */
enum class NameProblem { EMPTY, TOO_LONG, INVALID_CHARACTERS }

object Names {
    /** Prüft eine lokale Eingabe nach [normalizeInput]; `null` = in Ordnung. */
    fun checkLocalInput(input: String): NameProblem? {
        val name = normalizeInput(input)
        if (name.isEmpty()) return NameProblem.EMPTY
        if (length(name) > Limits.MAX_NAME_LENGTH_LOCAL) return NameProblem.TOO_LONG
        if (!isValid(name, Limits.MAX_NAME_LENGTH_LOCAL)) return NameProblem.INVALID_CHARACTERS
        return null
    }

    /**
     * Gültiger Name: 1..[maxCodePoints] Unicode-Zeichen, nicht nur Leerraum,
     * keine Steuerzeichen, keine Zeilen-/Absatztrenner, keine Bidi-Steuerzeichen
     * oder unsichtbaren Formatzeichen, keine unvollständigen Surrogate.
     */
    fun isValid(name: String, maxCodePoints: Int): Boolean {
        if (name.isEmpty() || name.isBlank()) return false
        var count = 0
        var i = 0
        while (i < name.length) {
            val c = name[i]
            val codePoint: Int
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= name.length || !Character.isLowSurrogate(name[i + 1])) return false
                codePoint = Character.toCodePoint(c, name[i + 1])
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                codePoint = c.code
                i += 1
            }
            if (isForbidden(codePoint)) return false
            count++
            if (count > maxCodePoints) return false
        }
        return true
    }

    /** Bereinigt eine lokale Eingabe: Ränder weg, Leerraumfolgen zu einem Leerzeichen. */
    fun normalizeInput(input: String): String = input.trim().replace(WHITESPACE_RUN, " ")

    /** Zählt Unicode-Zeichen (Codepoints) statt UTF-16-Einheiten. */
    fun length(name: String): Int = name.codePointCount(0, name.length)

    private val WHITESPACE_RUN = Regex("\\s+")

    private fun isForbidden(cp: Int): Boolean =
        cp <= 0x1F || cp in 0x7F..0x9F || // C0/C1-Steuerzeichen, DEL
            cp == 0x2028 || cp == 0x2029 || // Zeilen-/Absatztrenner
            cp == 0x061C || cp == 0x200E || cp == 0x200F || // Bidi-Markierungen
            cp in 0x202A..0x202E || cp in 0x2066..0x2069 || // Bidi-Einbettungen/-Isolate
            cp == 0x200B || cp in 0x2060..0x2064 || cp == 0xFEFF || // unsichtbare Zeichen
            cp == 0xFFFE || cp == 0xFFFF // Nichtzeichen
}
