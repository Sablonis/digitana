package ch.digitana.dienstplan.core.crdt

/** Schichtarten. Farben definiert die Oberfläche. */
enum class Shift(val code: String, val label: String, val hours: Int) {
    FRUEH("F", "Früh", 8),
    SPAET("S", "Spät", 8),
    NACHT("N", "Nacht", 8),
    FREI("X", "Frei", 0),
    URLAUB("U", "Urlaub", 0);

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: String): Shift? = byCode[code]

        /** Reihenfolge beim Antippen: leer → F → S → N → X → U → leer. */
        fun next(current: Shift?): Shift? = when (current) {
            null -> FRUEH
            FRUEH -> SPAET
            SPAET -> NACHT
            NACHT -> FREI
            FREI -> URLAUB
            URLAUB -> null
        }
    }
}
