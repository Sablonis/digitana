package ch.digitana.dienstplan.ui.me

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.crdt.WishStatus
import ch.digitana.dienstplan.core.plan.MyDay
import ch.digitana.dienstplan.core.plan.MyShiftsModel
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.plan.CellRef
import ch.digitana.dienstplan.ui.plan.CellSheet
import ch.digitana.dienstplan.ui.plan.MenuItem
import ch.digitana.dienstplan.ui.plan.PlanMessages
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.plan.cellOf
import ch.digitana.dienstplan.ui.plan.lastChangeText
import ch.digitana.dienstplan.ui.plan.kindLabel
import ch.digitana.dienstplan.ui.plan.wishStatusText
import java.time.LocalDate
import java.time.YearMonth

/** „Ich“: eigene Dienste der nächsten Wochen, Stunden, Wünsche und Notizen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyShiftsScreen(viewModel: PlanViewModel, onExportCalendar: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var openDate by remember { mutableStateOf<LocalDate?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val me = state.myMemberId
    val model = remember(state.plan, me, state.today) { me?.let { MyShiftsModel.build(state.plan, it, state.today) } }

    PlanMessages(viewModel, snackbar, resources)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.me_title)) },
                actions = {
                    if (model != null) {
                        IconButton(onClick = { onExportCalendar(model.member.id) }) {
                            Icon(painterResource(R.drawable.ic_event), contentDescription = stringResource(R.string.me_export_calendar))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                MenuItem(R.string.me_change_person, R.drawable.ic_person) {
                                    menuOpen = false
                                    viewModel.setMe(null)
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        if (model == null) {
            ChooseMe(members = state.plan.members(), onChoose = viewModel::setMe, modifier = content)
        } else {
            val weeks = remember(model) { model.days.groupBy { WeekId.of(it.date) }.toList() }
            LazyColumn(content, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") { MyHeader(model) }
                item(key = "next") { NextShift(model.next) }
                item(key = "wishes") {
                    WishCalendar(
                        plan = state.plan,
                        memberId = model.member.id,
                        memberName = model.member.name,
                        today = state.today,
                        editable = state.canEditWishes(model.member.id),
                        onSetWish = { date, wish -> viewModel.setWish(CellRef(model.member.id, date), wish) },
                    )
                }
                for ((week, days) in weeks) {
                    item(key = "week-${week.bucketName}") {
                        Text(
                            "${WeekFormat.weekLabel(week)} · ${WeekFormat.rangeLabel(week)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
                        )
                    }
                    items(days, key = { it.date.toString() }) { day ->
                        MyDayRow(day = day, types = model.types, onClick = { openDate = day.date })
                    }
                }
            }
        }
    }

    val member = model?.member
    val date = openDate
    if (member != null && date != null) {
        val ref = CellRef(member.id, date)
        CellSheet(
            member = member,
            date = date,
            cell = cellOf(state.plan, ref),
            types = state.plan.shiftTypes,
            readOnly = state.readOnly,
            shiftsEditable = state.canEditShift(date),
            wishesEditable = state.canEditWishes(member.id),
            noteProblem = viewModel::noteProblem,
            onSelectType = { typeId ->
                viewModel.setShift(ref, typeId)
                openDate = null
            },
            onWish = { viewModel.setWish(ref, it) },
            onSaveNote = { viewModel.setMemberNote(ref, it) },
            onDismiss = { openDate = null },
            lastChange = lastChangeText(state.plan, ref),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseMe(members: List<Member>, onChoose: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.me_choose_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(if (members.isEmpty()) R.string.notify_no_members else R.string.me_choose_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(members, key = { it.id }) { member ->
            OutlinedCard(onClick = { onChoose(member.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatar(member.name, member.id, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(member.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MyHeader(model: MyShiftsModel) {
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(model.member.name, model.member.id, size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    model.member.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.me_hours, Format.hours(model.weekMinutes), Format.monthLabel(YearMonth.from(model.today)), Format.hours(model.monthMinutes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun NextShift(next: MyDay?) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (next != null) {
                ShiftBadge(type = next.cell.type, typeId = next.cell.typeId, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.me_next), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(WeekFormat.longDate(next.date), style = MaterialTheme.typography.titleMedium)
                    val type = next.cell.type
                    if (type != null) {
                        Text(
                            listOfNotNull(type.name, Format.timeRange(type)).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Icon(
                    painterResource(R.drawable.ic_today),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.me_next_none), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun MyDayRow(day: MyDay, types: ShiftTypeSet, onClick: () -> Unit) {
    val cell = day.cell
    val type = cell.type
    val dayLabel = WeekFormat.longDate(day.date)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (day.isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClickLabel = stringResource(R.string.me_day_click_label), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = dayLabel }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                WeekFormat.weekday(day.date),
                style = MaterialTheme.typography.labelSmall,
                color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (cell.typeId != null) {
            ShiftBadge(type = type, typeId = cell.typeId, size = 38.dp)
        } else {
            Spacer(Modifier.size(38.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when {
                    type != null -> type.name
                    cell.typeId != null -> stringResource(R.string.shift_unknown)
                    else -> stringResource(R.string.me_no_shift)
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (cell.typeId != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (type != null) {
                Text(
                    Format.timeRange(type) ?: kindLabel(type.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cell.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            day.dayNote?.let {
                Text(
                    stringResource(R.string.me_day_note, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        cell.wish?.let { wish ->
            val status = cell.wishStatus
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (status == WishStatus.FULFILLED) R.drawable.ic_check else R.drawable.ic_favorite),
                    contentDescription = null,
                    tint = if (status == WishStatus.UNMET) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                val label = wish.label(types)
                Text(
                    if (status != null && cell.typeId != null) stringResource(R.string.wish_with_status, label, wishStatusText(status)) else label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 2,
                )
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
