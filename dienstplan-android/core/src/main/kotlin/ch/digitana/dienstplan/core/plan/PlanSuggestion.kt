package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.WishKind
import java.time.DayOfWeek
import java.time.LocalDate

/** Ein vorgeschlagener Dienst. */
data class SuggestedShift(val memberId: String, val date: LocalDate, val type: ShiftType)

/** Vorschlag für einen Zeitraum: neue Dienste und was danach noch fehlt. */
data class Suggestion(val shifts: List<SuggestedShift>, val gaps: List<OpenShift>) {
    val isEmpty: Boolean get() = shifts.isEmpty()

    /** Als Änderungen für das Repository. */
    fun changes(): List<Pair<String, String>> = shifts.map { PlanKeys.shift(it.memberId, it.date) to it.type.id }
}

/**
 * Plan-Vorschlag: füllt offene Dienste (nach Soll-Besetzung) mit Personen, die an dem Tag
 * frei sind. Vorhandene Einträge bleiben unverändert. Der Vorschlag ist nachvollziehbar und
 * immer gleich (kein Zufall); er beachtet:
 *
 * - Wünsche: Wunschfrei, Ferienwunsch und „nicht verfügbar“ schliessen aus, Wunscharbeitstage
 *   und Wunschschichten werden bevorzugt.
 * - Ruhezeit: kein Dienst, der davor oder danach zu wenig Ruhe liesse.
 * - Höchstens [MAX_CONSECUTIVE_DAYS] Arbeitstage am Stück.
 * - Fairness: Wer im Zeitraum (gemessen am Pensum) weniger Stunden hat, kommt zuerst; Wochenend-
 *   und Nachtdienste werden gleichmässig verteilt.
 *
 * [editable] sagt, welche Tage dieses Gerät ändern darf (Sperre).
 */
object PlanSuggestion {
    const val MAX_CONSECUTIVE_DAYS = 6

    fun suggest(state: PlanState, dates: List<LocalDate>, device: String, editable: (LocalDate) -> Boolean = { true }): Suggestion {
        val types = state.shiftTypes
        val members = state.members()
        val validDates = dates.filter(PlanKeys::isValidDate).sorted()
        if (members.isEmpty() || validDates.isEmpty() || state.targets.isEmpty()) {
            return Suggestion(emptyList(), validDates.firstOrNull()?.let { ShiftTrades.openShifts(state, it, validDates.last()) }.orEmpty())
        }
        val restMinutes = state.restMinutes
        val holidays = SwissHolidays.between(state.canton, validDates.first(), validDates.last())

        // Zähler pro Person über den ganzen Zeitraum (vorhandene Dienste und Vorschläge).
        val minutes = HashMap<String, Int>()
        val weekends = HashMap<String, Int>()
        val nights = HashMap<String, Int>()
        for (member in members) {
            for (date in validDates) {
                val type = types[state.shift(member.id, date)] ?: continue
                if (type.kind != ShiftKind.WORK) continue
                minutes.merge(member.id, type.paidMinutes, Int::plus)
                if (isWeekend(date)) weekends.merge(member.id, 1, Int::plus)
                if (NightWork.isNight(type)) nights.merge(member.id, 1, Int::plus)
            }
        }
        // Anteil am Pensum: 100 % zählt voll; ohne Angabe wie 100 %.
        val share = members.associate { it.id to (state.pensum(it.id) ?: 100) / 100.0 }

        var working = state
        var timestamp = state.maxTimestamp
        val suggested = ArrayList<SuggestedShift>()
        for (date in validDates) {
            if (!editable(date)) continue
            val weekday = date.dayOfWeek.value - 1
            val open = types.all
                .filter { it.countsForCoverage && !it.archived }
                .mapNotNull { type ->
                    val target = state.targets[type.id]?.get(weekday) ?: return@mapNotNull null
                    val count = members.count { working.shift(it.id, date) == type.id }
                    if (count < target) type to target - count else null
                }
                // Früheste Schicht zuerst, damit Ruhezeiten danach richtig geprüft werden.
                .sortedWith(compareBy(nullsLast()) { it.first.start })
            for ((type, missing) in open) {
                repeat(missing) {
                    val candidate = members
                        .filter { eligible(working, it.id, date, type, restMinutes) }
                        .maxWithOrNull(
                            compareBy<Member> { member ->
                                score(working, member.id, date, type, minutes, weekends, nights, share, holidays)
                            }.thenByDescending { tieBreak(it.id, date) },
                        ) ?: return@repeat
                    timestamp += 1
                    working = working.withEntry(PlanKeys.shift(candidate.id, date), Entry(type.id, timestamp, device))
                    suggested += SuggestedShift(candidate.id, date, type)
                    minutes.merge(candidate.id, type.paidMinutes, Int::plus)
                    if (isWeekend(date)) weekends.merge(candidate.id, 1, Int::plus)
                    if (NightWork.isNight(type)) nights.merge(candidate.id, 1, Int::plus)
                }
            }
        }
        val dateSet = validDates.toSet()
        val gaps = ShiftTrades.openShifts(working, validDates.first(), validDates.last()).filter { it.date in dateSet && editable(it.date) }
        return Suggestion(suggested, gaps)
    }

    private fun eligible(state: PlanState, memberId: String, date: LocalDate, type: ShiftType, restMinutes: Int): Boolean {
        if (state.shift(memberId, date) != null) return false
        val wish = state.wish(memberId, date)
        if (wish != null && wish.kind != WishKind.WORK) return false
        if (RestRules.issueFor(state, state.shiftTypes, memberId, date, type.id, restMinutes) != null) return false
        return consecutiveDays(state, memberId, date) < MAX_CONSECUTIVE_DAYS
    }

    /** Arbeitstage am Stück, wenn an [date] ein Dienst dazukäme (ohne diesen Tag gezählt). */
    private fun consecutiveDays(state: PlanState, memberId: String, date: LocalDate): Int {
        fun works(day: LocalDate) = PlanKeys.isValidDate(day) && state.shiftTypes[state.shift(memberId, day)]?.kind == ShiftKind.WORK
        var before = 0
        while (before < MAX_CONSECUTIVE_DAYS && works(date.minusDays(before + 1L))) before++
        var after = 0
        while (after < MAX_CONSECUTIVE_DAYS && works(date.plusDays(after + 1L))) after++
        return before + after
    }

    private fun score(
        state: PlanState,
        memberId: String,
        date: LocalDate,
        type: ShiftType,
        minutes: Map<String, Int>,
        weekends: Map<String, Int>,
        nights: Map<String, Int>,
        share: Map<String, Double>,
        holidays: Map<LocalDate, Holiday>,
    ): Double {
        var score = 0.0
        val wish = state.wish(memberId, date)
        // Wunschschicht: genau diese bevorzugen und die Person für sie freihalten.
        if (wish?.kind == WishKind.WORK) score += if (wish.typeId == type.id) 100.0 else if (wish.typeId == null) 60.0 else -30.0
        // Weniger Stunden im Verhältnis zum Pensum = höhere Priorität (eine Stunde ≈ 1 Punkt).
        score -= (minutes[memberId] ?: 0) / 60.0 / (share[memberId] ?: 1.0)
        if (isWeekend(date) || date in holidays) score -= 15.0 * (weekends[memberId] ?: 0)
        if (NightWork.isNight(type)) score -= 15.0 * (nights[memberId] ?: 0)
        // Gleiche Schicht wie am Vortag: ruhigerer Rhythmus.
        if (state.shift(memberId, date.minusDays(1)) == type.id) score += 5.0
        return score
    }

    private fun isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /** Gleichstand ohne Zufall auflösen, aber nicht immer zugunsten derselben Person. */
    private fun tieBreak(memberId: String, date: LocalDate): Int = (memberId + date).hashCode()
}
