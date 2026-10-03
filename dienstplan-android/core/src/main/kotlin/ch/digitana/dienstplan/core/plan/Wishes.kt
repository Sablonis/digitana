package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.WishStatus

/**
 * Wünsche trägt jede Person selbst ein – gleichberechtigt und auch, wenn der Plan gesperrt
 * ist. Hat eine Person ein eigenes Gerät im Team („Das bin ich“), ändern ihre Wünsche nur
 * dieses Gerät und Admins. Personen ohne eigenes Gerät kann jedes Mitglied vertreten.
 *
 * Die Regel gilt in der Oberfläche und beim lokalen Schreiben, nicht beim Empfang: Eine
 * veränderte App könnte sie umgehen (siehe SICHERHEIT.md).
 */
object WishRights {
    /** [owners]: Geräte-ID → Personen-ID (siehe `PlanState.deviceOwners`). */
    fun mayEdit(owners: Map<String, String>, memberId: String, device: String?, isAdmin: Boolean): Boolean {
        if (isAdmin) return true
        if (device != null && owners[device] == memberId) return true
        return owners.values.none { it == memberId }
    }
}

/** Wünsche einer Person in einem Zeitraum und wie viele davon der Plan erfüllt. */
data class WishTally(val member: Member, val total: Int, val fulfilled: Int, val unmet: Int) {
    /** Noch nicht eingeplant. */
    val open: Int get() = total - fulfilled - unmet

    companion object {
        /** Pro Person mit mindestens einem Wunsch, in der Reihenfolge der Zeilen. */
        fun of(rows: List<MemberRow>): List<WishTally> = rows.mapNotNull { row ->
            val statuses = row.cells.mapNotNull { it.wishStatus }
            if (statuses.isEmpty()) {
                null
            } else {
                WishTally(
                    member = row.member,
                    total = statuses.size,
                    fulfilled = statuses.count { it == WishStatus.FULFILLED },
                    unmet = statuses.count { it == WishStatus.UNMET },
                )
            }
        }
    }
}
