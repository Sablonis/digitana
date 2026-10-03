package ch.digitana.dienstplan.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Canton
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftOffer
import ch.digitana.dienstplan.core.crdt.ShiftPatterns
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.SwapRequest
import ch.digitana.dienstplan.core.crdt.SwapStatus
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.plan.Activity
import ch.digitana.dienstplan.core.plan.ActivityItem
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.EmptyState
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * „Aktivität“: wer zuletzt was geändert hat, neueste zuerst. Neues seit dem letzten Besuch ist
 * hervorgehoben und gilt beim Verlassen der Seite als gesehen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(viewModel: PlanViewModel, onBack: () -> Unit, onOpenWeek: (WeekId) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Was beim Öffnen neu war, bleibt hervorgehoben, bis die Seite verlassen wird.
    val unseen = remember { state.markers.unseen }
    DisposableEffect(Unit) { onDispose { viewModel.markAllSeen() } }
    val items by produceState<List<ActivityItem>?>(initialValue = null, state.plan) {
        value = withContext(Dispatchers.Default) { Activity.recent(state.plan) }
    }
    val zone = remember { ZoneId.systemDefault() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.markers.unseen.isNotEmpty()) {
                        TextButton(onClick = viewModel::markAllSeen) { Text(stringResource(R.string.activity_mark_seen)) }
                    }
                },
            )
        },
    ) { padding ->
        val list = items
        val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        when {
            list == null -> Box(content)
            list.isEmpty() -> Box(content, contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = R.drawable.ic_history,
                    title = stringResource(R.string.activity_empty_title),
                    text = stringResource(R.string.activity_empty_text),
                )
            }
            else -> {
                val byDay = remember(list) { list.groupBy { Instant.ofEpochMilli(it.entry.timestamp).atZone(zone).toLocalDate() } }
                LazyColumn(content, contentPadding = PaddingValues(bottom = 24.dp)) {
                    for ((day, dayItems) in byDay) {
                        item(key = "day-$day") { DayHeader(day, state.today) }
                        items(dayItems, key = { it.key }) { item ->
                            ActivityRow(
                                item = item,
                                plan = state.plan,
                                isNew = item.key in unseen,
                                onClick = activityDate(item)?.let { date -> { onOpenWeek(WeekId.of(date)) } },
                            )
                        }
                    }
                    item(key = "limits") {
                        Text(
                            stringResource(R.string.activity_limits),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Tag, zu dem eine Änderung gehört (für „zur Woche springen“); null für Teameinstellungen. */
private fun activityDate(item: ActivityItem): LocalDate? = when (val key = item.parsed) {
    is PlanKey.Shift -> key.date
    is PlanKey.Wish -> key.date
    is PlanKey.MemberNote -> key.date
    is PlanKey.DayNote -> key.date
    is PlanKey.Offer -> key.date
    is PlanKey.Swap -> key.fromDate
    else -> null
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    Text(
        when (day) {
            today -> stringResource(R.string.today_label)
            today.minusDays(1) -> stringResource(R.string.activity_yesterday)
            else -> WeekFormat.longDate(day)
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun ActivityRow(item: ActivityItem, plan: PlanState, isNew: Boolean, onClick: (() -> Unit)?) {
    val owners = remember(plan) { plan.deviceOwners() }
    val members = remember(plan) { plan.members().associateBy { it.id } }
    val authorMember = owners[item.entry.device]?.let { members[it] }
    val author = plan.authorName(item.entry.device) ?: stringResource(R.string.change_unknown_device, item.entry.device.take(6))
    val text = describe(item, plan)
    val typeId = (item.parsed as? PlanKey.Shift)?.let { item.entry.value.ifEmpty { null } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isNew) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.activity_open_week), onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (authorMember != null) {
            MemberAvatar(authorMember.name, authorMember.id, size = 36.dp)
        } else {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.activity_by, author, Format.changeTime(item.entry.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (typeId != null) {
            Spacer(Modifier.width(8.dp))
            ShiftBadge(type = plan.shiftTypes[typeId], typeId = typeId, size = 30.dp)
        }
        if (isNew) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Kurze Beschreibung einer Änderung, z. B. „Anna: Mo 5.10. → Früh“. */
@Composable
private fun describe(item: ActivityItem, plan: PlanState): String {
    val names = remember(plan) { plan.members().associate { it.id to it.name } }
    val unknown = stringResource(R.string.activity_unknown_person)
    fun name(id: String) = names[id] ?: unknown
    fun day(date: LocalDate) = "${WeekFormat.weekday(date)} ${WeekFormat.shortDate(date)}"
    val value = item.entry.value
    val types = plan.shiftTypes
    return when (val key = item.parsed) {
        is PlanKey.Shift ->
            if (value.isEmpty()) {
                stringResource(R.string.activity_shift_removed, name(key.memberId), day(key.date))
            } else {
                stringResource(R.string.activity_shift, name(key.memberId), day(key.date), types[value]?.name ?: stringResource(R.string.shift_unknown))
            }
        is PlanKey.Wish ->
            Wish.fromCode(value)?.let { stringResource(R.string.activity_wish, name(key.memberId), day(key.date), it.label(types)) }
                ?: stringResource(R.string.activity_wish_removed, name(key.memberId), day(key.date))
        is PlanKey.MemberNote ->
            if (value.isEmpty()) {
                stringResource(R.string.activity_note_removed, name(key.memberId), day(key.date))
            } else {
                stringResource(R.string.activity_member_note, name(key.memberId), day(key.date), value)
            }
        is PlanKey.DayNote ->
            if (value.isEmpty()) stringResource(R.string.activity_day_note_removed, day(key.date)) else stringResource(R.string.activity_day_note, day(key.date), value)
        is PlanKey.Member ->
            if (value.isEmpty()) stringResource(R.string.activity_member_deleted) else stringResource(R.string.activity_member, value)
        is PlanKey.ShiftType ->
            ShiftTypes.decode(key.typeId, value)?.let { stringResource(R.string.activity_shift_type, it.name) }
                ?: stringResource(R.string.activity_shift_type_reset, types[key.typeId]?.name ?: key.typeId)
        is PlanKey.Pattern ->
            ShiftPatterns.decode(key.patternId, value)?.let { stringResource(R.string.activity_pattern, it.name) }
                ?: stringResource(R.string.activity_pattern_deleted)
        is PlanKey.Setting -> settingText(key.name, value)
        is PlanKey.Target ->
            if (value.isEmpty()) {
                stringResource(R.string.activity_target_removed, types[key.typeId]?.name ?: key.typeId)
            } else {
                stringResource(R.string.activity_target, types[key.typeId]?.name ?: key.typeId)
            }
        is PlanKey.Pensum ->
            value.toIntOrNull()?.let { stringResource(R.string.activity_pensum, name(key.memberId), it) }
                ?: stringResource(R.string.activity_pensum_removed, name(key.memberId))
        is PlanKey.Offer -> {
            val offer = ShiftOffer.decode(value)
            val claimer = offer?.claimedBy
            when {
                offer == null -> stringResource(R.string.activity_offer_closed, name(key.memberId), day(key.date))
                claimer != null -> stringResource(R.string.activity_offer_claimed, name(claimer), name(key.memberId), day(key.date))
                else -> stringResource(R.string.activity_offer, name(key.memberId), types[offer.typeId]?.name ?: stringResource(R.string.shift_unknown), day(key.date))
            }
        }
        is PlanKey.Swap -> {
            val request = SwapRequest.decode(value)
            val from = name(key.fromMember)
            val to = name(key.toMember)
            when (request?.status) {
                null -> stringResource(R.string.activity_swap_withdrawn, from, to)
                SwapStatus.PROPOSED -> stringResource(R.string.activity_swap_proposed, from, to, day(key.fromDate), day(key.toDate))
                SwapStatus.ACCEPTED -> stringResource(R.string.activity_swap_accepted, to, from)
                SwapStatus.DONE -> stringResource(R.string.activity_swap_done, from, to)
                SwapStatus.DECLINED -> stringResource(R.string.activity_swap_declined, to, from)
            }
        }
        is PlanKey.Device, is PlanKey.DeviceOwner -> ""
    }
}

@Composable
private fun settingText(name: String, value: String): String {
    val month = PlanRules.wishDeadlineMonth(name)
    return when {
        month != null -> PlanRules.decodeDate(value)?.let { stringResource(R.string.activity_deadline, Format.monthLabel(month), WeekFormat.longDate(it)) }
            ?: stringResource(R.string.activity_deadline_removed, Format.monthLabel(month))
        name == PlanRules.REST -> stringResource(
            R.string.activity_rest,
            value.toIntOrNull()?.let { if (it == 0) stringResource(R.string.activity_rest_off) else Format.hours(it) } ?: Format.hours(PlanRules.DEFAULT_REST_MINUTES),
        )
        name == PlanRules.HOURS -> stringResource(R.string.activity_hours, Format.hours(value.toIntOrNull() ?: PlanRules.DEFAULT_WEEK_MINUTES))
        name == PlanRules.CANTON -> Canton.fromCode(value)?.let { stringResource(R.string.activity_canton, it.label) } ?: stringResource(R.string.activity_canton_removed)
        else -> stringResource(R.string.activity_setting)
    }
}
