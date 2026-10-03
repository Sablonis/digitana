package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.WeekId
import java.time.LocalDate
import java.time.YearMonth

/** Ein Tag in „Meine Dienste“. */
data class MyDay(
    val date: LocalDate,
    val cell: Cell,
    val dayNote: String?,
    val isToday: Boolean,
)

/**
 * „Meine Dienste“: die Tage einer Person ab Montag der laufenden Woche, mit Stunden der
 * laufenden Woche und des laufenden Monats.
 */
data class MyShiftsModel(
    val member: Member,
    val today: LocalDate,
    val days: List<MyDay>,
    val weekMinutes: Int,
    val monthMinutes: Int,
    val types: ShiftTypeSet,
    /** Soll und Saldo des laufenden Monats (mit angerechneten Abwesenheiten). */
    val monthBalance: WorkBalance = WorkBalance(monthMinutes, null),
    /** Saldo seit Jahresbeginn; null ohne Pensum. */
    val yearDeltaMinutes: Int? = null,
    /** Feiertage in den angezeigten Tagen. */
    val holidays: Map<LocalDate, Holiday> = emptyMap(),
) {
    /** Der nächste Arbeitsdienst ab heute, sonst null. */
    val next: MyDay? get() = days.firstOrNull { !it.date.isBefore(today) && it.cell.type?.countsForCoverage == true }

    companion object {
        const val DEFAULT_WEEKS = 8

        /** null, wenn es die Person nicht (mehr) gibt. */
        fun build(state: PlanState, memberId: String, today: LocalDate, weeks: Int = DEFAULT_WEEKS): MyShiftsModel? {
            val member = state.members().firstOrNull { it.id == memberId } ?: return null
            val types = state.shiftTypes
            val first = WeekId.of(today).monday
            val dates = (0 until weeks * 7).map { first.plusDays(it.toLong()) }.filter(PlanKeys::isValidDate)
            val days = dates.map { date -> MyDay(date, cellOf(state, types, memberId, date), state.dayNote(date), date == today) }
            val week = WeekId.of(today)
            val weekMinutes = week.days.filter(PlanKeys::isValidDate).sumOf { minutes(state, types, memberId, it) }
            val month = YearMonth.from(today)
            val monthMinutes = (1..month.lengthOfMonth()).map { month.atDay(it) }.sumOf { minutes(state, types, memberId, it) }
            val balance = if (PlanKeys.isValidDate(month.atDay(1)) && PlanKeys.isValidDate(month.atEndOfMonth())) {
                WorkTime.balance(state, memberId, month)
            } else {
                WorkBalance(monthMinutes, null)
            }
            val holidays = if (dates.isEmpty()) emptyMap() else SwissHolidays.between(state.canton, dates.first(), dates.last())
            return MyShiftsModel(
                member, today, days, weekMinutes, monthMinutes, types, balance,
                WorkTime.yearToDateDelta(state, memberId, month), holidays,
            )
        }

        /** Die nächsten [limit] Tage ab heute mit eingetragener Schicht (für das Widget). */
        fun upcoming(state: PlanState, memberId: String, today: LocalDate, limit: Int, horizonDays: Int = 60): List<MyDay> {
            val types = state.shiftTypes
            val result = ArrayList<MyDay>()
            for (offset in 0 until horizonDays) {
                val date = today.plusDays(offset.toLong())
                if (!PlanKeys.isValidDate(date)) break
                val cell = cellOf(state, types, memberId, date)
                if (cell.typeId == null) continue
                result += MyDay(date, cell, state.dayNote(date), date == today)
                if (result.size >= limit) break
            }
            return result
        }

        private fun cellOf(state: PlanState, types: ShiftTypeSet, memberId: String, date: LocalDate): Cell {
            val typeId = state.shift(memberId, date)
            val rest = RestRules.issueBefore(state, types, memberId, date, typeId, state.restMinutes)
            val offered = typeId != null && state.offer(memberId, date)?.typeId == typeId
            return Cell(typeId, types[typeId], state.wish(memberId, date), state.memberNote(memberId, date), rest, offered)
        }

        private fun minutes(state: PlanState, types: ShiftTypeSet, memberId: String, date: LocalDate): Int {
            if (!PlanKeys.isValidDate(date)) return 0
            return types[state.shift(memberId, date)]?.paidMinutes ?: 0
        }
    }
}
