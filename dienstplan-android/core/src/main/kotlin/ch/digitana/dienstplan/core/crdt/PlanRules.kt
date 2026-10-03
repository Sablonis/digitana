package ch.digitana.dienstplan.core.crdt

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/**
 * Planungsregeln des Teams: Mindestruhezeit zwischen zwei Diensten (`c|rest`), Wochenstunden
 * bei 100 % (`c|hours`), Kanton für die Feiertage (`c|canton`), Wunschfrist pro Monat
 * (`c|wish-JJJJ-MM`), Soll-Besetzung pro Arbeitsschicht und Wochentag (`b|<schichtart-id>`) und
 * das Pensum jeder Person (`p|<id>`). Alles sind gewöhnliche Einträge im Bucket `team` und
 * laufen durch dieselbe Prüfung wie alle anderen.
 */
object PlanRules {
    /** Tägliche Ruhezeit nach dem Arbeitsgesetz (Art. 15a ArG): mindestens 11 Stunden. */
    const val DEFAULT_REST_MINUTES = 11 * 60

    /** Höchste einstellbare Ruhezeit; ein Dienst dauert höchstens 24 Stunden. */
    const val MAX_REST_MINUTES = 16 * 60

    /** Schrittweite in der Oberfläche. */
    const val REST_STEP_MINUTES = 30

    const val MAX_TARGET = 99

    /** Wochenstunden bei 100 %, wenn das Team nichts festlegt: 42 Stunden. */
    const val DEFAULT_WEEK_MINUTES = 42 * 60
    const val MIN_WEEK_MINUTES = 60
    const val MAX_WEEK_MINUTES = 70 * 60
    const val WEEK_STEP_MINUTES = 30

    const val MIN_PENSUM = 1
    const val MAX_PENSUM = 100

    /** Name der Einstellung „Mindestruhezeit in Minuten“ (0 = keine Warnung, leer = Standard). */
    const val REST = "rest"

    /** Name der Einstellung „Wochenstunden bei 100 % in Minuten“ (leer = Standard). */
    const val HOURS = "hours"

    /** Name der Einstellung „Kanton für Feiertage“ (leer = keine Feiertage). */
    const val CANTON = "canton"

    private const val WISH_PREFIX = "wish-"

    private val NUMBER = Regex("0|[1-9][0-9]{0,3}")
    private val TARGET = Regex("0|[1-9][0-9]?")
    private val PENSUM = Regex("[1-9][0-9]?|100")
    private val DATE = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
    private val WISH_NAME = Regex("wish-([0-9]{4})-(0[1-9]|1[0-2])")

    /** Teil der Schlüssel-Regex in [PlanKeys]: alle erlaubten Namen. */
    internal const val SETTING_NAMES = "rest|hours|canton|wish-[0-9]{4}-(?:0[1-9]|1[0-2])"

    /** Ist [name] eine bekannte Einstellung (auch das Jahr einer Wunschfrist im erlaubten Bereich)? */
    fun isValidSettingName(name: String): Boolean = when (name) {
        REST, HOURS, CANTON -> true
        else -> wishDeadlineMonth(name) != null
    }

    fun isValidSetting(name: String, value: String): Boolean = when (name) {
        REST -> NUMBER.matches(value) && value.toInt() <= MAX_REST_MINUTES
        HOURS -> NUMBER.matches(value) && value.toInt() in MIN_WEEK_MINUTES..MAX_WEEK_MINUTES
        CANTON -> Canton.fromCode(value) != null
        else -> wishDeadlineMonth(name) != null && decodeDate(value) != null
    }

    /** Name der Wunschfrist für [month], z. B. `wish-2026-11`. */
    fun wishDeadline(month: YearMonth): String {
        require(month.year in PlanKeys.MIN_DATE.year..PlanKeys.MAX_DATE.year) { "Monat ausserhalb 2000–2100" }
        return WISH_PREFIX + month
    }

    /** Monat einer Wunschfrist aus ihrem Namen; null, wenn es keine ist. */
    fun wishDeadlineMonth(name: String): YearMonth? {
        val match = WISH_NAME.matchEntire(name) ?: return null
        val year = match.groupValues[1].toInt()
        if (year !in PlanKeys.MIN_DATE.year..PlanKeys.MAX_DATE.year) return null
        return YearMonth.of(year, match.groupValues[2].toInt())
    }

    /** Datum im Format `JJJJ-MM-TT` im Bereich 2000–2100; sonst null. */
    fun decodeDate(value: String): LocalDate? {
        val match = DATE.matchEntire(value) ?: return null
        val date = try {
            LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        } catch (e: DateTimeException) {
            return null
        }
        return date.takeIf(PlanKeys::isValidDate)
    }

    /** Pensum in Prozent (1–100) ohne führende Nullen. */
    fun isValidPensum(value: String): Boolean = PENSUM.matches(value)

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
