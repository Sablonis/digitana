package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import java.time.LocalDate

/** Zu kurze Ruhezeit zwischen zwei Diensten einer Person. */
data class RestIssue(
    /** Ruhe zwischen den Diensten in Minuten; negativ = sie überschneiden sich. */
    val restMinutes: Int,
    /** Der andere Dienst: Tag und Art. */
    val otherDate: LocalDate,
    val other: ShiftType,
    /** true: Der andere Dienst liegt davor (sonst danach). */
    val otherBefore: Boolean,
    /** Verlangte Mindestruhe in Minuten. */
    val minimumMinutes: Int,
)

/**
 * Ruhezeit zwischen zwei Arbeitsdiensten (Art. 15a ArG: mindestens 11 Stunden am Stück).
 *
 * Es zählen nur Arbeitsschichten mit Zeiten; ein Dienst über Mitternacht endet am Folgetag.
 * Weil ein Dienst höchstens 24 Stunden dauert und die Grenze höchstens 16 Stunden beträgt,
 * genügen die zwei Tage davor bzw. danach. Gerechnet wird in Ortszeit; eine Zeitumstellung in
 * der Nacht verschiebt das Ergebnis um eine Stunde.
 */
object RestRules {

    /** Beginn und Ende in Minuten ab 1970-01-01 (Ortszeit); null für Frei, Abwesenheit oder ohne Zeiten. */
    private class Span(val start: Long, val end: Long)

    private fun span(type: ShiftType?, date: LocalDate): Span? {
        if (type == null || type.kind != ShiftKind.WORK) return null
        val start = type.start ?: return null
        val begin = date.toEpochDay() * MINUTES_PER_DAY + start.toSecondOfDay() / 60
        return Span(begin, begin + type.durationMinutes)
    }

    /** Zu kurze Ruhe vor dem Dienst [typeId] an [date]; bei mehreren die kürzeste. */
    fun issueBefore(state: PlanState, types: ShiftTypeSet, memberId: String, date: LocalDate, typeId: String?, minimum: Int): RestIssue? {
        if (minimum <= 0) return null
        val current = span(types[typeId], date) ?: return null
        var worst: RestIssue? = null
        for (back in 1L..2L) {
            val otherDate = date.minusDays(back)
            if (!PlanKeys.isValidDate(otherDate)) continue
            val other = types[state.shift(memberId, otherDate)] ?: continue
            val otherSpan = span(other, otherDate) ?: continue
            val rest = (current.start - otherSpan.end).toInt()
            if (rest < minimum && (worst == null || rest < worst.restMinutes)) worst = RestIssue(rest, otherDate, other, true, minimum)
        }
        return worst
    }

    /** Zu kurze Ruhe nach dem Dienst [typeId] an [date]; bei mehreren die kürzeste. */
    fun issueAfter(state: PlanState, types: ShiftTypeSet, memberId: String, date: LocalDate, typeId: String?, minimum: Int): RestIssue? {
        if (minimum <= 0) return null
        val current = span(types[typeId], date) ?: return null
        var worst: RestIssue? = null
        for (ahead in 1L..2L) {
            val otherDate = date.plusDays(ahead)
            if (!PlanKeys.isValidDate(otherDate)) continue
            val other = types[state.shift(memberId, otherDate)] ?: continue
            val otherSpan = span(other, otherDate) ?: continue
            val rest = (otherSpan.start - current.end).toInt()
            if (rest < minimum && (worst == null || rest < worst.restMinutes)) worst = RestIssue(rest, otherDate, other, false, minimum)
        }
        return worst
    }

    /**
     * Was ein Dienst [typeId] an [date] für die Ruhezeit bedeuten würde – davor oder danach,
     * die kürzere Ruhe zählt. Für die Auswahl im Eintragsfenster.
     */
    fun issueFor(state: PlanState, types: ShiftTypeSet, memberId: String, date: LocalDate, typeId: String, minimum: Int): RestIssue? {
        val before = issueBefore(state, types, memberId, date, typeId, minimum)
        val after = issueAfter(state, types, memberId, date, typeId, minimum)
        return when {
            before == null -> after
            after == null -> before
            after.restMinutes < before.restMinutes -> after
            else -> before
        }
    }

    private const val MINUTES_PER_DAY = 24L * 60
}
