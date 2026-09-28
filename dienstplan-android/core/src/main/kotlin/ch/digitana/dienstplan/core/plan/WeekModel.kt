package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.Shift
import ch.digitana.dienstplan.core.crdt.WeekId
import java.time.DayOfWeek
import java.time.LocalDate

/** Alles, was die Wochenansicht braucht – reine Daten, ohne Android. */
data class WeekModel(
    val week: WeekId,
    val days: List<DayInfo>,
    val rows: List<MemberRow>,
    val coverage: List<Coverage>,
) {
    val totalHours: Int get() = rows.sumOf { it.hours }

    data class DayInfo(
        val date: LocalDate,
        val isToday: Boolean,
        val isWeekend: Boolean,
        /** Liegt das Datum im erlaubten Bereich 2000–2100? */
        val editable: Boolean,
    )

    data class MemberRow(
        val member: Member,
        val shifts: List<Shift?>,
        val hours: Int,
    )

    /** Besetzung pro Tag: Anzahl Früh-, Spät- und Nachtschichten. */
    data class Coverage(val frueh: Int, val spaet: Int, val nacht: Int)

    companion object {
        fun build(state: PlanState, week: WeekId, today: LocalDate): WeekModel {
            val dates = week.days
            val days = dates.map { date ->
                DayInfo(
                    date = date,
                    isToday = date == today,
                    isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY,
                    editable = PlanKeys.isValidDate(date),
                )
            }
            val rows = state.members().map { member ->
                val shifts = dates.map { date ->
                    if (PlanKeys.isValidDate(date)) state.shift(member.id, date) else null
                }
                MemberRow(member, shifts, shifts.sumOf { it?.hours ?: 0 })
            }
            val coverage = dates.indices.map { i ->
                var f = 0
                var s = 0
                var n = 0
                for (row in rows) {
                    when (row.shifts[i]) {
                        Shift.FRUEH -> f++
                        Shift.SPAET -> s++
                        Shift.NACHT -> n++
                        else -> Unit
                    }
                }
                Coverage(f, s, n)
            }
            return WeekModel(week, days, rows, coverage)
        }

        /** Erste und letzte Woche, die vollständig im erlaubten Datumsbereich liegen. */
        val FIRST_WEEK: WeekId = WeekId.of(PlanKeys.MIN_DATE).next()
        val LAST_WEEK: WeekId = WeekId.of(PlanKeys.MAX_DATE).previous()
    }
}
