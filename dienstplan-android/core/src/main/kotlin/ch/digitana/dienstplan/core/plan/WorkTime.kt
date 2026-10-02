package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.WishStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/** Geplante Stunden und Soll einer Person in einem Zeitraum. */
data class WorkBalance(
    /** Bezahlte Minuten der Dienste plus angerechnete Abwesenheiten. */
    val plannedMinutes: Int,
    /** Soll nach Pensum; null, wenn die Person kein Pensum hat. */
    val targetMinutes: Int?,
) {
    /** Plus (positiv) oder Minus (negativ); null ohne Soll. */
    val deltaMinutes: Int? get() = targetMinutes?.let { plannedMinutes - it }
}

/**
 * Soll-Stunden und Saldo. Das Soll ergibt sich aus den Wochenstunden des Teams bei 100 %
 * (`c|hours`, Standard 42 h) und dem Pensum der Person: Pro Werktag (Montag bis Freitag,
 * ohne Feiertage des gewählten Kantons) ein Fünftel der Wochenstunden mal Pensum.
 *
 * Geplant sind die bezahlten Minuten der Dienste. Abwesenheiten (z. B. Ferien) zählen mit
 * ihrer Anrechnung; ist bei der Schichtart keine festgelegt, wird an Werktagen das Tagessoll
 * angerechnet, damit Ferien den Saldo nicht ins Minus drücken.
 */
object WorkTime {

    /** Tagessoll in Minuten: Wochenstunden × Pensum / 5. */
    fun dailyTargetMinutes(weekMinutes: Int, pensum: Int): Int = (weekMinutes * pensum / 100.0 / 5.0).roundToInt()

    fun isWorkday(date: LocalDate, holidays: Map<LocalDate, Holiday>): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && date !in holidays

    /** Angerechnete Minuten eines Feldes (siehe Klassenbeschreibung). */
    fun creditedMinutes(type: ShiftType?, date: LocalDate, dailyTarget: Int?, holidays: Map<LocalDate, Holiday>): Int = when {
        type == null -> 0
        type.kind == ShiftKind.ABSENCE && !type.hasTimes && type.creditMinutes == 0 ->
            if (dailyTarget != null && isWorkday(date, holidays)) dailyTarget else 0
        else -> type.paidMinutes
    }

    /** Geplant und Soll der Person an den Tagen [dates]. */
    fun balance(state: PlanState, memberId: String, dates: List<LocalDate>, holidays: Map<LocalDate, Holiday>): WorkBalance {
        val types = state.shiftTypes
        val pensum = state.pensum(memberId)
        val daily = pensum?.let { dailyTargetMinutes(state.weekMinutes, it) }
        var planned = 0
        var target = 0
        for (date in dates) {
            if (!PlanKeys.isValidDate(date)) continue
            planned += creditedMinutes(types[state.shift(memberId, date)], date, daily, holidays)
            if (daily != null && isWorkday(date, holidays)) target += daily
        }
        return WorkBalance(planned, if (daily != null) target else null)
    }

    /** Alle Tage eines Monats. */
    fun daysOf(month: YearMonth): List<LocalDate> = (1..month.lengthOfMonth()).map { month.atDay(it) }

    fun balance(state: PlanState, memberId: String, month: YearMonth): WorkBalance {
        val days = daysOf(month)
        return balance(state, memberId, days, SwissHolidays.between(state.canton, days.first(), days.last()))
    }

    /**
     * Saldo seit Jahresbeginn bis Ende [month]. Es zählen nur Monate, in denen für die Person
     * schon etwas eingetragen ist, damit Monate vor dem Start des Plans kein Minus erzeugen.
     * null ohne Pensum.
     */
    fun yearToDateDelta(state: PlanState, memberId: String, month: YearMonth): Int? {
        if (state.pensum(memberId) == null) return null
        var delta = 0
        for (m in 1..month.monthValue) {
            val current = YearMonth.of(month.year, m)
            if (!PlanKeys.isValidDate(current.atDay(1)) || !PlanKeys.isValidDate(current.atEndOfMonth())) continue
            val days = daysOf(current)
            if (days.none { state.shift(memberId, it) != null }) continue
            delta += balance(state, memberId, current).deltaMinutes ?: 0
        }
        return delta
    }
}

/** Nachtarbeit nach Arbeitsgesetz: 23 bis 6 Uhr. */
object NightWork {
    private const val START = 23 * 60
    private const val END = 6 * 60
    private const val DAY = 24 * 60

    /** Mindestens so viele Minuten zwischen 23 und 6 Uhr machen einen Dienst zum Nachtdienst. */
    const val THRESHOLD_MINUTES = 3 * 60

    /** Minuten eines Arbeitsdienstes zwischen 23 und 6 Uhr. */
    fun minutes(type: ShiftType): Int {
        val start = type.start ?: return 0
        if (type.kind != ShiftKind.WORK) return 0
        val begin = start.toSecondOfDay() / 60
        val end = begin + type.durationMinutes
        var total = 0
        // Nachtfenster am Vortag, am Tag selbst und am Folgetag (ein Dienst dauert höchstens 24 h).
        for (offset in listOf(-DAY, 0, DAY)) {
            val windowStart = START + offset
            val windowEnd = DAY + END + offset
            total += maxOf(0, minOf(end, windowEnd) - maxOf(begin, windowStart))
        }
        return total
    }

    fun isNight(type: ShiftType?): Boolean = type != null && minutes(type) >= THRESHOLD_MINUTES
}

/** Kennzahlen einer Person in einem Zeitraum (Auswertung und faire Verteilung). */
data class MemberStats(
    val member: Member,
    /** Pensum in Prozent; null = nicht angegeben. */
    val pensum: Int?,
    val balance: WorkBalance,
    val workShifts: Int,
    val weekendShifts: Int,
    val nightShifts: Int,
    val holidayShifts: Int,
    val wishes: Int,
    val wishesFulfilled: Int,
    val wishesUnmet: Int,
)

/** Auswertung eines Zeitraums über alle Personen. */
data class PlanStats(
    val from: LocalDate,
    val to: LocalDate,
    val holidays: Map<LocalDate, Holiday>,
    val members: List<MemberStats>,
) {
    /** Durchschnitt der Wochenend- und Nachtdienste: Wer deutlich darüber liegt, trägt mehr. */
    val averageWeekend: Double get() = members.map { it.weekendShifts }.average().takeIf { !it.isNaN() } ?: 0.0
    val averageNight: Double get() = members.map { it.nightShifts }.average().takeIf { !it.isNaN() } ?: 0.0

    companion object {
        fun of(state: PlanState, from: LocalDate, to: LocalDate): PlanStats {
            val holidays = SwissHolidays.between(state.canton, from, to)
            val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.filter(PlanKeys::isValidDate).toList()
            val types = state.shiftTypes
            val members = state.members().map { member -> statsOf(state, types, member, dates, holidays) }
            return PlanStats(from, to, holidays, members)
        }

        fun of(state: PlanState, month: YearMonth): PlanStats = of(state, month.atDay(1), month.atEndOfMonth())

        private fun statsOf(state: PlanState, types: ShiftTypeSet, member: Member, dates: List<LocalDate>, holidays: Map<LocalDate, Holiday>): MemberStats {
            var work = 0
            var weekend = 0
            var night = 0
            var onHoliday = 0
            var wishes = 0
            var fulfilled = 0
            var unmet = 0
            for (date in dates) {
                val typeId = state.shift(member.id, date)
                val type = types[typeId]
                if (type?.kind == ShiftKind.WORK) {
                    work++
                    if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) weekend++
                    if (NightWork.isNight(type)) night++
                    if (date in holidays) onHoliday++
                }
                val wish = state.wish(member.id, date) ?: continue
                wishes++
                when (wish.status(typeId, type)) {
                    WishStatus.FULFILLED -> fulfilled++
                    WishStatus.UNMET -> unmet++
                    WishStatus.OPEN -> Unit
                }
            }
            val balance = WorkTime.balance(state, member.id, dates, holidays)
            return MemberStats(member, state.pensum(member.id), balance, work, weekend, night, onHoliday, wishes, fulfilled, unmet)
        }
    }
}
