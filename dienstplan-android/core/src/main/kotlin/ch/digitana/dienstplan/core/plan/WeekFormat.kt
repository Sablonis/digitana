package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.WeekId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Deutsche Beschriftungen für die Wochenansicht. */
object WeekFormat {
    private val WEEKDAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    private val LONG_WEEKDAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
    private val DAY_MONTH_LONG = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN)
    private val DAY_MONTH_YEAR_LONG = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)

    /** „KW 39“ */
    fun weekLabel(week: WeekId): String = "KW ${week.week}"

    /**
     * Zeitraum der Woche, z. B. „21.–27. September 2026“,
     * „28. September – 4. Oktober 2026“ oder „29. Dezember 2025 – 4. Januar 2026“.
     */
    fun rangeLabel(week: WeekId): String {
        val start = week.monday
        val end = week.sunday
        return when {
            start.month == end.month -> "${start.dayOfMonth}.–${end.dayOfMonth}. ${end.format(MONTH_YEAR)}"
            start.year == end.year -> "${start.format(DAY_MONTH_LONG)} – ${end.format(DAY_MONTH_YEAR_LONG)}"
            else -> "${start.format(DAY_MONTH_YEAR_LONG)} – ${end.format(DAY_MONTH_YEAR_LONG)}"
        }
    }

    /** Kurzer Wochentag, z. B. „Mo“. */
    fun weekday(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

    /** Kurzes Datum, z. B. „21.9.“ */
    fun shortDate(date: LocalDate): String = "${date.dayOfMonth}.${date.monthValue}."

    /** Für Screenreader, z. B. „Montag, 21. September 2026“. */
    fun longDate(date: LocalDate): String =
        "${LONG_WEEKDAYS[date.dayOfWeek.value - 1]}, ${date.format(DAY_MONTH_YEAR_LONG)}"
}
