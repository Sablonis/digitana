package ch.digitana.dienstplan.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.plan.OpenShift
import ch.digitana.dienstplan.core.plan.ShiftTrades
import ch.digitana.dienstplan.core.plan.Suggestion
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.components.ShiftBadge
import java.time.LocalDate
import java.time.YearMonth

/**
 * Abgeben, Übernehmen und Tauschen für ein Feld – gleiche Regeln in Woche, Monat und „Ich“.
 * [onSwap] öffnet die Auswahl eines Tauschpartners, [onDone] schliesst das Eintragsfenster.
 */
internal fun cellTrade(state: PlanUiState, ref: CellRef, viewModel: PlanViewModel, onSwap: () -> Unit, onDone: () -> Unit): CellTrade? {
    if (state.readOnly || state.deviceId == null) return null
    val plan = state.plan
    val typeId = plan.shift(ref.memberId, ref.date)
    val isWork = plan.shiftTypes[typeId]?.kind == ShiftKind.WORK
    val future = !ref.date.isBefore(state.today)
    val offer = plan.offer(ref.memberId, ref.date)?.takeIf { typeId != null && it.typeId == typeId }
    val forMember = state.canTradeFor(ref.memberId)
    val me = state.myMemberId
    val claimedBy = offer?.claimedBy
    return CellTrade(
        canOffer = forMember && isWork && future && offer == null,
        offer = offer,
        claimedByName = claimedBy?.let { id -> plan.members().firstOrNull { it.id == id }?.name },
        canWithdraw = forMember && offer != null,
        canTake = offer != null && claimedBy == null && future && me != null && me != ref.memberId && ShiftTrades.isFree(plan, me, ref.date),
        canSwap = forMember && isWork && future,
        canApprove = state.isAdmin && claimedBy != null,
        onOffer = { viewModel.offerShift(ref) },
        onWithdraw = { viewModel.withdrawOffer(ref) },
        onTake = {
            if (me != null) viewModel.takeOffer(ref.memberId, ref.date, me)
            onDone()
        },
        onSwap = onSwap,
        onApprove = {
            viewModel.approveClaim(ref.memberId, ref.date)
            onDone()
        },
        onReject = { viewModel.rejectClaim(ref.memberId, ref.date) },
    )
}

/** „Ich übernehme“ in der Tagesansicht: nur mit eigener Person, freiem Tag und ohne Sperre. */
internal fun claimAction(state: PlanUiState, date: LocalDate, viewModel: PlanViewModel, onDone: () -> Unit): ((ShiftType) -> Unit)? {
    val me = state.myMemberId ?: return null
    if (state.readOnly || !state.canEditShift(date) || date.isBefore(state.today)) return null
    if (ShiftTrades.claimProblem(state.plan, me, date) != null) return null
    return { type ->
        viewModel.claimOpenShift(me, date, type.id)
        onDone()
    }
}

/** Tablets: Eintragsfenster und Tagesansicht als Spalte neben dem Raster statt als Blatt. */
@Composable
internal fun SidePanel(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.width(400.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                }
            }
            content()
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** Offene Dienste der Woche als Chips; ein Tipp öffnet die Tagesansicht mit „Ich übernehme“. */
@Composable
internal fun OpenShiftsBar(open: List<OpenShift>, onOpenDay: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    if (open.isEmpty()) return
    val resources = LocalResources.current
    val total = open.sumOf { it.missing }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            resources.getQuantityString(R.plurals.open_shifts, total, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        for (shift in open) {
            AssistChip(
                onClick = { onOpenDay(shift.date) },
                label = {
                    val day = WeekFormat.weekday(shift.date)
                    Text(if (shift.missing > 1) "$day · ${shift.type.code} × ${shift.missing}" else "$day · ${shift.type.code}")
                },
                leadingIcon = { ShiftBadge(type = shift.type, typeId = shift.type.id, size = AssistChipDefaults.IconSize) },
            )
        }
    }
}

/** Hinweis auf eine laufende Wunschfrist. */
@Composable
internal fun DeadlineBanner(month: YearMonth, deadline: LocalDate, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_event_available),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.deadline_banner, Format.monthName(month), WeekFormat.longDate(deadline)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** Kurzer Tipp beim ersten Öffnen, mit „Verstanden“. */
@Composable
internal fun TipCard(tip: Int, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (tip) {
        Tips.SWIPE -> R.string.tip_swipe
        Tips.CELL -> R.string.tip_cell
        else -> R.string.tip_brush
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_lightbulb),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.tip_ok)) }
    }
}

/** Vorschau des Plan-Vorschlags: neue Dienste pro Tag und was noch fehlt; übernehmen oder verwerfen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestionSheet(suggestion: Suggestion, plan: PlanState, onApply: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resources = LocalResources.current
    val names = remember(plan) { plan.members().associate { it.id to it.name } }
    val byDay = remember(suggestion) { suggestion.shifts.groupBy { it.date }.toSortedMap() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_auto_awesome), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.suggest_title), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                if (suggestion.isEmpty) {
                    stringResource(R.string.suggest_nothing)
                } else {
                    resources.getQuantityString(R.plurals.suggest_summary, suggestion.shifts.size, suggestion.shifts.size)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (suggestion.gaps.isNotEmpty()) {
                val gaps = suggestion.gaps.sumOf { it.missing }
                Text(
                    resources.getQuantityString(R.plurals.suggest_gaps, gaps, gaps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(R.string.suggest_rules),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                for ((date, shifts) in byDay) {
                    Text(
                        WeekFormat.longDate(date),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    for (shift in shifts) {
                        val name = names[shift.memberId] ?: continue
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            ShiftBadge(type = shift.type, typeId = shift.type.id, size = 28.dp)
                            Spacer(Modifier.width(10.dp))
                            MemberAvatar(name, shift.memberId, size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.suggest_discard)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApply, enabled = !suggestion.isEmpty) {
                    Text(stringResource(R.string.suggest_apply))
                }
            }
        }
    }
}
