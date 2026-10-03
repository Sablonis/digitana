package ch.digitana.dienstplan.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.OfferEntry
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.SwapEntry
import ch.digitana.dienstplan.core.crdt.SwapStatus
import ch.digitana.dienstplan.core.plan.ShiftTrades
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.plan.CellRef
import ch.digitana.dienstplan.ui.plan.PlanUiState
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import java.time.LocalDate

/** Ein Eintrag in „Tausch und Abgabe“ mit seinen Aktionen. */
private class TradeItem(val key: String, val text: String, val typeId: String?, val actions: List<Pair<String, () -> Unit>>, val primary: Int = 0)

/**
 * „Tausch und Abgabe“ im Reiter „Ich“: Vorschläge an mich, eigene Vorschläge und Angebote,
 * Dienste, die andere abgeben, und – für Admins – was auf eine Bestätigung wartet.
 */
@Composable
internal fun TradeInbox(state: PlanUiState, viewModel: PlanViewModel, modifier: Modifier = Modifier) {
    val me = state.myMemberId ?: return
    val plan = state.plan
    val today = state.today
    val names = remember(plan) { plan.members().associate { it.id to it.name } }
    val unknown = stringResource(R.string.activity_unknown_person)
    val unknownShift = stringResource(R.string.shift_unknown)
    fun name(id: String) = names[id] ?: unknown
    fun day(date: LocalDate) = "${WeekFormat.weekday(date)} ${WeekFormat.shortDate(date)}"
    fun type(id: String?) = id?.let { plan.shiftTypes[it]?.name } ?: unknownShift
    fun upcoming(swap: SwapEntry) = !swap.swap.fromDate.isBefore(today) || !swap.swap.toDate.isBefore(today)
    fun current(offer: OfferEntry) = !offer.date.isBefore(today) && ShiftTrades.isOfferValid(plan, offer)

    val accept = stringResource(R.string.trade_accept)
    val decline = stringResource(R.string.trade_decline)
    val withdraw = stringResource(R.string.trade_withdraw)
    val take = stringResource(R.string.trade_take)
    val approve = stringResource(R.string.trade_approve)
    val reject = stringResource(R.string.trade_reject)
    val items = ArrayList<TradeItem>()

    for (swap in plan.swaps.filter(::upcoming)) {
        val request = swap.request
        val key = swap.swap
        when {
            key.toMember == me && request.status == SwapStatus.PROPOSED -> items += TradeItem(
                swap.key,
                if (request.toType == null) {
                    stringResource(R.string.inbox_swap_give, name(key.fromMember), type(request.fromType), day(key.fromDate))
                } else {
                    stringResource(
                        R.string.inbox_swap_incoming,
                        name(key.fromMember),
                        type(request.fromType),
                        day(key.fromDate),
                        type(request.toType),
                        day(key.toDate),
                    )
                },
                request.fromType,
                listOf(accept to { viewModel.answerSwap(key, true) }, decline to { viewModel.answerSwap(key, false) }),
            )
            key.fromMember == me && request.isOpen -> items += TradeItem(
                swap.key,
                stringResource(
                    if (request.status == SwapStatus.ACCEPTED) R.string.inbox_swap_mine_accepted else R.string.inbox_swap_mine,
                    name(key.toMember),
                    type(request.fromType),
                    day(key.fromDate),
                ),
                request.fromType,
                listOf(withdraw to { viewModel.withdrawSwap(key) }),
                primary = -1,
            )
        }
        if (state.isAdmin && request.status == SwapStatus.ACCEPTED && key.fromMember != me) {
            items += TradeItem(
                swap.key + "#admin",
                stringResource(R.string.inbox_swap_admin, name(key.fromMember), name(key.toMember), day(key.fromDate)),
                request.fromType,
                listOf(approve to { viewModel.executeSwap(key) }, reject to { viewModel.answerSwap(key, false) }),
            )
        }
    }
    for (offer in plan.offers.filter(::current)) {
        val claimedBy = offer.offer.claimedBy
        when {
            offer.memberId == me -> items += TradeItem(
                offer.key,
                if (claimedBy != null) {
                    stringResource(R.string.inbox_offer_mine_claimed, type(offer.offer.typeId), day(offer.date), name(claimedBy))
                } else {
                    stringResource(R.string.inbox_offer_mine, type(offer.offer.typeId), day(offer.date))
                },
                offer.offer.typeId,
                listOf(withdraw to { viewModel.withdrawOffer(CellRef(me, offer.date)) }),
                primary = -1,
            )
            claimedBy == null -> {
                val free = ShiftTrades.isFree(plan, me, offer.date)
                items += TradeItem(
                    offer.key,
                    stringResource(if (free) R.string.inbox_offer_other else R.string.inbox_offer_other_busy, name(offer.memberId), type(offer.offer.typeId), day(offer.date)),
                    offer.offer.typeId,
                    if (free) listOf(take to { viewModel.takeOffer(offer.memberId, offer.date, me) }) else emptyList(),
                )
            }
        }
        if (state.isAdmin && claimedBy != null) {
            items += TradeItem(
                offer.key + "#admin",
                stringResource(R.string.inbox_offer_admin, name(claimedBy), name(offer.memberId), type(offer.offer.typeId), day(offer.date)),
                offer.offer.typeId,
                listOf(approve to { viewModel.approveClaim(offer.memberId, offer.date) }, reject to { viewModel.rejectClaim(offer.memberId, offer.date) }),
            )
        }
    }
    if (items.isEmpty()) return

    OutlinedCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_swap_horiz), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.trade_section), style = MaterialTheme.typography.titleMedium)
            }
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider()
                TradeRow(plan, item)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TradeRow(plan: PlanState, item: TradeItem) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (item.typeId != null) {
                ShiftBadge(type = plan.shiftTypes[item.typeId], typeId = item.typeId, size = 30.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (item.actions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.actions.forEachIndexed { index, (label, action) ->
                    if (index == item.primary) {
                        FilledTonalButton(onClick = action) { Text(label) }
                    } else {
                        TextButton(onClick = action) { Text(label) }
                    }
                }
            }
        }
    }
}

/** Offene Dienste der nächsten zwei Wochen, die ich übernehmen kann. */
@Composable
internal fun OpenShiftsCard(state: PlanUiState, viewModel: PlanViewModel, modifier: Modifier = Modifier) {
    val me = state.myMemberId ?: return
    if (state.readOnly) return
    val plan = state.plan
    val open = remember(plan, state.today, me) {
        ShiftTrades.openShifts(plan, state.today, state.today.plusDays(OPEN_DAYS - 1))
            .filter { ShiftTrades.claimProblem(plan, me, it.date) == null }
    }.filter { state.canEditShift(it.date) }
    if (open.isEmpty()) return
    OutlinedCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_volunteer_activism), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.open_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.open_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (shift in open.take(MAX_OPEN)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShiftBadge(type = shift.type, typeId = shift.type.id, size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(WeekFormat.longDate(shift.date), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.open_line, shift.type.name, shift.missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = { viewModel.claimOpenShift(me, shift.date, shift.type.id) }) {
                        Icon(painterResource(R.drawable.ic_volunteer_activism), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.open_claim))
                    }
                }
            }
            if (open.size > MAX_OPEN) {
                Text(
                    stringResource(R.string.open_more, open.size - MAX_OPEN),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val OPEN_DAYS = 14L
private const val MAX_OPEN = 5
