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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.data.TradeOutcome
import ch.digitana.dienstplan.core.plan.Cell
import ch.digitana.dienstplan.core.plan.Reminders
import ch.digitana.dienstplan.core.plan.ShiftTrades
import ch.digitana.dienstplan.core.plan.TradeProblem
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.ui.components.EmptyState
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.components.SyncStatusChip
import ch.digitana.dienstplan.ui.team.messageRes
import ch.digitana.dienstplan.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Seitenindex des Wochen-Pagers: Wochen seit der ersten erlaubten Woche. */
private fun pageOf(week: WeekId): Int = ChronoUnit.WEEKS.between(WeekModel.FIRST_WEEK.monday, week.monday).toInt()

private fun weekAt(page: Int): WeekId = WeekId.of(WeekModel.FIRST_WEEK.monday.plusWeeks(page.toLong()))

private val PAGE_COUNT = pageOf(WeekModel.LAST_WEEK) + 1

/** Ab dieser Breite stehen Eintragsfenster und Tagesansicht neben dem Raster. */
private val TWO_PANE_MIN_WIDTH = 840.dp

/** So viele Tage vor einer Wunschfrist erscheint der Hinweis. */
private const val DEADLINE_BANNER_DAYS = 28L

/** Wochenansicht: Wischen wechselt die Woche, oben die Übersicht für heute. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenPatterns: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    onShareWeek: (WeekId) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedWeek by viewModel.selectedWeek.collectAsStateWithLifecycle()
    val lockBusy by viewModel.lockBusy.collectAsStateWithLifecycle()
    val canUndoStroke by viewModel.canUndoStroke.collectAsStateWithLifecycle()
    val suggestion by viewModel.suggestion.collectAsStateWithLifecycle()
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
    var swapFor by remember { mutableStateOf<CellRef?>(null) }
    var confirmCopy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var lockDialog by remember { mutableStateOf(false) }
    // „Schnell eintragen“: gewählte Schichtart wird mit jedem Antippen oder Wischen eingetragen (null = leeren).
    var brushOn by rememberSaveable { mutableStateOf(false) }
    var brushType by rememberSaveable { mutableStateOf<String?>(null) }
    val brushReady = brushOn && !state.readOnly
    // Pro Strich höchstens ein Hinweis auf gesperrte Tage.
    var lockedHintShown by remember { mutableStateOf(false) }

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
    // Geöffnetes Feld gilt als gesehen.
    LaunchedEffect(openCell) { openCell?.let { viewModel.markSeen(it.keys()) } }
    PlanMessages(viewModel, snackbar, resources)

    val brushGestures = if (brushReady) {
        BrushGestures(
            onStart = {
                lockedHintShown = false
                viewModel.beginStroke()
            },
            onPaint = { ref ->
                if (state.canEditShift(ref.date)) {
                    viewModel.paint(ref, brushType)
                } else if (!lockedHintShown) {
                    lockedHintShown = true
                    viewModel.notify(PlanMessage.Locked)
                }
            },
            onEnd = { viewModel.endStroke() },
        )
    } else {
        null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
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
                        val unseen = state.markers.unseen.size
                        IconButton(onClick = onOpenActivity) {
                            BadgedBox(badge = { if (unseen > 0) Badge { Text(if (unseen > 99) "99+" else unseen.toString()) } }) {
                                Icon(
                                    painterResource(R.drawable.ic_history),
                                    contentDescription = if (unseen > 0) {
                                        resources.getQuantityString(R.plurals.activity_unseen, unseen, unseen)
                                    } else {
                                        stringResource(R.string.activity_title)
                                    },
                                )
                            }
                        }
                        SyncStatusChip(status = state.sync, onClick = onOpenDiagnostics, compact = true)
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!state.readOnly) {
                                    MenuItem(R.string.brush_menu, R.drawable.ic_brush) {
                                        menuOpen = false
                                        if (brushType == null || state.plan.shiftTypes[brushType] == null) {
                                            brushType = state.plan.shiftTypes.active.firstOrNull { it.countsForCoverage }?.id
                                        }
                                        brushOn = true
                                    }
                                    if (state.plan.targets.isNotEmpty()) {
                                        MenuItem(R.string.suggest_menu, R.drawable.ic_auto_awesome) {
                                            menuOpen = false
                                            viewModel.suggest(shownWeek.days)
                                        }
                                    }
                                    MenuItem(R.string.menu_copy_week, R.drawable.ic_copy) {
                                        menuOpen = false
                                        when {
                                            shownWeek.next().days.any { !state.canEditShift(it) } -> viewModel.notify(PlanMessage.WeekLocked)
                                            viewModel.nextWeekHasEntries(shownWeek) -> confirmCopy = true
                                            else -> viewModel.copyWeekToNext(shownWeek)
                                        }
                                    }
                                    MenuItem(R.string.menu_patterns, R.drawable.ic_repeat) {
                                        menuOpen = false
                                        onOpenPatterns()
                                    }
                                    MenuItem(R.string.menu_shift_types, R.drawable.ic_palette) {
                                        menuOpen = false
                                        onOpenShiftTypes()
                                    }
                                    MenuItem(R.string.menu_rules, R.drawable.ic_tune) {
                                        menuOpen = false
                                        onOpenRules()
                                    }
                                    if (state.isAdmin) {
                                        MenuItem(if (state.lock == null) R.string.lock_dialog_title else R.string.lock_action_change, R.drawable.ic_lock) {
                                            menuOpen = false
                                            lockDialog = true
                                        }
                                    }
                                }
                                MenuItem(R.string.menu_share_week, R.drawable.ic_share) {
                                    menuOpen = false
                                    onShareWeek(shownWeek)
                                }
                                MenuItem(R.string.settings_title, R.drawable.ic_settings) {
                                    menuOpen = false
                                    onOpenSettings()
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
            bottomBar = {
                AnimatedVisibility(visible = brushReady, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    BrushBar(
                        types = state.plan.shiftTypes,
                        selected = brushType,
                        canUndo = canUndoStroke,
                        onSelect = { brushType = it },
                        onUndo = viewModel::undoLastStroke,
                        onDone = { brushOn = false },
                    )
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
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
                    if (lockBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    val lock = state.lock
                    AnimatedVisibility(
                        visible = lock != null && lock.isLocked(weekAt(pagerState.settledPage).monday),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        if (lock != null) {
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
                    }
                    val deadline = remember(state.plan, state.today) { Reminders.openDeadline(state.plan, state.today) }
                    AnimatedVisibility(
                        visible = deadline != null && !state.readOnly && !state.today.plusDays(DEADLINE_BANNER_DAYS).isBefore(deadline.second),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        if (deadline != null) DeadlineBanner(deadline.first, deadline.second)
                    }
                    val tip = Tips.ALL.firstOrNull { state.tipsSeen and it == 0 }
                    AnimatedVisibility(
                        visible = tip != null && !state.readOnly && state.plan.members().isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        if (tip != null) TipCard(tip, onDismiss = { viewModel.dismissTip(tip) })
                    }
                    // Erst nach dem Wischen ein- oder ausblenden, damit das Raster nicht mitten im Wischen springt.
                    AnimatedVisibility(
                        visible = weekAt(pagerState.settledPage) == thisWeek,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        TodayCard(plan = state.plan, today = state.today, myMemberId = state.myMemberId, onClick = { openDay = state.today })
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        key = { it },
                        // Beim schnellen Eintragen malt Wischen; die Woche wechseln dann die Pfeile.
                        userScrollEnabled = !brushReady,
                    ) { page ->
                        val week = weekAt(page)
                        val model = remember(state.plan, week, state.today) { WeekModel.build(state.plan, week, state.today) }
                        val open = remember(state.plan, week) { ShiftTrades.openShifts(state.plan, week.monday, week.sunday) }
                        val noShifts = model.rows.isNotEmpty() && model.rows.all { row -> row.cells.all { it.typeId == null } }
                        Column(Modifier.fillMaxSize()) {
                            PlanChecks(understaffed = model.understaffedCount, restIssues = model.restIssueCount)
                            OpenShiftsBar(open = open, onOpenDay = { openDay = it }, modifier = Modifier.padding(bottom = 4.dp))
                            WeekGrid(
                                model = model,
                                myMemberId = state.myMemberId,
                                readOnly = state.readOnly,
                                onCellClick = { ref ->
                                    when {
                                        !brushReady -> openCell = ref
                                        state.canEditShift(ref.date) -> viewModel.paint(ref, brushType)
                                        else -> viewModel.notify(PlanMessage.Locked)
                                    }
                                },
                                onCellLongClick = { viewModel.clearShift(it) },
                                onDayClick = { openDay = it },
                                onMemberClick = { editMember = it },
                                onAddMember = { showAddMember = true },
                                isLocked = { state.lock?.isLocked(it) == true },
                                canEditShift = state::canEditShift,
                                markers = state.markers,
                                selected = openCell,
                                brush = brushGestures,
                                emptyContent = if (noShifts && !state.readOnly) {
                                    {
                                        EmptyState(
                                            icon = R.drawable.ic_event,
                                            title = stringResource(R.string.empty_week_title),
                                            text = stringResource(R.string.empty_week_text),
                                            action = stringResource(R.string.empty_week_pattern) to onOpenPatterns,
                                            secondary = if (state.plan.targets.isNotEmpty()) {
                                                stringResource(R.string.suggest_menu) to { viewModel.suggest(week.days) }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                val cellRef = openCell
                val dayDate = openDay
                if (twoPane && (cellRef != null || dayDate != null)) {
                    SidePanel(onClose = {
                        openCell = null
                        openDay = null
                    }) {
                        if (cellRef != null) {
                            CellContent(state, viewModel, cellRef, onClose = { openCell = null }, onSwap = { swapFor = cellRef })
                        } else if (dayDate != null) {
                            DaySheetContent(
                                plan = state.plan,
                                date = dayDate,
                                isToday = dayDate == state.today,
                                readOnly = state.readOnly,
                                noteProblem = viewModel::noteProblem,
                                onSaveNote = { viewModel.setDayNote(dayDate, it) },
                                onClaim = claimAction(state, dayDate, viewModel) { openDay = null },
                            )
                        }
                    }
                }
            }
        }

        if (!twoPane) {
            openCell?.let { ref ->
                val member = state.plan.members().firstOrNull { it.id == ref.memberId }
                if (member == null) {
                    // Die Person wurde inzwischen gelöscht.
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
                        lastChange = lastChangeText(state.plan, ref),
                        restIssueFor = restCheck(state.plan, ref),
                        trade = cellTrade(state, ref, viewModel, onSwap = {
                            swapFor = ref
                            openCell = null
                        }, onDone = { openCell = null }),
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
                    onClaim = claimAction(state, date, viewModel) { openDay = null },
                )
            }
        }
    }

    swapFor?.let { ref ->
        SwapPickerSheet(
            plan = state.plan,
            memberId = ref.memberId,
            date = ref.date,
            onPick = { candidate ->
                swapFor = null
                viewModel.proposeSwap(ref.memberId, ref.date, candidate.memberId, candidate.date)
            },
            onDismiss = { swapFor = null },
        )
    }

    suggestion?.let {
        SuggestionSheet(suggestion = it, plan = state.plan, onApply = viewModel::applySuggestion, onDismiss = viewModel::discardSuggestion)
    }

    MemberDialogs(
        viewModel = viewModel,
        showAdd = showAddMember,
        onAddClosed = { showAddMember = false },
        editMember = editMember,
        onEditClosed = { editMember = null },
        myMemberId = state.myMemberId,
        canDelete = state.canDeleteMembers,
        canEditPensum = state.canEditRules,
        pensumOf = { state.plan.pensum(it) },
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

/** Eintragsfenster als Inhalt (Seitenspalte auf Tablets). */
@Composable
internal fun CellContent(state: PlanUiState, viewModel: PlanViewModel, ref: CellRef, onClose: () -> Unit, onSwap: () -> Unit) {
    val member = state.plan.members().firstOrNull { it.id == ref.memberId }
    if (member == null) {
        LaunchedEffect(ref) { onClose() }
        return
    }
    CellSheetContent(
        member = member,
        date = ref.date,
        cell = cellOf(state.plan, ref),
        types = state.plan.shiftTypes,
        readOnly = state.readOnly,
        shiftsEditable = state.canEditShift(ref.date),
        wishesEditable = state.canEditWishes(ref.memberId),
        noteProblem = viewModel::noteProblem,
        onSelectType = { typeId -> viewModel.setShift(ref, typeId) },
        onWish = { viewModel.setWish(ref, it) },
        onSaveNote = { viewModel.setMemberNote(ref, it) },
        lastChange = lastChangeText(state.plan, ref),
        restIssueFor = restCheck(state.plan, ref),
        trade = cellTrade(state, ref, viewModel, onSwap = onSwap, onDone = onClose),
    )
}

/** Feld aus dem Plan lesen (für das Bearbeiten im Sheet). */
internal fun cellOf(plan: PlanState, ref: CellRef): Cell {
    val typeId = plan.shift(ref.memberId, ref.date)
    val offered = typeId != null && plan.offer(ref.memberId, ref.date)?.typeId == typeId
    return Cell(typeId, plan.shiftTypes[typeId], plan.wish(ref.memberId, ref.date), plan.memberNote(ref.memberId, ref.date), offered = offered)
}

/** Rückmeldungen des ViewModels als Snackbar; Änderungen lassen sich rückgängig machen. */
@Composable
internal fun PlanMessages(viewModel: PlanViewModel, snackbar: SnackbarHostState, resources: android.content.res.Resources) {
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val undoable = when (message) {
                is PlanMessage.WeekCopied -> message.batch.takeIf { it.changed > 0 }
                is PlanMessage.PatternApplied -> message.batch.takeIf { it.changed > 0 }
                is PlanMessage.Changed -> message.batch.takeIf { it.changed > 0 }
                is PlanMessage.Painted -> message.batch.takeIf { it.changed > 0 }
                is PlanMessage.SuggestionApplied -> message.batch.takeIf { it.changed > 0 }
                else -> null
            }
            val text = when (message) {
                is PlanMessage.WeekCopied ->
                    if (message.batch.changed == 0) {
                        resources.getString(R.string.copy_week_nothing)
                    } else {
                        resources.getString(R.string.copy_week_done, WeekFormat.weekLabel(message.target), message.batch.changed)
                    }
                is PlanMessage.PatternApplied -> resources.getString(R.string.pattern_applied, message.batch.changed)
                is PlanMessage.Changed -> resources.getString(
                    when (message.kind) {
                        ChangeKind.SHIFT -> R.string.changed_shift
                        ChangeKind.WISH -> R.string.changed_wish
                        ChangeKind.NOTE -> R.string.changed_note
                        ChangeKind.MEMBER_DELETED -> R.string.changed_member_deleted
                        ChangeKind.CLAIMED -> R.string.changed_claimed
                        ChangeKind.OFFERED -> R.string.changed_offered
                    },
                )
                is PlanMessage.Painted -> resources.getQuantityString(R.plurals.painted, message.batch.changed, message.batch.changed)
                is PlanMessage.SuggestionApplied -> resources.getQuantityString(R.plurals.suggest_applied, message.batch.changed, message.batch.changed)
                is PlanMessage.Undone -> resources.getString(R.string.undo_done)
                is PlanMessage.Discarded -> resources.getQuantityString(R.plurals.discarded_changes, message.count, message.count)
                is PlanMessage.LockChanged -> resources.getString(if (message.locked) R.string.lock_locked_done else R.string.lock_opened_done)
                is PlanMessage.Trade -> resources.getString(if (message.outcome == TradeOutcome.DONE) R.string.trade_done else R.string.trade_awaiting_admin)
                is PlanMessage.TradeFailed -> resources.getString(
                    when (message.problem) {
                        TradeProblem.STALE -> R.string.trade_failed_stale
                        TradeProblem.SELF -> R.string.trade_failed_self
                        TradeProblem.BUSY -> R.string.trade_failed_busy
                    },
                )
                PlanMessage.SwapProposed -> resources.getString(R.string.swap_proposed)
                PlanMessage.SwapDeclined -> resources.getString(R.string.swap_declined)
                PlanMessage.TradeNotAllowed -> resources.getString(R.string.trade_not_allowed)
                PlanMessage.Locked -> resources.getString(R.string.error_locked)
                PlanMessage.WeekLocked -> resources.getString(R.string.copy_week_locked)
                PlanMessage.WishNotAllowed -> resources.getString(R.string.error_wish_not_allowed)
                is PlanMessage.TeamFailed -> resources.getString(message.reason.messageRes())
                PlanMessage.Failed -> resources.getString(R.string.error_generic)
            }
            if (undoable != null) {
                val result = snackbar.showSnackbar(
                    message = text,
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Long,
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(undoable)
            } else {
                snackbar.showSnackbar(text)
            }
        }
    }
}

/** Leiste für „Schnell eintragen“: Schichtart wählen, dann Felder antippen oder über eine Zeile wischen. */
@Composable
private fun BrushBar(
    types: ShiftTypeSet,
    selected: String?,
    canUndo: Boolean,
    onSelect: (String?) -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_brush), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.brush_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.brush_undo))
                }
                TextButton(onClick = onDone) { Text(stringResource(R.string.brush_done)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (type in types.active) {
                    val isSelected = selected == type.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .selectable(selected = isSelected, onClick = { onSelect(type.id) }, role = Role.RadioButton)
                            .padding(4.dp),
                    ) {
                        ShiftBadge(type = type, typeId = type.id, size = 40.dp, modifier = Modifier.semantics { contentDescription = type.name })
                    }
                }
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    label = { Text(stringResource(R.string.brush_eraser)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
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

/** Hinzufügen, Umbenennen, Löschen, Pensum und „Das bin ich“ für Personen. */
@Composable
internal fun MemberDialogs(
    viewModel: PlanViewModel,
    showAdd: Boolean,
    onAddClosed: () -> Unit,
    editMember: Member?,
    onEditClosed: () -> Unit,
    myMemberId: String?,
    canDelete: Boolean,
    canEditPensum: Boolean = false,
    pensumOf: (String) -> Int? = { null },
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
            onDelete = if (canDelete) {
                {
                    viewModel.deleteMember(member.id)
                    onEditClosed()
                }
            } else {
                null
            },
            onDismiss = onEditClosed,
            isMe = member.id == myMemberId,
            onToggleMe = {
                viewModel.setMe(if (member.id == myMemberId) null else member.id)
                onEditClosed()
            },
            pensum = pensumOf(member.id),
            onPensum = if (canEditPensum) {
                { value -> viewModel.setPensum(member.id, value) }
            } else {
                null
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
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            val working = summary.groups.filter { (type, _) -> type.first?.countsForCoverage == true }
            if (working.isEmpty() && summary.missing.isEmpty()) {
                Text(
                    stringResource(R.string.today_nobody),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((type, members) in working) {
                        val target = summary.targets[type.second] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ShiftBadge(type = type.first, typeId = type.second, size = 22.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (target > 0) "${members.size}/$target" else members.size.toString(),
                                style = MaterialTheme.typography.labelLarge.tabular(),
                                color = if (members.size < target) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    // Schichten mit Soll, in denen noch niemand eingeteilt ist.
                    for ((type, missing) in summary.missing.filter { (type, _) -> working.none { it.first.second == type.id } }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ShiftBadge(type = type, typeId = type.id, size = 22.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("0/$missing", style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
