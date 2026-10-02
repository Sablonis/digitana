package ch.digitana.dienstplan.core.crdt

/** Wunsch oder Abwesenheit, die eine Person für einen Tag einträgt. */
enum class Wish(val code: String, val label: String) {
    DAY_OFF("WF", "Wunschfrei"),
    VACATION("FW", "Ferienwunsch"),
    UNAVAILABLE("NV", "Nicht verfügbar");

    companion object {
        fun fromCode(code: String): Wish? = entries.firstOrNull { it.code == code }
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
