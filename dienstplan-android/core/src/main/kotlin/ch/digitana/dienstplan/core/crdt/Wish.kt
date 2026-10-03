package ch.digitana.dienstplan.core.crdt

/** Art eines Wunsches. */
enum class WishKind(val code: String, val label: String) {
    DAY_OFF("WF", "Wunschfrei"),
    VACATION("FW", "Ferienwunsch"),
    UNAVAILABLE("NV", "Nicht verfügbar"),
    WORK("WA", "Wunscharbeitstag"),
}

/** Wie weit der Plan einen Wunsch erfüllt. */
enum class WishStatus {
    /** Eingeplant wie gewünscht. */
    FULFILLED,

    /** Noch nichts eingetragen (oder Schichtart unbekannt). */
    OPEN,

    /** Eingetragen, aber anders als gewünscht. */
    UNMET,
}

/**
 * Wunsch einer Person für einen Tag. Ein Wunscharbeitstag kann eine bestimmte Schichtart
 * nennen ([typeId], „Wunschschicht“). Format im Plan: `WF`, `FW`, `NV`, `WA` oder `WA:<id>`.
 */
data class Wish(val kind: WishKind, val typeId: String? = null) {
    init {
        require(typeId == null || (kind == WishKind.WORK && ShiftTypes.isValidId(typeId))) { "Ungültiger Wunsch" }
    }

    val code: String get() = if (typeId == null) kind.code else "${kind.code}:$typeId"

    /** Bezeichnung; bei einer Wunschschicht mit Namen der Schichtart. */
    fun label(types: ShiftTypeSet): String =
        if (typeId == null) kind.label else "Wunsch: ${types[typeId]?.name ?: "unbekannte Schicht"}"

    /** Vergleich mit der eingetragenen Schicht ([assignedId], [assigned]; null = leer). */
    fun status(assignedId: String?, assigned: ShiftType?): WishStatus {
        if (assignedId == null || assigned == null) return WishStatus.OPEN
        val works = assigned.kind == ShiftKind.WORK
        val met = when (kind) {
            WishKind.DAY_OFF, WishKind.VACATION, WishKind.UNAVAILABLE -> !works
            WishKind.WORK -> if (typeId != null) assignedId == typeId else works
        }
        return if (met) WishStatus.FULFILLED else WishStatus.UNMET
    }

    companion object {
        val DAY_OFF = Wish(WishKind.DAY_OFF)
        val VACATION = Wish(WishKind.VACATION)
        val UNAVAILABLE = Wish(WishKind.UNAVAILABLE)
        val WORK = Wish(WishKind.WORK)

        /** Wünsche ohne Schichtart, in der Reihenfolge der Anzeige. */
        val SIMPLE: List<Wish> = listOf(DAY_OFF, VACATION, UNAVAILABLE, WORK)

        /** Wunschschicht. */
        fun shift(typeId: String): Wish = Wish(WishKind.WORK, typeId)

        fun fromCode(code: String): Wish? {
            if (code.length > MAX_CODE_LENGTH) return null
            WishKind.entries.firstOrNull { it.code == code }?.let { return Wish(it) }
            val prefix = WishKind.WORK.code + ":"
            if (!code.startsWith(prefix)) return null
            val typeId = code.substring(prefix.length)
            return if (ShiftTypes.isValidId(typeId)) Wish(WishKind.WORK, typeId) else null
        }

        private const val MAX_CODE_LENGTH = 11
    }
}

/** Notizen zu einem Tag oder zu einem Dienst. */
object Notes {
    const val MAX_LENGTH = 200

    /** Gleiche Zeichenregeln wie bei Namen, nur länger. */
    fun isValid(note: String): Boolean = Names.isValid(note, MAX_LENGTH)

    /** Bereinigt eine lokale Eingabe; leer = Notiz löschen. */
    fun normalizeInput(input: String): String = Names.normalizeInput(input)

    /** `null` = in Ordnung (auch leer, das löscht die Notiz). */
    fun checkLocalInput(input: String): NameProblem? {
        val note = normalizeInput(input)
        if (note.isEmpty()) return null
        if (Names.length(note) > MAX_LENGTH) return NameProblem.TOO_LONG
        if (!isValid(note)) return NameProblem.INVALID_CHARACTERS
        return null
    }
}
