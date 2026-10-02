package ch.digitana.dienstplan.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.export.ExportFormat
import ch.digitana.dienstplan.export.PlanExport
import ch.digitana.dienstplan.ui.diagnostics.DiagnosticsScreen
import ch.digitana.dienstplan.ui.me.MyShiftsScreen
import ch.digitana.dienstplan.ui.month.MonthScreen
import ch.digitana.dienstplan.ui.patterns.PatternsScreen
import ch.digitana.dienstplan.ui.plan.PlanScreen
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.team.JoiningScreen
import ch.digitana.dienstplan.ui.team.OnboardingScreen
import ch.digitana.dienstplan.ui.team.TeamScreen
import ch.digitana.dienstplan.ui.team.TeamViewModel
import ch.digitana.dienstplan.ui.types.ShiftTypesScreen
import ch.digitana.dienstplan.util.copyToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Reiter der unteren Leiste. */
private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    WEEK(R.string.tab_week, R.drawable.ic_view_week),
    MONTH(R.string.tab_month, R.drawable.ic_calendar_month),
    ME(R.string.tab_me, R.drawable.ic_person),
    TEAM(R.string.tab_team, R.drawable.ic_group),
}

/** Seiten, die über den Reitern liegen (mit Zurück-Pfeil, ohne untere Leiste). */
private enum class Page { SHIFT_TYPES, PATTERNS, DIAGNOSTICS }

/** Was geteilt werden soll, bevor das Format gewählt ist. */
private sealed interface ShareRequest {
    data class Week(val week: WeekId) : ShareRequest
    data class Month(val month: YearMonth) : ShareRequest
}

@Composable
fun DienstplanRoot(container: AppContainer) {
    val mainViewModel: MainViewModel = viewModel { MainViewModel(container) }
    val state by mainViewModel.state.collectAsStateWithLifecycle()
    val crashReport by mainViewModel.crashReport.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        val team = state.team
        when {
            state.loading -> LoadingScreen()
            team is TeamState.None -> OnboardingScreen(viewModel { TeamViewModel(container) })
            team is TeamState.Joining -> JoiningScreen(viewModel { TeamViewModel(container) }, team)
            else -> MainNavigation(container, startWithTeam = mainViewModel::consumeJustCreatedTeam)
        }
    }

    if (state.upgraded && !state.loading) {
        AlertDialog(
            onDismissRequest = mainViewModel::acknowledgeUpgrade,
            title = { Text(stringResource(R.string.upgrade_title)) },
            text = { Text(stringResource(R.string.upgrade_text)) },
            confirmButton = {
                TextButton(onClick = mainViewModel::acknowledgeUpgrade) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    if (state.storageReset) {
        AlertDialog(
            onDismissRequest = mainViewModel::acknowledgeStorageReset,
            title = { Text(stringResource(R.string.storage_reset_title)) },
            text = { Text(stringResource(R.string.storage_reset_text)) },
            confirmButton = {
                TextButton(onClick = mainViewModel::acknowledgeStorageReset) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    crashReport?.let { report ->
        val context = LocalContext.current
        val label = stringResource(R.string.crash_clipboard_label)
        AlertDialog(
            onDismissRequest = mainViewModel::dismissCrashReport,
            title = { Text(stringResource(R.string.crash_title)) },
            text = { Text(stringResource(R.string.crash_text)) },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, label, report, sensitive = false)
                    mainViewModel.dismissCrashReport()
                }) { Text(stringResource(R.string.crash_copy)) }
            },
            dismissButton = {
                TextButton(onClick = mainViewModel::dismissCrashReport) { Text(stringResource(R.string.crash_discard)) }
            },
        )
    }
}

@Composable
private fun MainNavigation(container: AppContainer, startWithTeam: () -> Boolean) {
    // Nach „Neues Team“ zuerst die Teamseite zeigen (Geräte hinzufügen) – mit einmaligem Hinweis.
    var createdHintPending by remember { mutableStateOf(startWithTeam()) }
    var tab by rememberSaveable { mutableStateOf(if (createdHintPending) Tab.TEAM else Tab.WEEK) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var share by remember { mutableStateOf<ShareRequest?>(null) }
    val planViewModel: PlanViewModel = viewModel { PlanViewModel(container) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler(enabled = page != null || tab != Tab.WEEK) {
        if (page != null) page = null else tab = Tab.WEEK
    }

    fun export(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.logger.warn("Export", "Export fehlgeschlagen", e)
                Toast.makeText(context, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    when (page) {
        Page.SHIFT_TYPES -> ShiftTypesScreen(planViewModel, onBack = { page = null })
        Page.PATTERNS -> PatternsScreen(planViewModel, onBack = { page = null })
        Page.DIAGNOSTICS -> DiagnosticsScreen(container = container, onBack = { page = null })
        null -> Scaffold(
            // Die Abstände für Status- und Navigationsleiste setzen die Reiter und die untere Leiste selbst.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    for (item in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (tab) {
                    Tab.WEEK -> PlanScreen(
                        viewModel = planViewModel,
                        onOpenDiagnostics = { page = Page.DIAGNOSTICS },
                        onOpenShiftTypes = { page = Page.SHIFT_TYPES },
                        onOpenPatterns = { page = Page.PATTERNS },
                        onShareWeek = { share = ShareRequest.Week(it) },
                    )
                    Tab.MONTH -> MonthScreen(
                        viewModel = planViewModel,
                        onOpenDiagnostics = { page = Page.DIAGNOSTICS },
                        onShareMonth = { share = ShareRequest.Month(it) },
                    )
                    Tab.ME -> MyShiftsScreen(
                        viewModel = planViewModel,
                        onExportCalendar = { memberId ->
                            val state = planViewModel.uiState.value
                            export { PlanExport.shareCalendar(context, state.plan, memberId, state.today) }
                        },
                    )
                    Tab.TEAM -> {
                        val teamViewModel: TeamViewModel = viewModel { TeamViewModel(container) }
                        TeamScreen(
                            viewModel = teamViewModel,
                            onBack = null,
                            showCreatedHint = createdHintPending,
                            onCreatedHintShown = { createdHintPending = false },
                            onOpenShiftTypes = { page = Page.SHIFT_TYPES },
                            onOpenPatterns = { page = Page.PATTERNS },
                            onOpenDiagnostics = { page = Page.DIAGNOSTICS },
                        )
                    }
                }
            }
        }
    }

    share?.let { request ->
        ShareDialog(
            onFormat = { format ->
                share = null
                val state = planViewModel.uiState.value
                export {
                    when (request) {
                        is ShareRequest.Week -> PlanExport.shareWeek(context, state.plan, request.week, state.today, state.teamName, format)
                        is ShareRequest.Month -> PlanExport.shareMonth(context, state.plan, request.month, state.today, state.teamName, format)
                    }
                }
            },
            onDismiss = { share = null },
        )
    }
}

/** Format wählen; geteilte Dateien sind nicht verschlüsselt. */
@Composable
private fun ShareDialog(onFormat: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.share_unencrypted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ShareOption(R.drawable.ic_description, R.string.share_pdf) { onFormat(ExportFormat.PDF) }
                ShareOption(R.drawable.ic_image, R.string.share_image) { onFormat(ExportFormat.PNG) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ShareOption(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
    }
}
