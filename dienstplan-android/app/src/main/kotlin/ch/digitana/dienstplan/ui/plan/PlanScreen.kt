package ch.digitana.dienstplan.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.SyncStatusChip
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    onOpenTeam: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var showAddMember by rememberSaveable { mutableStateOf(false) }
    var editMember by remember { mutableStateOf<Member?>(null) }
    var confirmCopy by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.refreshToday()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is PlanMessage.WeekCopied ->
                    if (message.changedFields == 0) {
                        resources.getString(R.string.copy_week_nothing)
                    } else {
                        resources.getString(R.string.copy_week_done, WeekFormat.weekLabel(message.target), message.changedFields)
                    }
                PlanMessage.Failed -> resources.getString(R.string.error_generic)
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    SyncStatusChip(status = state.sync, onClick = onOpenDiagnostics)
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_copy_week)) },
                                onClick = {
                                    menuOpen = false
                                    if (viewModel.nextWeekHasEntries()) confirmCopy = true else viewModel.copyWeekToNext()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_team)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenTeam()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_diagnostics)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenDiagnostics()
                                },
                            )
                        }
                    }
                },
            )
        },
        // Untere Leiste statt schwebendem Knopf: So verdeckt nichts die Besetzung am Sonntag.
        bottomBar = {
            BottomAppBar {
                Text(
                    text = stringResource(R.string.legend),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                FilledTonalButton(onClick = { showAddMember = true }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_add_member))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            WeekHeader(
                weekLabel = WeekFormat.weekLabel(state.week.week),
                rangeLabel = WeekFormat.rangeLabel(state.week.week),
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                onPrevious = viewModel::previousWeek,
                onNext = viewModel::nextWeek,
                onToday = viewModel::goToToday,
            )
            WeekGrid(
                model = state.week,
                onCellClick = viewModel::cycleShift,
                onCellLongClick = viewModel::clearShift,
                onMemberClick = { editMember = it },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showAddMember) {
        MemberNameDialog(
            title = stringResource(R.string.member_add_title),
            initialName = "",
            confirmLabel = stringResource(R.string.action_add),
            nameProblem = viewModel::nameProblem,
            onConfirm = { name ->
                viewModel.addMember(name)
                showAddMember = false
            },
            onDismiss = { showAddMember = false },
        )
    }

    editMember?.let { member ->
        EditMemberDialog(
            member = member,
            nameProblem = viewModel::nameProblem,
            onRename = { name ->
                viewModel.renameMember(member.id, name)
                editMember = null
            },
            onDelete = {
                viewModel.deleteMember(member.id)
                editMember = null
            },
            onDismiss = { editMember = null },
        )
    }

    if (confirmCopy) {
        AlertDialog(
            onDismissRequest = { confirmCopy = false },
            title = { Text(stringResource(R.string.copy_week_title)) },
            text = { Text(stringResource(R.string.copy_week_text, WeekFormat.weekLabel(viewModel.nextWeekId()))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCopy = false
                    viewModel.copyWeekToNext()
                }) { Text(stringResource(R.string.copy_week_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCopy = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun WeekHeader(
    weekLabel: String,
    rangeLabel: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = canGoBack) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.week_previous))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = stringResource(R.string.week_today_hint), onClick = onToday)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(weekLabel, style = MaterialTheme.typography.titleMedium)
            Text(rangeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.week_next))
        }
    }
}
