package ch.digitana.dienstplan.ui.month

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import ch.digitana.dienstplan.core.plan.DayInfo
import ch.digitana.dienstplan.core.plan.MonthModel
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.core.plan.WishTally
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.components.SyncStatusChip
import ch.digitana.dienstplan.ui.plan.CellRef
import ch.digitana.dienstplan.ui.plan.CellSheet
import ch.digitana.dienstplan.ui.plan.DaySheet
import ch.digitana.dienstplan.ui.plan.LockBanner
import ch.digitana.dienstplan.ui.plan.LockDialog
import ch.digitana.dienstplan.ui.plan.MemberDialogs
import ch.digitana.dienstplan.ui.plan.PlanMessages
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.plan.ShiftCellView
import ch.digitana.dienstplan.ui.plan.cellOf
import ch.digitana.dienstplan.ui.plan.dayTint
import java.time.LocalDate
import java.time.YearMonth

private val NAME_WIDTH = 116.dp
private val DAY_WIDTH = 40.dp
private val ROW_HEIGHT = 50.dp
private val HEADER_HEIGHT = 52.dp

/** Monatsansicht: alle Personen über den ganzen Monat, Tage waagrecht scrollbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthScreen(viewModel: PlanViewModel, onOpenDiagnostics: () -> Unit, onShareMonth: (YearMonth) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val month by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val lockBusy by viewModel.lockBusy.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val model = remember(state.plan, month, state.today) { MonthModel.build(state.plan, month, state.today) }
    val thisMonth = YearMonth.from(state.today)
    val horizontal = rememberScrollState()
    var openCell by remember { mutableStateOf<CellRef?>(null) }
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    var editMember by remember { mutableStateOf<Member?>(null) }
    var lockDialog by remember { mutableStateOf(false) }

    PlanMessages(viewModel, snackbar, resources)
    val dayWidthPx = with(LocalDensity.current) { DAY_WIDTH.toPx() }
    // Im laufenden Monat so scrollen, dass heute (mit zwei Tagen davor) sichtbar ist.
    LaunchedEffect(month, thisMonth) {
        val target = if (month == thisMonth) ((state.today.dayOfMonth - 3).coerceAtLeast(0) * dayWidthPx).toInt() else 0
        horizontal.scrollTo(target)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_month)) },
                actions = {
                    SyncStatusChip(status = state.sync, onClick = onOpenDiagnostics, compact = true)
                    IconButton(onClick = { onShareMonth(month) }) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.menu_share_month))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.selectMonth(month.minusMonths(1)) }, enabled = month > MonthModel.FIRST_MONTH) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.month_previous))
                }
                AnimatedContent(
                    targetState = month,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.weight(1f),
                    label = "Monat",
                ) { shown ->
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(Format.monthLabel(shown), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.month_total, Format.hours(model.totalMinutes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (month != thisMonth) {
                    TextButton(onClick = viewModel::goToThisMonth) {
                        Icon(painterResource(R.drawable.ic_today), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.today_label))
                    }
                }
                IconButton(onClick = { viewModel.selectMonth(month.plusMonths(1)) }, enabled = month < MonthModel.LAST_MONTH) {
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.month_next))
                }
            }

            if (lockBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            val lock = state.lock
            if (lock != null && lock.isLocked(month.atDay(1))) {
                LockBanner(
                    lock = lock,
                    isAdmin = state.isAdmin,
                    onManage = if (state.isAdmin && !state.readOnly) {
                        { lockDialog = true }
                    } else {
                        null
                    },
                )
            }

            // Kopfzeile mit Tagen; scrollt waagrecht mit dem Raster (gemeinsamer ScrollState).
            Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
                Box(Modifier.width(NAME_WIDTH).height(HEADER_HEIGHT), contentAlignment = Alignment.CenterStart) {
                    Text(
                        stringResource(R.string.column_team),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                Row(Modifier.horizontalScroll(horizontal)) {
                    for (day in model.days) DayHeader(day, locked = day.editable && lock?.isLocked(day.date) == true) { openDay = day.date }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (model.rows.isEmpty()) {
                    Text(
                        stringResource(R.string.empty_members),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                Row {
                    Column(Modifier.width(NAME_WIDTH)) {
                        for (row in model.rows) {
                            NameCell(
                                member = row.member,
                                minutes = row.minutes,
                                isMe = row.member.id == state.myMemberId,
                                enabled = !state.readOnly,
                                onClick = { editMember = row.member },
                            )
                        }
                    }
                    Column(Modifier.horizontalScroll(horizontal)) {
                        for (row in model.rows) {
                            Row(Modifier.height(ROW_HEIGHT)) {
                                model.days.forEachIndexed { index, day ->
                                    val ref = CellRef(row.member.id, day.date)
                                    ShiftCellView(
                                        cell = row.cells[index],
                                        day = day,
                                        memberName = row.member.name,
                                        types = model.types,
                                        enabled = day.editable && !state.readOnly,
                                        onClick = { openCell = ref },
                                        onLongClick = { viewModel.setShift(ref, null) },
                                        modifier = Modifier.width(DAY_WIDTH),
                                        showTime = false,
                                        allowLongClick = day.editable && state.canEditShift(day.date),
                                    )
                                }
                            }
                        }
                    }
                }
                val tallies = model.wishTallies
                if (tallies.isNotEmpty()) WishTallyCard(month, tallies)
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.coverage_total),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(NAME_WIDTH).padding(start = 16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.horizontalScroll(horizontal)) {
                        model.days.forEachIndexed { index, day ->
                            val total = model.coverage[index].total
                            val description = stringResource(R.string.coverage_description, WeekFormat.longDate(day.date), total.toString())
                            Box(
                                modifier = Modifier
                                    .width(DAY_WIDTH)
                                    .height(40.dp)
                                    .background(dayTint(day))
                                    .semantics { contentDescription = description },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    total.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (total > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    openCell?.let { ref ->
        val member = state.plan.members().firstOrNull { it.id == ref.memberId }
        if (member == null) {
            LaunchedEffect(ref) { openCell = null }
        } else {
            CellSheet(
                member = member,
                date = ref.date,
                cell = cellOf(state.plan, ref),
                types = state.plan.shiftTypes,
                readOnly = state.readOnly,
                shiftsEditable = state.canEditShift(ref.date),
                wishesEditable = state.canEditWishes(ref.memberId),
                noteProblem = viewModel::noteProblem,
                onSelectType = { typeId ->
                    viewModel.setShift(ref, typeId)
                    openCell = null
                },
                onWish = { viewModel.setWish(ref, it) },
                onSaveNote = { viewModel.setMemberNote(ref, it) },
                onDismiss = { openCell = null },
            )
        }
    }
    openDay?.let { date ->
        DaySheet(
            plan = state.plan,
            date = date,
            isToday = date == state.today,
            readOnly = state.readOnly,
            noteProblem = viewModel::noteProblem,
            onSaveNote = { viewModel.setDayNote(date, it) },
            onDismiss = { openDay = null },
        )
    }
    MemberDialogs(
        viewModel = viewModel,
        showAdd = false,
        onAddClosed = {},
        editMember = editMember,
        onEditClosed = { editMember = null },
        myMemberId = state.myMemberId,
        canDelete = state.canDeleteMembers,
    )
    if (lockDialog) {
        LockDialog(
            current = state.lock,
            today = state.today,
            onLock = { until ->
                lockDialog = false
                viewModel.setPlanLock(true, until)
            },
            onOpen = {
                lockDialog = false
                viewModel.setPlanLock(false, null)
            },
            onDismiss = { lockDialog = false },
        )
    }
}

/** Wünsche des Monats pro Person: wie viele erfüllt, nicht erfüllt oder noch offen sind. */
@Composable
private fun WishTallyCard(month: YearMonth, tallies: List<WishTally>) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_favorite),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.wishes_month_title, Format.monthLabel(month)), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(R.string.wishes_tally_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (tally in tallies) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemberAvatar(tally.member.name, tally.member.id, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tally.member.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.wishes_tally_line, tally.fulfilled, tally.total, tally.unmet, tally.open),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tally.unmet > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { tally.fulfilled.toFloat() / tally.total },
                        modifier = Modifier.width(72.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: DayInfo, locked: Boolean, onClick: () -> Unit) {
    val today = stringResource(R.string.today_marker)
    val lockMarker = stringResource(R.string.lock_day_marker)
    val description = WeekFormat.longDate(day.date) + (if (day.isToday) ", $today" else "") + (if (locked) ", $lockMarker" else "")
    Column(
        modifier = Modifier
            .width(DAY_WIDTH)
            .height(HEADER_HEIGHT)
            .background(dayTint(day))
            .clickable(enabled = day.editable, onClickLabel = stringResource(R.string.day_click_label), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            WeekFormat.weekday(day.date),
            style = MaterialTheme.typography.labelSmall,
            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (day.isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (day.isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (locked) {
            Icon(
                painterResource(R.drawable.ic_lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(8.dp),
            )
        } else {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (day.note != null) MaterialTheme.colorScheme.tertiary else Color.Transparent),
            )
        }
    }
}

@Composable
private fun NameCell(member: Member, minutes: Int, isMe: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .width(NAME_WIDTH)
            .height(ROW_HEIGHT)
            .background(if (isMe) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = stringResource(R.string.member_click_label), onClick = onClick)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(member.name, member.id, size = 28.dp, highlighted = isMe)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                member.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(Format.hours(minutes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
