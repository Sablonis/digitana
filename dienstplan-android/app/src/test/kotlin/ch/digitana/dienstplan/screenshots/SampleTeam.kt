package ch.digitana.dienstplan.screenshots

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.plan.WeekModel
import java.time.LocalDate

/**
 * Beispielteam für Screenshots und Barrierefreiheitstests: vier Personen in der Weihnachtswoche
 * 2026 im Kanton Zürich, mit Wünschen, Notizen, Soll-Besetzung und einer zu kurzen Ruhezeit.
 */
internal object SampleTeam {
    const val ANNA = "a1a1a1a1a1a1a1a1"
    const val BEAT = "b2b2b2b2b2b2b2b2"
    const val CARLA = "c3c3c3c3c3c3c3c3"
    const val DARIO = "d4d4d4d4d4d4d4d4"
    private const val DEVICE = "0123456789abcdef"

    val TODAY: LocalDate = LocalDate.of(2026, 12, 22)
    val WEEK: WeekId = WeekId.of(TODAY)

    val plan: PlanState by lazy { build() }

    fun week(state: PlanState = plan): WeekModel = WeekModel.build(state, WEEK, TODAY)

    private fun build(): PlanState {
        var state = PlanState.EMPTY
        var time = 1_000L
        fun put(key: String, value: String) {
            time += 1
            state = state.withEntry(key, Entry(value, time, DEVICE))
        }
        put(PlanKeys.member(ANNA), "Anna Meier")
        put(PlanKeys.member(BEAT), "Beat Keller")
        put(PlanKeys.member(CARLA), "Carla Rossi")
        put(PlanKeys.member(DARIO), "Dario Huber")
        put(PlanKeys.setting(PlanRules.CANTON), "ZH")
        put(PlanKeys.target("F"), "2,2,2,2,2,1,1")
        put(PlanKeys.target("S"), "1,1,1,1,1,1,1")
        put(PlanKeys.pensum(ANNA), "80")
        put(PlanKeys.pensum(BEAT), "100")
        val monday = WEEK.monday
        val shifts = mapOf(
            ANNA to "F F S X X F F",
            BEAT to "S S F F U U U",
            CARLA to "N N X X S S X",
            // Nacht am Mittwoch, Früh am Donnerstag: zu wenig Ruhe.
            DARIO to "X F N F X X S",
        )
        for ((id, line) in shifts) {
            line.split(" ").forEachIndexed { index, code -> put(PlanKeys.shift(id, monday.plusDays(index.toLong())), code) }
        }
        put(PlanKeys.wish(BEAT, monday.plusDays(3)), Wish.DAY_OFF.code)
        put(PlanKeys.wish(CARLA, monday.plusDays(5)), Wish.shift("S").code)
        put(PlanKeys.memberNote(ANNA, monday.plusDays(1)), "Weiterbildung bis 12 Uhr")
        put(PlanKeys.dayNote(monday.plusDays(3)), "Weihnachtsessen")
        return state
    }
}
