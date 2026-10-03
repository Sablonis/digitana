package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Canton
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** Ein Feiertag an einem bestimmten Datum. */
data class Holiday(val date: LocalDate, val kind: SwissHoliday) {
    val name: String get() = kind.label
}

/** Feiertage, die in mindestens einem Kanton gelten, mit ihrer Berechnung. */
enum class SwissHoliday(val label: String) {
    NEW_YEAR("Neujahr"),
    BERCHTOLD("Berchtoldstag"),
    EPIPHANY("Dreikönigstag"),
    REPUBLIC_NE("Jahrestag der Republik"),
    JOSEPH("Josefstag"),
    NAEFELS("Näfelser Fahrt"),
    GOOD_FRIDAY("Karfreitag"),
    EASTER("Ostern"),
    EASTER_MONDAY("Ostermontag"),
    LABOUR("Tag der Arbeit"),
    ASCENSION("Auffahrt"),
    WHIT_SUNDAY("Pfingsten"),
    WHIT_MONDAY("Pfingstmontag"),
    CORPUS_CHRISTI("Fronleichnam"),
    INDEPENDENCE_JU("Fest der Unabhängigkeit"),
    PETER_PAUL("Peter und Paul"),
    NATIONAL("Bundesfeiertag"),
    ASSUMPTION("Mariä Himmelfahrt"),
    JEUNE_GENEVOIS("Genfer Bettag"),
    FAST("Bettag"),
    FAST_MONDAY("Bettagsmontag"),
    MAURITIUS("Mauritiustag"),
    BROTHER_KLAUS("Bruder Klaus"),
    ALL_SAINTS("Allerheiligen"),
    IMMACULATE("Mariä Empfängnis"),
    CHRISTMAS("Weihnachten"),
    STEPHEN("Stephanstag"),
    RESTORATION_GE("Wiederherstellung der Republik");

    /** Datum im Jahr [year]. */
    fun dateIn(year: Int): LocalDate {
        val easter = SwissHolidays.easter(year)
        return when (this) {
            NEW_YEAR -> LocalDate.of(year, 1, 1)
            BERCHTOLD -> LocalDate.of(year, 1, 2)
            EPIPHANY -> LocalDate.of(year, 1, 6)
            REPUBLIC_NE -> LocalDate.of(year, 3, 1)
            JOSEPH -> LocalDate.of(year, 3, 19)
            // Erster Donnerstag im April; fällt er auf den Gründonnerstag, eine Woche später.
            NAEFELS -> LocalDate.of(year, 4, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY))
                .let { if (it == easter.minusDays(3)) it.plusWeeks(1) else it }
            GOOD_FRIDAY -> easter.minusDays(2)
            EASTER -> easter
            EASTER_MONDAY -> easter.plusDays(1)
            LABOUR -> LocalDate.of(year, 5, 1)
            ASCENSION -> easter.plusDays(39)
            WHIT_SUNDAY -> easter.plusDays(49)
            WHIT_MONDAY -> easter.plusDays(50)
            CORPUS_CHRISTI -> easter.plusDays(60)
            INDEPENDENCE_JU -> LocalDate.of(year, 6, 23)
            PETER_PAUL -> LocalDate.of(year, 6, 29)
            NATIONAL -> LocalDate.of(year, 8, 1)
            ASSUMPTION -> LocalDate.of(year, 8, 15)
            // Donnerstag nach dem ersten Sonntag im September.
            JEUNE_GENEVOIS -> firstSundayOfSeptember(year).plusDays(4)
            // Eidgenössischer Dank-, Buss- und Bettag: dritter Sonntag im September.
            FAST -> firstSundayOfSeptember(year).plusWeeks(2)
            FAST_MONDAY -> firstSundayOfSeptember(year).plusWeeks(2).plusDays(1)
            MAURITIUS -> LocalDate.of(year, 9, 22)
            BROTHER_KLAUS -> LocalDate.of(year, 9, 25)
            ALL_SAINTS -> LocalDate.of(year, 11, 1)
            IMMACULATE -> LocalDate.of(year, 12, 8)
            CHRISTMAS -> LocalDate.of(year, 12, 25)
            STEPHEN -> LocalDate.of(year, 12, 26)
            RESTORATION_GE -> LocalDate.of(year, 12, 31)
        }
    }

    private companion object {
        fun firstSundayOfSeptember(year: Int): LocalDate =
            LocalDate.of(year, Month.SEPTEMBER, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    }
}

/**
 * Feiertage in der Schweiz nach Kanton, offline berechnet: feste Daten und solche, die von
 * Ostern abhängen. Enthalten sind die gesetzlichen Feiertage und allgemein üblichen freien
 * Tage (z. B. Berchtoldstag) des Kantons; nicht enthalten sind halbe Tage, Feiertage, die nur
 * in einzelnen Gemeinden gelten, und Ruhetage der Verwaltung. Grundlage: Liste der kantonalen
 * Feiertage des Bundesamts für Justiz und ch.ch (Stand 2026).
 */
object SwissHolidays {

    private val BASE: Set<SwissHoliday> = setOf(
        SwissHoliday.NEW_YEAR, SwissHoliday.GOOD_FRIDAY, SwissHoliday.EASTER, SwissHoliday.EASTER_MONDAY,
        SwissHoliday.ASCENSION, SwissHoliday.WHIT_SUNDAY, SwissHoliday.WHIT_MONDAY, SwissHoliday.NATIONAL,
        SwissHoliday.FAST, SwissHoliday.CHRISTMAS, SwissHoliday.STEPHEN,
    )

    /** Katholische Feiertage, die viele Kantone gemeinsam haben. */
    private val CATHOLIC: Set<SwissHoliday> = setOf(
        SwissHoliday.CORPUS_CHRISTI, SwissHoliday.ASSUMPTION, SwissHoliday.ALL_SAINTS, SwissHoliday.IMMACULATE,
    )

    private class Rules(
        val add: Set<SwissHoliday> = emptySet(),
        val remove: Set<SwissHoliday> = emptySet(),
        /** Gilt nur an bestimmten Wochentagen (z. B. Stephanstag in Uri nicht am Montag oder Freitag). */
        val onlyIf: Map<SwissHoliday, (DayOfWeek) -> Boolean> = emptyMap(),
    )

    private val RULES: Map<Canton, Rules> = mapOf(
        Canton.AG to Rules(add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.LABOUR) + CATHOLIC),
        Canton.AI to Rules(
            add = setOf(SwissHoliday.CORPUS_CHRISTI, SwissHoliday.ASSUMPTION, SwissHoliday.MAURITIUS, SwissHoliday.ALL_SAINTS),
            onlyIf = mapOf(SwissHoliday.STEPHEN to { day -> day != DayOfWeek.TUESDAY && day != DayOfWeek.SATURDAY }),
        ),
        Canton.AR to Rules(onlyIf = mapOf(SwissHoliday.STEPHEN to { day -> day != DayOfWeek.MONDAY })),
        Canton.BE to Rules(add = setOf(SwissHoliday.BERCHTOLD)),
        Canton.BL to Rules(add = setOf(SwissHoliday.LABOUR)),
        Canton.BS to Rules(add = setOf(SwissHoliday.LABOUR)),
        Canton.FR to Rules(add = setOf(SwissHoliday.BERCHTOLD) + CATHOLIC),
        Canton.GE to Rules(
            add = setOf(SwissHoliday.JEUNE_GENEVOIS, SwissHoliday.RESTORATION_GE),
            remove = setOf(SwissHoliday.FAST, SwissHoliday.STEPHEN),
        ),
        Canton.GL to Rules(
            add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.NAEFELS, SwissHoliday.ALL_SAINTS),
            remove = setOf(SwissHoliday.FAST),
        ),
        Canton.GR to Rules(),
        Canton.JU to Rules(
            add = setOf(
                SwissHoliday.BERCHTOLD, SwissHoliday.LABOUR, SwissHoliday.CORPUS_CHRISTI, SwissHoliday.INDEPENDENCE_JU,
                SwissHoliday.ASSUMPTION, SwissHoliday.ALL_SAINTS,
            ),
            remove = setOf(SwissHoliday.STEPHEN),
        ),
        Canton.LU to Rules(add = setOf(SwissHoliday.BERCHTOLD) + CATHOLIC),
        Canton.NE to Rules(
            add = setOf(
                SwissHoliday.BERCHTOLD, SwissHoliday.REPUBLIC_NE, SwissHoliday.LABOUR, SwissHoliday.CORPUS_CHRISTI,
                SwissHoliday.FAST_MONDAY,
            ),
            remove = setOf(SwissHoliday.WHIT_MONDAY),
            // Berchtoldstag und Stephanstag nur, wenn Neujahr bzw. Weihnachten auf einen Sonntag fällt.
            onlyIf = mapOf(
                SwissHoliday.BERCHTOLD to { day -> day == DayOfWeek.MONDAY },
                SwissHoliday.STEPHEN to { day -> day == DayOfWeek.MONDAY },
            ),
        ),
        Canton.NW to Rules(add = setOf(SwissHoliday.JOSEPH) + CATHOLIC),
        Canton.OW to Rules(add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.BROTHER_KLAUS) + CATHOLIC),
        Canton.SG to Rules(add = setOf(SwissHoliday.ALL_SAINTS)),
        Canton.SH to Rules(add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.LABOUR)),
        Canton.SO to Rules(add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.CORPUS_CHRISTI, SwissHoliday.ASSUMPTION, SwissHoliday.ALL_SAINTS)),
        Canton.SZ to Rules(add = setOf(SwissHoliday.EPIPHANY, SwissHoliday.JOSEPH) + CATHOLIC),
        Canton.TG to Rules(add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.LABOUR)),
        Canton.TI to Rules(
            add = setOf(SwissHoliday.EPIPHANY, SwissHoliday.JOSEPH, SwissHoliday.LABOUR, SwissHoliday.PETER_PAUL) + CATHOLIC,
            remove = setOf(SwissHoliday.GOOD_FRIDAY),
        ),
        Canton.UR to Rules(
            add = setOf(SwissHoliday.EPIPHANY, SwissHoliday.JOSEPH) + CATHOLIC,
            onlyIf = mapOf(SwissHoliday.STEPHEN to { day -> day != DayOfWeek.MONDAY && day != DayOfWeek.FRIDAY }),
        ),
        Canton.VD to Rules(add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.FAST_MONDAY), remove = setOf(SwissHoliday.STEPHEN)),
        Canton.VS to Rules(add = setOf(SwissHoliday.JOSEPH) + CATHOLIC, remove = setOf(SwissHoliday.GOOD_FRIDAY)),
        Canton.ZG to Rules(add = setOf(SwissHoliday.BERCHTOLD) + CATHOLIC),
        Canton.ZH to Rules(add = setOf(SwissHoliday.BERCHTOLD, SwissHoliday.LABOUR)),
    )

    /** Feiertage des Kantons im Jahr [year], nach Datum. */
    fun of(canton: Canton, year: Int): List<Holiday> {
        val rules = RULES.getValue(canton)
        return (BASE + rules.add - rules.remove)
            .map { Holiday(it.dateIn(year), it) }
            .filter { holiday -> rules.onlyIf[holiday.kind]?.invoke(holiday.date.dayOfWeek) ?: true }
            .sortedBy { it.date }
    }

    /** Feiertage von [from] bis [to] (einschliesslich); leer ohne Kanton. */
    fun between(canton: Canton?, from: LocalDate, to: LocalDate): Map<LocalDate, Holiday> {
        if (canton == null || to.isBefore(from)) return emptyMap()
        val result = LinkedHashMap<LocalDate, Holiday>()
        for (year in from.year..to.year) {
            for (holiday in of(canton, year)) {
                if (!holiday.date.isBefore(from) && !holiday.date.isAfter(to)) result.putIfAbsent(holiday.date, holiday)
            }
        }
        return result
    }

    /** Ostersonntag im gregorianischen Kalender (anonymer Algorithmus nach Meeus/Jones/Butcher). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
