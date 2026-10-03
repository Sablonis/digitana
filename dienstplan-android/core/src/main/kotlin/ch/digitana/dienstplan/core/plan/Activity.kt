package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.OfferEntry
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.SwapEntry
import ch.digitana.dienstplan.core.crdt.SwapStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.PriorityQueue

/** Eine Änderung im Plan für die Liste „Aktivität“: der aktuelle Eintrag eines Schlüssels. */
data class ActivityItem(val key: String, val parsed: PlanKey, val entry: Entry) {
    /** Leerer Wert: gelöscht bzw. entfernt. */
    val isRemoval: Boolean get() = entry.value.isEmpty()
}

/**
 * „Aktivität“: die zuletzt geänderten Einträge, neueste zuerst. Weil der Plan pro Feld nur den
 * letzten Wert kennt (LWW), ist das kein vollständiger Verlauf: Wer ein Feld zweimal ändert,
 * erscheint nur mit der letzten Änderung. Gerätenamen und Zuordnungen fehlen bewusst.
 */
object Activity {
    const val DEFAULT_LIMIT = 200

    fun recent(state: PlanState, limit: Int = DEFAULT_LIMIT): List<ActivityItem> {
        if (limit <= 0) return emptyList()
        // Kleinster Zeitstempel oben: so bleiben die [limit] neuesten übrig.
        val heap = PriorityQueue<ActivityItem>(limit + 1, compareBy<ActivityItem> { it.entry })
        for (map in state.buckets.values) {
            for ((key, entry) in map.entries) {
                if (key.startsWith("d|") || key.startsWith("u|")) continue
                if (heap.size >= limit && entry <= heap.peek().entry) continue
                val parsed = PlanKeys.parse(key) ?: continue
                heap.add(ActivityItem(key, parsed, entry))
                if (heap.size > limit) heap.poll()
            }
        }
        return heap.sortedByDescending { it.entry }
    }
}

/** Nach welcher Regel an eigene Dienste erinnert wird. */
enum class ReminderMode {
    OFF,

    /** Am Vorabend um 19 Uhr. */
    EVENING_BEFORE,

    /** Eine Stunde vor Dienstbeginn (nur Schichten mit Zeiten). */
    ONE_HOUR,

    /** Zwei Stunden vor Dienstbeginn (nur Schichten mit Zeiten). */
    TWO_HOURS,
}

/** Erinnerung an einen eigenen Dienst: wann und an welchen. */
data class ShiftReminder(val at: LocalDateTime, val date: LocalDate, val typeId: String)

/** Erinnerung an eine Wunschfrist. */
data class DeadlineReminder(val at: LocalDateTime, val month: YearMonth, val deadline: LocalDate)

/** Wann die App lokal erinnert (ohne Server, rein auf dem Gerät). */
object Reminders {
    val EVENING: LocalTime = LocalTime.of(19, 0)
    val DEADLINE_TIME: LocalTime = LocalTime.of(9, 0)
    const val DEADLINE_DAYS_BEFORE = 2L

    /** Nächste Erinnerung an einen Arbeitsdienst von [memberId] nach [now]; null = keine in [horizonDays] Tagen. */
    fun nextShift(state: PlanState, memberId: String, now: LocalDateTime, mode: ReminderMode, horizonDays: Int = 14): ShiftReminder? {
        if (mode == ReminderMode.OFF) return null
        val types = state.shiftTypes
        val today = now.toLocalDate()
        for (offset in 0..horizonDays) {
            val date = today.plusDays(offset.toLong())
            if (!PlanKeys.isValidDate(date)) break
            val typeId = state.shift(memberId, date) ?: continue
            val type = types[typeId] ?: continue
            if (!type.countsForCoverage) continue
            val start = type.start
            val at = when (mode) {
                ReminderMode.EVENING_BEFORE -> date.minusDays(1).atTime(EVENING)
                ReminderMode.ONE_HOUR -> start?.let { date.atTime(it).minusHours(1) }
                ReminderMode.TWO_HOURS -> start?.let { date.atTime(it).minusHours(2) }
                ReminderMode.OFF -> null
            } ?: continue
            if (at.isAfter(now)) return ShiftReminder(at, date, typeId)
        }
        return null
    }

    /**
     * Nächste Erinnerung an eine Wunschfrist: [DEADLINE_DAYS_BEFORE] Tage vorher um 9 Uhr – nur,
     * wenn [memberId] für den Monat noch keinen Wunsch eingetragen hat.
     */
    fun nextDeadline(state: PlanState, memberId: String?, now: LocalDateTime): DeadlineReminder? =
        state.wishDeadlines.entries
            .sortedBy { it.value }
            .asSequence()
            .map { (month, deadline) -> DeadlineReminder(deadline.minusDays(DEADLINE_DAYS_BEFORE).atTime(DEADLINE_TIME), month, deadline) }
            .filter { it.at.isAfter(now) }
            .firstOrNull { reminder -> memberId == null || !hasWishes(state, memberId, reminder.month) }

    /** Laufende Wunschfrist für ein Banner: der nächste Monat, dessen Frist heute oder später endet. */
    fun openDeadline(state: PlanState, today: LocalDate): Pair<YearMonth, LocalDate>? =
        state.wishDeadlines.entries
            .filter { (_, deadline) -> !deadline.isBefore(today) }
            .minByOrNull { it.value }
            ?.toPair()

    fun hasWishes(state: PlanState, memberId: String, month: YearMonth): Boolean =
        (1..month.lengthOfMonth()).any { day -> state.wish(memberId, month.atDay(day)) != null }
}

/** Ereignis rund um Abgeben und Tauschen, das eine Benachrichtigung wert ist. */
sealed interface TradeEvent {
    /** Eindeutig pro Stand: Schlüssel und Wert. Ändert sich der Stand, ist es ein neues Ereignis. */
    val id: String

    /** Jemand schlägt mir einen Tausch vor. */
    data class SwapProposed(val swap: SwapEntry) : TradeEvent {
        override val id: String get() = "${swap.key}=${swap.entry.value}"
    }

    /** Mein Vorschlag wurde beantwortet (getauscht, abgelehnt oder wartet auf einen Admin). */
    data class SwapAnswered(val swap: SwapEntry) : TradeEvent {
        override val id: String get() = "${swap.key}=${swap.entry.value}"
    }

    /** Für Admins: angenommener Tausch an einem gesperrten Tag. */
    data class SwapNeedsAdmin(val swap: SwapEntry) : TradeEvent {
        override val id: String get() = "${swap.key}=${swap.entry.value}"
    }

    /** Jemand anderes gibt einen Dienst ab. */
    data class OfferOpen(val offer: OfferEntry) : TradeEvent {
        override val id: String get() = "${offer.key}=${offer.entry.value}"
    }

    /** Für Admins: Übernahme an einem gesperrten Tag angemeldet. */
    data class OfferNeedsAdmin(val offer: OfferEntry) : TradeEvent {
        override val id: String get() = "${offer.key}=${offer.entry.value}"
    }
}

/** Welche Tausch-Ereignisse für dieses Gerät gerade gelten (ab heute). */
object TradeWatch {
    fun events(state: PlanState, me: String?, isAdmin: Boolean, today: LocalDate): List<TradeEvent> {
        val events = ArrayList<TradeEvent>()
        for (swap in state.swaps) {
            if (swap.swap.fromDate.isBefore(today) && swap.swap.toDate.isBefore(today)) continue
            val request = swap.request
            when {
                me != null && swap.swap.toMember == me && request.status == SwapStatus.PROPOSED -> events += TradeEvent.SwapProposed(swap)
                me != null && swap.swap.fromMember == me && request.status != SwapStatus.PROPOSED -> events += TradeEvent.SwapAnswered(swap)
            }
            if (isAdmin && request.status == SwapStatus.ACCEPTED) events += TradeEvent.SwapNeedsAdmin(swap)
        }
        for (offer in state.offers) {
            if (offer.date.isBefore(today) || !ShiftTrades.isOfferValid(state, offer)) continue
            if (offer.offer.claimedBy != null) {
                if (isAdmin) events += TradeEvent.OfferNeedsAdmin(offer)
            } else if (offer.memberId != me) {
                events += TradeEvent.OfferOpen(offer)
            }
        }
        return events
    }
}
