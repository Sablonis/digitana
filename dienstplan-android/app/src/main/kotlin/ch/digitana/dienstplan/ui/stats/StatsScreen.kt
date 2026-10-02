package ch.digitana.dienstplan.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.plan.MemberStats
import ch.digitana.dienstplan.core.plan.MonthModel
import ch.digitana.dienstplan.core.plan.PlanStats
import ch.digitana.dienstplan.core.plan.WorkTime
import ch.digitana.dienstplan.ui.components.EmptyState
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kennzahlen pro Person und Monat: Stunden und Saldo, Wochenend-, Nacht- und Feiertagsdienste, Wünsche. */
private data class StatsView(val stats: PlanStats, val yearDeltas: Map<String, Int?>)

/**
 * Auswertung eines Monats: Wer arbeitet wie viel im Verhältnis zum Pensum, und sind Wochenend-
 * und Nachtdienste fair verteilt? Grundlage für faire Planung, keine Lohnabrechnung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: PlanViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val month by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val view by produceState<StatsView?>(initialValue = null, state.plan, month) {
        value = withContext(Dispatchers.Default) {
            val stats = PlanStats.of(state.plan, month)
            StatsView(stats, stats.members.associate { it.member.id to WorkTime.yearToDateDelta(state.plan, it.member.id, month) })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.selectMonth(month.minusMonths(1)) }, enabled = month > MonthModel.FIRST_MONTH) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.month_previous))
                }
                AnimatedContent(targetState = month, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.weight(1f), label = "Monat") { shown ->
                    Text(Format.monthLabel(shown), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 8.dp))
                }
                IconButton(onClick = { viewModel.selectMonth(month.plusMonths(1)) }, enabled = month < MonthModel.LAST_MONTH) {
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.month_next))
                }
            }
            val current = view
            when {
                current == null -> Box(Modifier.fillMaxSize())
                current.stats.members.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_insights,
                    title = stringResource(R.string.stats_empty_title),
                    text = stringResource(R.string.empty_members),
                )
                else -> {
                    val stats = current.stats
                    val maxWeekend = stats.members.maxOf { it.weekendShifts }.coerceAtLeast(1)
                    val maxNight = stats.members.maxOf { it.nightShifts }.coerceAtLeast(1)
                    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text(
                                stringResource(
                                    R.string.stats_averages,
                                    Format.decimal(stats.averageWeekend),
                                    Format.decimal(stats.averageNight),
                                ) + if (stats.holidays.isEmpty()) "" else " · " + stringResource(R.string.stats_holidays, stats.holidays.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        items(stats.members, key = { it.member.id }) { member ->
                            MemberStatsCard(member, current.yearDeltas[member.member.id], maxWeekend, maxNight, stats.averageWeekend, stats.averageNight)
                        }
                        item {
                            Text(
                                stringResource(R.string.stats_limits),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberStatsCard(stats: MemberStats, yearDelta: Int?, maxWeekend: Int, maxNight: Int, averageWeekend: Double, averageNight: Double) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(stats.member.name, stats.member.id, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stats.member.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stats.pensum?.let { stringResource(R.string.stats_pensum, it) } ?: stringResource(R.string.stats_no_pensum),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Format.hours(stats.balance.plannedMinutes), style = MaterialTheme.typography.titleMedium.tabular())
                    val target = stats.balance.targetMinutes
                    val delta = stats.balance.deltaMinutes
                    if (target != null && delta != null) {
                        Text(
                            stringResource(R.string.stats_target, Format.hours(target), Format.signedHours(delta)),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = when {
                                delta < 0 -> MaterialTheme.colorScheme.error
                                delta > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (yearDelta != null) {
                        Text(
                            stringResource(R.string.stats_year, Format.signedHours(yearDelta)),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Bar(stringResource(R.string.stats_weekend), stats.weekendShifts, maxWeekend, high = stats.weekendShifts > averageWeekend + 1)
            Bar(stringResource(R.string.stats_night), stats.nightShifts, maxNight, high = stats.nightShifts > averageNight + 1)
            Text(
                listOfNotNull(
                    stringResource(R.string.stats_shifts, stats.workShifts),
                    stats.holidayShifts.takeIf { it > 0 }?.let { stringResource(R.string.stats_holiday_shifts, it) },
                    stats.wishes.takeIf { it > 0 }?.let { stringResource(R.string.stats_wishes, stats.wishesFulfilled, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (stats.wishesUnmet > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Waagrechter Balken mit Zahl: auffällig, wenn deutlich über dem Durchschnitt. */
@Composable
private fun Bar(label: String, value: Int, max: Int, high: Boolean) {
    val description = "$label: $value"
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.toFloat() / max)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (high) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelLarge.tabular(),
            modifier = Modifier.width(32.dp).padding(start = 8.dp),
            maxLines = 1,
        )
    }
}
