package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import java.time.LocalDate
import java.time.YearMonth

/** Monatsansicht: alle Personen über alle Tage eines Monats, mit Stunden pro Monat. */
data class MonthModel(
    val month: YearMonth,
    val days: List<DayInfo>,
    val rows: List<MemberRow>,
    val coverage: List<DayCoverage>,
    val types: ShiftTypeSet,
) {
    val totalMinutes: Int get() = rows.sumOf { it.minutes }

    companion object {
        val FIRST_MONTH: YearMonth = YearMonth.from(PlanKeys.MIN_DATE)
        val LAST_MONTH: YearMonth = YearMonth.from(PlanKeys.MAX_DATE)

        fun build(state: PlanState, month: YearMonth, today: LocalDate): MonthModel {
            val dates = (1..month.lengthOfMonth()).map { month.atDay(it) }
            val grid = PlanGrid.build(state, dates, today)
            return MonthModel(month, grid.days, grid.rows, grid.coverage, grid.types)
        }
    }
}
