package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.OfferEntry
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftOffer
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.SwapEntry
import ch.digitana.dienstplan.core.crdt.SwapRequest
import ch.digitana.dienstplan.core.crdt.SwapStatus
import java.time.LocalDate

/** Ein offener Dienst: An [date] fehlen [missing] Personen in der Schicht [type] (nach Soll-Besetzung). */
data class OpenShift(val date: LocalDate, val type: ShiftType, val missing: Int)

/** Warum ein Dienst nicht übernommen oder getauscht werden kann. */
enum class TradeProblem {
    /** Der Plan hat sich seit dem Angebot oder Vorschlag geändert. */
    STALE,

    /** Die eigene Person. */
    SELF,

    /** Die Person hat an dem Tag schon einen Dienst oder ist abwesend. */
    BUSY,
}

/** Ein möglicher Tausch: Dienst [typeId] von [memberId] an [date]; ohne Art übernimmt die Person nur. */
data class SwapCandidate(val memberId: String, val date: LocalDate, val typeId: String?)

/**
 * Offene Dienste übernehmen, Dienste abgeben und tauschen. Die Regeln gelten auf jedem Gerät
 * gleich; was sie am Plan ändern, wird als eine Sammeländerung geschrieben. Ist ein Tag
 * gesperrt, schreibt nur ein Admin die Schichten – dann bleibt die Übernahme angemeldet bzw.
 * der Tausch angenommen, bis ein Admin ihn ausführt.
 */
object ShiftTrades {

    /** Frei an dem Tag: kein Eintrag oder eine freie Schichtart (nicht Arbeit, nicht abwesend). */
    fun isFree(state: PlanState, memberId: String, date: LocalDate): Boolean {
        val typeId = state.shift(memberId, date) ?: return true
        val type = state.shiftTypes[typeId] ?: return false
        return type.kind == ShiftKind.OFF
    }

    /** Offene Dienste von [from] bis [to] (einschliesslich), nach Datum und Schichtart. */
    fun openShifts(state: PlanState, from: LocalDate, to: LocalDate): List<OpenShift> {
        val targets = state.targets
        if (targets.isEmpty() || to.isBefore(from)) return emptyList()
        val types = state.shiftTypes
        val members = state.members()
        val result = ArrayList<OpenShift>()
        var date = from
        while (!date.isAfter(to)) {
            if (PlanKeys.isValidDate(date)) {
                val weekday = date.dayOfWeek.value - 1
                for (type in types.all) {
                    if (!type.countsForCoverage) continue
                    val target = targets[type.id]?.get(weekday) ?: continue
                    val count = members.count { state.shift(it.id, date) == type.id }
                    if (count < target) result += OpenShift(date, type, target - count)
                }
            }
            date = date.plusDays(1)
        }
        return result
    }

    /** Kann [memberId] an [date] einen offenen Dienst übernehmen? null = ja. */
    fun claimProblem(state: PlanState, memberId: String, date: LocalDate): TradeProblem? =
        if (isFree(state, memberId, date)) null else TradeProblem.BUSY

    /** Gilt ein Angebot noch, d. h. hat die Person den angebotenen Dienst noch? */
    fun isOfferValid(state: PlanState, offer: OfferEntry): Boolean = state.shift(offer.memberId, offer.date) == offer.offer.typeId

    /** Kann [takerId] das Angebot übernehmen? null = ja. */
    fun takeProblem(state: PlanState, offer: OfferEntry, takerId: String): TradeProblem? = when {
        takerId == offer.memberId -> TradeProblem.SELF
        !isOfferValid(state, offer) -> TradeProblem.STALE
        !isFree(state, takerId, offer.date) -> TradeProblem.BUSY
        else -> null
    }

    /** Übergabe ausführen: [takerId] übernimmt den Dienst, das Angebot ist erledigt. */
    fun takeChanges(offer: OfferEntry, takerId: String): List<Pair<String, String>> = listOf(
        PlanKeys.shift(takerId, offer.date) to offer.offer.typeId,
        PlanKeys.shift(offer.memberId, offer.date) to "",
        offer.key to "",
    )

    /** Übernahme anmelden, wenn der Tag gesperrt ist: Ein Admin bestätigt sie. */
    fun claimChange(offer: OfferEntry, takerId: String): Pair<String, String> =
        offer.key to ShiftOffer(offer.offer.typeId, takerId).encode()

    /** Gilt ein Tauschvorschlag noch (beide Dienste wie beim Vorschlag) und lässt er sich ausführen? null = ja. */
    fun swapProblem(state: PlanState, entry: SwapEntry): TradeProblem? {
        val swap = entry.swap
        val request = entry.request
        if (state.shift(swap.fromMember, swap.fromDate) != request.fromType) return TradeProblem.STALE
        val toType = request.toType
        if (toType != null && state.shift(swap.toMember, swap.toDate) != toType) return TradeProblem.STALE
        if (swap.fromDate == swap.toDate) {
            // Am selben Tag: Ohne Gegendienst muss B frei sein, sonst tauschen beide ihre Schicht.
            return if (toType == null && !isFree(state, swap.toMember, swap.fromDate)) TradeProblem.BUSY else null
        }
        if (!isFree(state, swap.toMember, swap.fromDate)) return TradeProblem.BUSY
        if (toType != null && !isFree(state, swap.fromMember, swap.toDate)) return TradeProblem.BUSY
        return null
    }

    /** Tausch ausführen: Schichten tauschen, Vorschlag als erledigt markieren. */
    fun swapChanges(entry: SwapEntry): List<Pair<String, String>> {
        val swap = entry.swap
        val request = entry.request
        val changes = ArrayList<Pair<String, String>>()
        if (swap.fromDate == swap.toDate) {
            changes += PlanKeys.shift(swap.fromMember, swap.fromDate) to (request.toType ?: "")
            changes += PlanKeys.shift(swap.toMember, swap.fromDate) to request.fromType
        } else {
            changes += PlanKeys.shift(swap.fromMember, swap.fromDate) to ""
            changes += PlanKeys.shift(swap.toMember, swap.fromDate) to request.fromType
            request.toType?.let { toType ->
                changes += PlanKeys.shift(swap.toMember, swap.toDate) to ""
                changes += PlanKeys.shift(swap.fromMember, swap.toDate) to toType
            }
        }
        changes += entry.key to request.with(SwapStatus.DONE).encode()
        return changes
    }

    /** Neuer Vorschlag: A gibt den Dienst an [fromDate] ab und übernimmt ggf. den von B an [toDate]. */
    fun proposal(state: PlanState, fromMember: String, fromDate: LocalDate, toMember: String, toDate: LocalDate): Pair<String, String>? {
        val fromType = state.shift(fromMember, fromDate)?.takeIf { isWork(state, it) } ?: return null
        val toType = if (toDate == fromDate) {
            state.shift(toMember, toDate)?.takeIf { isWork(state, it) }
        } else {
            state.shift(toMember, toDate)?.takeIf { isWork(state, it) } ?: return null
        }
        return PlanKeys.swap(fromMember, fromDate, toMember, toDate) to SwapRequest(SwapStatus.PROPOSED, fromType, toType).encode()
    }

    /**
     * Mögliche Tauschpartner für den Dienst von [memberId] an [date]: Arbeitsdienste anderer
     * Personen im Umkreis von [days] Tagen, an deren Tag [memberId] frei ist und die selbst an
     * [date] frei sind; am selben Tag ein anderer Dienst (die beiden tauschen die Schicht). Dazu
     * Personen, die an [date] frei sind und den Dienst nur übernehmen würden.
     */
    fun candidates(state: PlanState, memberId: String, date: LocalDate, days: Long = 14): List<SwapCandidate> {
        val ownType = state.shift(memberId, date)?.takeIf { isWork(state, it) } ?: return emptyList()
        val result = ArrayList<SwapCandidate>()
        for (other in state.members()) {
            if (other.id == memberId) continue
            val otherSame = state.shift(other.id, date)
            if (otherSame != null && otherSame != ownType && isWork(state, otherSame)) result += SwapCandidate(other.id, date, otherSame)
            if (!isFree(state, other.id, date)) continue
            result += SwapCandidate(other.id, date, null)
            for (offset in -days..days) {
                if (offset == 0L) continue
                val day = date.plusDays(offset)
                if (!PlanKeys.isValidDate(day)) continue
                val typeId = state.shift(other.id, day) ?: continue
                if (isWork(state, typeId) && isFree(state, memberId, day)) result += SwapCandidate(other.id, day, typeId)
            }
        }
        return result.sortedWith(compareBy<SwapCandidate> { it.typeId == null }.thenBy { it.date }.thenBy { it.memberId })
    }

    private fun isWork(state: PlanState, typeId: String): Boolean = state.shiftTypes[typeId]?.kind == ShiftKind.WORK
}
