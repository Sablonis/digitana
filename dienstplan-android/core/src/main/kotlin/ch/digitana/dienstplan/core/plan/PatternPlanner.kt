package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import java.time.DayOfWeek
import java.time.LocalDate

/** Berechnet, welche Felder ein Rhythmus ändert. */
object PatternPlanner {
    const val MAX_WEEKS = 52

    /**
     * Änderungen (Schlüssel → ID der Schichtart oder leer) für [weeks] Wochen ab dem Montag [start].
     * Der Rhythmus wiederholt sich, bis der Zeitraum gefüllt ist. Felder ausserhalb 2000–2100 und
     * Personen, die es nicht (mehr) gibt, werden übersprungen.
     */
    fun changes(
        state: PlanState,
        pattern: ShiftPattern,
        memberIds: List<String>,
        start: LocalDate,
        weeks: Int,
        overwrite: Boolean,
    ): List<Pair<String, String>> {
        require(start.dayOfWeek == DayOfWeek.MONDAY) { "Ein Rhythmus beginnt an einem Montag" }
        require(weeks in 1..MAX_WEEKS) { "1 bis $MAX_WEEKS Wochen" }
        val active = state.members().map { it.id }.toSet()
        val result = ArrayList<Pair<String, String>>()
        for (memberId in memberIds.distinct()) {
            if (memberId !in active) continue
            for (offset in 0 until weeks * 7) {
                val date = start.plusDays(offset.toLong())
                if (!PlanKeys.isValidDate(date)) continue
                val typeId = pattern.days[offset % pattern.days.size]
                val current = state.shift(memberId, date)
                if (!overwrite && (current != null || typeId == null)) continue
                result += PlanKeys.shift(memberId, date) to (typeId ?: "")
            }
        }
        return result
    }
}
