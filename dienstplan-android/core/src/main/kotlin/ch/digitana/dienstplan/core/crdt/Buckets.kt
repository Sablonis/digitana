package ch.digitana.dienstplan.core.crdt

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** ISO-8601-Kalenderwoche (Montag bis Sonntag). */
data class WeekId(val year: Int, val week: Int) : Comparable<WeekId> {

    /** Bucket-Name, z. B. `2026-W39`. */
    val bucketName: String get() = String.format(Locale.ROOT, "%04d-W%02d", year, week)

    val monday: LocalDate
        get() = LocalDate.of(year, 1, 4)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(week - 1L)

    val days: List<LocalDate> get() = (0L..6L).map { monday.plusDays(it) }

    val sunday: LocalDate get() = monday.plusDays(6)

    fun contains(date: LocalDate): Boolean = of(date) == this

    fun next(): WeekId = of(monday.plusWeeks(1))

    fun previous(): WeekId = of(monday.minusWeeks(1))

    override fun compareTo(other: WeekId): Int =
        compareValuesBy(this, other, WeekId::year, WeekId::week)

    companion object {
        fun of(date: LocalDate): WeekId =
            WeekId(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

        /** 52 oder 53: Der 28. Dezember liegt immer in der letzten ISO-Woche. */
        fun weeksInYear(year: Int): Int =
            LocalDate.of(year, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    }
}

/**
 * Einträge werden in Buckets gebündelt: `team` für die Mitarbeitenden und je ein
 * Bucket pro ISO-Kalenderwoche für die Schichten. Pro Bucket gibt es genau ein
 * adressierbares Nostr-Event.
 */
object Buckets {
    const val TEAM = "team"

    private val WEEK = Regex("([0-9]{4})-W([0-9]{2})")

    /** Wochenjahre, die Daten von 2000-01-01 bis 2100-12-31 enthalten können. */
    private val WEEK_YEARS = 1999..2100

    fun forKey(key: PlanKey): String = when (key) {
        is PlanKey.Member -> TEAM
        is PlanKey.Shift -> WeekId.of(key.date).bucketName
    }

    fun isValid(name: String): Boolean = name == TEAM || parseWeek(name) != null

    fun parseWeek(name: String): WeekId? {
        val match = WEEK.matchEntire(name) ?: return null
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toInt()
        if (year !in WEEK_YEARS) return null
        if (week < 1 || week > WeekId.weeksInYear(year)) return null
        return WeekId(year, week)
    }
}
