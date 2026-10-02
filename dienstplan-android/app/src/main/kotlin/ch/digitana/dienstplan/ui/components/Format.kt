package ch.digitana.dienstplan.ui.components

import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Schweizer Schreibweise: Dezimalpunkt, „h“ für Stunden. */
object Format {
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.GERMAN)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN)

    /** „8“, „8.5“, „37.75“ – ohne Einheit. */
    fun hoursNumber(minutes: Int): String {
        if (minutes % 60 == 0) return (minutes / 60).toString()
        return BigDecimal(minutes).divide(BigDecimal(60), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /** „8 h“, „8.5 h“ */
    fun hours(minutes: Int): String = "${hoursNumber(minutes)} h"

    /** „06:00–14:00“ oder null ohne Zeiten. */
    fun timeRange(type: ShiftType): String? {
        val start = type.start ?: return null
        val end = type.end ?: return null
        return "${ShiftTypes.formatTime(start)}–${ShiftTypes.formatTime(end)}"
    }

    /** Kurz für enge Stellen: „6–14“, „7:30–16“. */
    fun shortTimeRange(type: ShiftType): String? {
        val start = type.start ?: return null
        val end = type.end ?: return null
        fun short(hour: Int, minute: Int) = if (minute == 0) hour.toString() else "$hour:%02d".format(Locale.ROOT, minute)
        return "${short(start.hour, start.minute)}–${short(end.hour, end.minute)}"
    }

    /** „Oktober 2026“ */
    fun monthLabel(month: YearMonth): String =
        month.atDay(1).format(MONTH_YEAR).replaceFirstChar { it.titlecase(Locale.GERMAN) }

    /** „1. Oktober“ */
    fun dayMonth(date: LocalDate): String = date.format(DAY_MONTH)

    /** Initialen für den Avatar: „Anna Muster“ → „AM“, „ben“ → „B“. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = words.take(2).mapNotNull { word ->
            val cp = word.codePointAt(0)
            if (Character.isLetterOrDigit(cp)) String(Character.toChars(cp)).uppercase(Locale.GERMAN) else null
        }
        return letters.joinToString("").ifEmpty { "?" }
    }
}
