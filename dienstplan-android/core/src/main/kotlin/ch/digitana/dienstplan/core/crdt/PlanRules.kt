package ch.digitana.dienstplan.core.crdt

/**
 * Planungsregeln des Teams: Mindestruhezeit zwischen zwei Diensten (`c|rest`) und
 * Soll-Besetzung pro Arbeitsschicht und Wochentag (`b|<schichtart-id>`). Beides sind
 * gewöhnliche Einträge im Bucket `team` und laufen durch dieselbe Prüfung wie alle anderen.
 */
object PlanRules {
    /** Tägliche Ruhezeit nach dem Arbeitsgesetz (Art. 15a ArG): mindestens 11 Stunden. */
    const val DEFAULT_REST_MINUTES = 11 * 60

    /** Höchste einstellbare Ruhezeit; ein Dienst dauert höchstens 24 Stunden. */
    const val MAX_REST_MINUTES = 16 * 60

    /** Schrittweite in der Oberfläche. */
    const val REST_STEP_MINUTES = 30

    const val MAX_TARGET = 99

    /** Name der Einstellung „Mindestruhezeit in Minuten“ (0 = keine Warnung, leer = Standard). */
    const val REST = "rest"

    private val NUMBER = Regex("0|[1-9][0-9]{0,3}")
    private val TARGET = Regex("0|[1-9][0-9]?")

    fun isValidSetting(name: String, value: String): Boolean = when (name) {
        REST -> NUMBER.matches(value) && value.toInt() <= MAX_REST_MINUTES
        else -> false
    }

    /** Soll pro Wochentag, Montag zuerst; je 0–99, 0 = kein Soll. Format `3,3,3,3,3,2,2`. */
    fun encodeTargets(targets: List<Int>): String {
        require(targets.size == 7 && targets.all { it in 0..MAX_TARGET }) { "Ungültiges Soll" }
        return targets.joinToString(",")
    }

    /** null, wenn der Wert nicht genau dem Format entspricht. */
    fun decodeTargets(value: String): List<Int>? {
        if (value.length > MAX_TARGETS_LENGTH) return null
        val parts = value.split(',')
        if (parts.size != 7 || parts.any { !TARGET.matches(it) }) return null
        return parts.map { it.toInt() }
    }

    private const val MAX_TARGETS_LENGTH = 7 * 3
}
