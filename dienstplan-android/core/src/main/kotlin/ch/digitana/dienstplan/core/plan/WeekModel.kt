package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.crdt.WishStatus
import java.time.DayOfWeek
import java.time.LocalDate

/** Ein Tag in Wochen- oder Monatsansicht. */
data class DayInfo(
    val date: LocalDate,
    val isToday: Boolean,
    val isWeekend: Boolean,
    /** Liegt das Datum im erlaubten Bereich 2000–2100? */
    val editable: Boolean,
    /** Notiz zum Tag oder null. */
    val note: String? = null,
)

/** Ein Feld des Plans: Schicht, Wunsch und Notiz einer Person an einem Tag. */
data class Cell(
    /** ID der Schichtart oder null (leer). */
    val typeId: String? = null,
    /** Die Schichtart; null, wenn leer oder (noch) unbekannt. */
    val type: ShiftType? = null,
    val wish: Wish? = null,
    val note: String? = null,
) {
    val isEmpty: Boolean get() = typeId == null

    /** Eingetragen, aber die Schichtart ist (noch) nicht bekannt, z. B. vor dem ersten Abgleich. */
    val isUnknownType: Boolean get() = typeId != null && type == null

    /** Erfüllt der Plan den Wunsch? null = kein Wunsch. */
    val wishStatus: WishStatus? get() = wish?.status(typeId, type)

    companion object {
        val EMPTY = Cell()
    }
}

/** Zeile einer Person mit Feldern und bezahlten Minuten im Zeitraum. */
data class MemberRow(
    val member: Member,
    val cells: List<Cell>,
    val minutes: Int,
)

/** Besetzung eines Tages: Anzahl pro Arbeitsschicht (in der Reihenfolge der Schichtarten). */
data class DayCoverage(val counts: List<Pair<ShiftType, Int>>) {
    val total: Int get() = counts.sumOf { it.second }

    fun count(typeId: String): Int = counts.firstOrNull { it.first.id == typeId }?.second ?: 0
}

/** Alles, was die Wochenansicht braucht – reine Daten, ohne Android. */
data class WeekModel(
    val week: WeekId,
    val days: List<DayInfo>,
    val rows: List<MemberRow>,
    val coverage: List<DayCoverage>,
    val types: ShiftTypeSet,
) {
    val totalMinutes: Int get() = rows.sumOf { it.minutes }

    /** Wünsche der Woche pro Person. */
    val wishTallies: List<WishTally> get() = WishTally.of(rows)

    /** Arbeitsschichten, die in der Besetzungszeile erscheinen: aktive und alle in dieser Woche benutzten. */
    val coverageTypes: List<ShiftType> get() = coverage.firstOrNull()?.counts?.map { it.first }.orEmpty()

    companion object {
        fun build(state: PlanState, week: WeekId, today: LocalDate): WeekModel {
            val grid = PlanGrid.build(state, week.days, today)
            return WeekModel(week, grid.days, grid.rows, grid.coverage, grid.types)
        }

        /** Erste und letzte Woche, die vollständig im erlaubten Datumsbereich liegen. */
        val FIRST_WEEK: WeekId = WeekId.of(PlanKeys.MIN_DATE).next()
        val LAST_WEEK: WeekId = WeekId.of(PlanKeys.MAX_DATE).previous()
    }
}

/** Gemeinsamer Aufbau für Woche und Monat: Tage, Zeilen und Besetzung für beliebige Daten. */
internal data class PlanGrid(
    val days: List<DayInfo>,
    val rows: List<MemberRow>,
    val coverage: List<DayCoverage>,
    val types: ShiftTypeSet,
) {
    companion object {
        fun build(state: PlanState, dates: List<LocalDate>, today: LocalDate): PlanGrid {
            val types = state.shiftTypes
            val days = dates.map { date ->
                val editable = PlanKeys.isValidDate(date)
                DayInfo(
                    date = date,
                    isToday = date == today,
                    isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY,
                    editable = editable,
                    note = if (editable) state.dayNote(date) else null,
                )
            }
            val rows = state.members().map { member ->
                val cells = days.map { day -> if (day.editable) cell(state, types, member.id, day.date) else Cell.EMPTY }
                MemberRow(member, cells, cells.sumOf { it.type?.paidMinutes ?: 0 })
            }
            // Spalten der Besetzung: aktive Arbeitsschichten und alle, die im Zeitraum vorkommen.
            val used = rows.flatMap { row -> row.cells.mapNotNull { it.type } }.toSet()
            val coverageTypes = types.all.filter { it.countsForCoverage && (!it.archived || it in used) }
            val coverage = days.indices.map { i ->
                DayCoverage(coverageTypes.map { type -> type to rows.count { it.cells[i].type?.id == type.id } })
            }
            return PlanGrid(days, rows, coverage, types)
        }

        private fun cell(state: PlanState, types: ShiftTypeSet, memberId: String, date: LocalDate): Cell {
            val typeId = state.shift(memberId, date)
            val wish = state.wish(memberId, date)
            val note = state.memberNote(memberId, date)
            if (typeId == null && wish == null && note == null) return Cell.EMPTY
            return Cell(typeId, types[typeId], wish, note)
        }
    }
}
