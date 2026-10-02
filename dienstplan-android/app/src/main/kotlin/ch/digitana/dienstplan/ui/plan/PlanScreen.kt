package ch.digitana.dienstplan.ui.plan

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.plan.Cell
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.components.SyncStatusChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Seitenindex des Wochen-Pagers: Wochen seit der ersten erlaubten Woche. */
private fun pageOf(week: WeekId): Int = ChronoUnit.WEEKS.between(WeekModel.FIRST_WEEK.monday, week.monday).toInt()

private fun weekAt(page: Int): WeekId = WeekId.of(WeekModel.FIRST_WEEK.monday.plusWeeks(page.toLong()))

private val PAGE_COUNT = pageOf(WeekModel.LAST_WEEK) + 1

/** Wochenansicht: Wischen wechselt die Woche, oben die Übersicht für heute. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenPatterns: () -> Unit,
    onShareWeek: (WeekId) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedWeek by viewModel.selectedWeek.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = pageOf(selectedWeek)) { PAGE_COUNT }
    val shownWeek = weekAt(pagerState.currentPage)
    val thisWeek = WeekId.of(state.today)

    var menuOpen by remember { mutableStateOf(false) }
    var showAddMember by rememberSaveable { mutableStateOf(false) }
    var editMember by remember { mutableStateOf<Member?>(null) }
    var openCell by remember { mutableStateOf<CellRef?>(null) }
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    var confirmCopy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.refreshToday()
        }
    }
    // Der Pager bestimmt die gewählte Woche; „Heute“ und andere Ansichten steuern ihn umgekehrt.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { viewModel.selectWeek(weekAt(it)) }
    }
    LaunchedEffect(selectedWeek) {
        val target = pageOf(selectedWeek)
        if (pagerState.settledPage != target && !pagerState.isScrollInProgress) pagerState.animateScrollToPage(target)
    }
    PlanMessages(viewModel, snackbar, resources)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.teamName?.ifBlank { null } ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    SyncStatusChip(status = state.sync, onClick = onOpenDiagnostics, compact = true)
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!state.readOnly) {
                                MenuItem(R.string.menu_copy_week, R.drawable.ic_copy) {
                                    menuOpen = false
                                    if (viewModel.nextWeekHasEntries(shownWeek)) confirmCopy = true else viewModel.copyWeekToNext(shownWeek)
                                }
                                MenuItem(R.string.menu_patterns, R.drawable.ic_repeat) {
                                    menuOpen = false
                                    onOpenPatterns()
                                }
                                MenuItem(R.string.menu_shift_types, R.drawable.ic_palette) {
                                    menuOpen = false
                                    onOpenShiftTypes()
                                }
                            }
                            MenuItem(R.string.menu_share_week, R.drawable.ic_share) {
                                menuOpen = false
                                onShareWeek(shownWeek)
                            }
                            MenuItem(R.string.menu_diagnostics, R.drawable.ic_cloud) {
                                menuOpen = false
                                onOpenDiagnostics()
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            state.removedFrom?.let { teamName ->
                RemovedBanner(teamName = teamName, onDelete = { confirmDelete = true })
            }
            WeekHeader(
                week = shownWeek,
                isThisWeek = shownWeek == thisWeek,
                canGoBack = pagerState.currentPage > 0,
                canGoForward = pagerState.currentPage < PAGE_COUNT - 1,
                onPrevious = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                onToday = { scope.launch { pagerState.animateScrollToPage(pageOf(thisWeek)) } },
            )
            AnimatedVisibility(
                visible = shownWeek == thisWeek,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                TodayCard(plan = state.plan, today = state.today, myMemberId = state.myMemberId, onClick = { openDay = state.today })
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), key = { it }) { page ->
                val week = weekAt(page)
                val model = remember(state.plan, week, state.today) { WeekModel.build(state.plan, week, state.today) }
                WeekGrid(
                    model = model,
                    myMemberId = state.myMemberId,
                    readOnly = state.readOnly,
                    onCellClick = { openCell = it },
                    onCellLongClick = { viewModel.setShift(it, null) },
                    onDayClick = { openDay = it },
                    onMemberClick = { editMember = it },
                    onAddMember = { showAddMember = true },
                )
            }
        }
    }

    openCell?.let { ref ->
        val member = state.plan.members().firstOrNull { it.id == ref.memberId }
        if (member == null) {
            // Die Person wurde inzwischen gelöscht.
            LaunchedEffect(ref) { openCell = null }
        } else {
            val cell = cellOf(state.plan, ref)
            CellSheet(
                member = member,
                date = ref.date,
                cell = cell,
                types = state.plan.shiftTypes,
                readOnly = state.readOnly,
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
        showAdd = showAddMember,
        onAddClosed = { showAddMember = false },
        editMember = editMember,
        onEditClosed = { editMember = null },
        myMemberId = state.myMemberId,
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.removed_delete_title)) },
            text = { Text(stringResource(R.string.removed_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteLocalData()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.removed_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmCopy) {
        AlertDialog(
            onDismissRequest = { confirmCopy = false },
            title = { Text(stringResource(R.string.copy_week_title)) },
            text = { Text(stringResource(R.string.copy_week_text, WeekFormat.weekLabel(shownWeek.next()))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCopy = false
                    viewModel.copyWeekToNext(shownWeek)
                }) { Text(stringResource(R.string.copy_week_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCopy = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Feld aus dem Plan lesen (für das Bearbeiten im Sheet). */
internal fun cellOf(plan: PlanState, ref: CellRef): Cell {
    val typeId = plan.shift(ref.memberId, ref.date)
    return Cell(typeId, plan.shiftTypes[typeId], plan.wish(ref.memberId, ref.date), plan.memberNote(ref.memberId, ref.date))
}

/** Rückmeldungen des ViewModels als Snackbar. */
@Composable
internal fun PlanMessages(viewModel: PlanViewModel, snackbar: SnackbarHostState, resources: android.content.res.Resources) {
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is PlanMessage.WeekCopied ->
                    if (message.changedFields == 0) {
                        resources.getString(R.string.copy_week_nothing)
                    } else {
                        resources.getString(R.string.copy_week_done, WeekFormat.weekLabel(message.target), message.changedFields)
                    }
                is PlanMessage.PatternApplied -> resources.getString(R.string.pattern_applied, message.changedFields)
                PlanMessage.Failed -> resources.getString(R.string.error_generic)
            }
            snackbar.showSnackbar(text)
        }
    }
}

@Composable
internal fun MenuItem(@StringRes label: Int, @DrawableRes icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** Hinzufügen, Umbenennen, Löschen und „Das bin ich“ für Personen. */
@Composable
internal fun MemberDialogs(
    viewModel: PlanViewModel,
    showAdd: Boolean,
    onAddClosed: () -> Unit,
    editMember: Member?,
    onEditClosed: () -> Unit,
    myMemberId: String?,
) {
    if (showAdd) {
        MemberNameDialog(
            title = stringResource(R.string.member_add_title),
            initialName = "",
            confirmLabel = stringResource(R.string.action_add),
            nameProblem = viewModel::nameProblem,
            onConfirm = { name ->
                viewModel.addMember(name)
                onAddClosed()
            },
            onDismiss = onAddClosed,
        )
    }
    editMember?.let { member ->
        EditMemberDialog(
            member = member,
            nameProblem = viewModel::nameProblem,
            onRename = { name ->
                viewModel.renameMember(member.id, name)
                onEditClosed()
            },
            onDelete = {
                viewModel.deleteMember(member.id)
                onEditClosed()
            },
            onDismiss = onEditClosed,
            isMe = member.id == myMemberId,
            onToggleMe = {
                viewModel.setMe(if (member.id == myMemberId) null else member.id)
                onEditClosed()
            },
        )
    }
}

/** Hinweis nach dem Entfernen aus dem Team: Plan nur noch lesbar, Daten löschen möglich. */
@Composable
internal fun RemovedBanner(teamName: String, onDelete: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.removed_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.removed_text, teamName.ifBlank { "–" }),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.removed_delete))
            }
        }
    }
}

/** „KW 40“ mit Zeitraum, Pfeilen und „Heute“. */
@Composable
private fun WeekHeader(
    week: WeekId,
    isThisWeek: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = canGoBack) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.week_previous))
        }
        AnimatedContent(
            targetState = week,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "Woche",
        ) { shown ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(R.string.week_today_hint), onClick = onToday)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(WeekFormat.weekLabel(shown), style = MaterialTheme.typography.headlineSmall)
                Text(
                    WeekFormat.rangeLabel(shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!isThisWeek) {
            TextButton(onClick = onToday) {
                Icon(painterResource(R.drawable.ic_today), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.today_label))
            }
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.week_next))
        }
    }
}

/** Heute auf einen Blick: eigener Dienst und Besetzung je Schicht. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayCard(plan: PlanState, today: LocalDate, myMemberId: String?, onClick: () -> Unit) {
    val summary = remember(plan, today) { DaySummary.of(plan, today) }
    val myCell = remember(plan, today, myMemberId) { myMemberId?.let { cellOf(plan, CellRef(it, today)) } }
    val gradient = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .clickable(onClickLabel = stringResource(R.string.day_click_label), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.today_title, WeekFormat.longDate(today)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val myType = myCell?.type
            if (myCell != null) {
                Text(
                    text = when {
                        myType != null -> stringResource(
                            R.string.today_mine,
                            listOfNotNull(myType.name, Format.timeRange(myType)).joinToString(" · "),
                        )
                        myCell.typeId != null -> stringResource(R.string.today_mine, stringResource(R.string.shift_unknown))
                        else -> stringResource(R.string.today_mine_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            val working = summary.groups.filter { (type, _) -> type.first?.countsForCoverage == true }
            if (working.isEmpty()) {
                Text(
                    stringResource(R.string.today_nobody),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((type, members) in working) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ShiftBadge(type = type.first, typeId = type.second, size = 22.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                members.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}
