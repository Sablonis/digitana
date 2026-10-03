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
    /** Feiertag im Kanton des Teams oder null. */
    val holiday: Holiday? = null,
)

/** Ein Feld des Plans: Schicht, Wunsch und Notiz einer Person an einem Tag. */
data class Cell(
    /** ID der Schichtart oder null (leer). */
    val typeId: String? = null,
    /** Die Schichtart; null, wenn leer oder (noch) unbekannt. */
    val type: ShiftType? = null,
    val wish: Wish? = null,
    val note: String? = null,
    /** Zu kurze Ruhezeit vor diesem Dienst; null = in Ordnung oder kein Arbeitsdienst. */
    val rest: RestIssue? = null,
    /** Die Person bietet diesen Dienst zum Abgeben an (Angebot noch gültig). */
    val offered: Boolean = false,
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

/**
 * Besetzung eines Tages: Anzahl pro Arbeitsschicht (in der Reihenfolge der Schichtarten) und
 * das Soll des Wochentags ([targets]: Schichtart-ID → Soll > 0).
 */
data class DayCoverage(val counts: List<Pair<ShiftType, Int>>, val targets: Map<String, Int> = emptyMap()) {
    val total: Int get() = counts.sumOf { it.second }

    fun count(typeId: String): Int = counts.firstOrNull { it.first.id == typeId }?.second ?: 0

    /** Soll dieser Art an diesem Tag; 0 = keins. */
    fun target(typeId: String): Int = targets[typeId] ?: 0

    val hasTargets: Boolean get() = targets.isNotEmpty()

    /** Summe der Soll-Werte aller Arten. */
    val totalTarget: Int get() = counts.sumOf { (type, _) -> target(type.id) }

    /** Fehlende Personen pro Art (Soll minus Ist, nicht negativ), zusammengezählt. */
    val shortfall: Int get() = counts.sumOf { (type, count) -> maxOf(0, target(type.id) - count) }

    /** Arten, die unter dem Soll liegen. */
    val understaffed: List<ShiftType> get() = counts.filter { (type, count) -> count < target(type.id) }.map { it.first }
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

    /** Dienste mit zu kurzer Ruhezeit davor. */
    val restIssueCount: Int get() = rows.sumOf { row -> row.cells.count { it.rest != null } }

    /** Schichten (Tag × Art) unter dem Soll. */
    val understaffedCount: Int get() = coverage.sumOf { it.understaffed.size }

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
            val holidays = if (dates.isEmpty()) emptyMap() else SwissHolidays.between(state.canton, dates.min(), dates.max())
            val days = dates.map { date ->
                val editable = PlanKeys.isValidDate(date)
                DayInfo(
                    date = date,
                    isToday = date == today,
                    isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY,
                    editable = editable,
                    note = if (editable) state.dayNote(date) else null,
                    holiday = holidays[date],
                )
            }
            val restMinutes = state.restMinutes
            val rows = state.members().map { member ->
                val cells = days.map { day -> if (day.editable) cell(state, types, member.id, day.date, restMinutes) else Cell.EMPTY }
                MemberRow(member, cells, cells.sumOf { it.type?.paidMinutes ?: 0 })
            }
            // Spalten der Besetzung: aktive Arbeitsschichten und alle, die im Zeitraum vorkommen.
            val used = rows.flatMap { row -> row.cells.mapNotNull { it.type } }.toSet()
            val coverageTypes = types.all.filter { it.countsForCoverage && (!it.archived || it in used) }
            val targets = state.targets
            val coverage = days.indices.map { i ->
                val weekday = days[i].date.dayOfWeek.value - 1
                val dayTargets = coverageTypes.mapNotNull { type ->
                    targets[type.id]?.get(weekday)?.takeIf { it > 0 }?.let { type.id to it }
                }.toMap()
                DayCoverage(coverageTypes.map { type -> type to rows.count { it.cells[i].type?.id == type.id } }, dayTargets)
            }
            return PlanGrid(days, rows, coverage, types)
        }

        internal fun cell(state: PlanState, types: ShiftTypeSet, memberId: String, date: LocalDate, restMinutes: Int): Cell {
            val typeId = state.shift(memberId, date)
            val wish = state.wish(memberId, date)
            val note = state.memberNote(memberId, date)
            if (typeId == null && wish == null && note == null) return Cell.EMPTY
            val rest = RestRules.issueBefore(state, types, memberId, date, typeId, restMinutes)
            val offered = typeId != null && state.offer(memberId, date)?.typeId == typeId
            return Cell(typeId, types[typeId], wish, note, rest, offered)
        }
    }
}
