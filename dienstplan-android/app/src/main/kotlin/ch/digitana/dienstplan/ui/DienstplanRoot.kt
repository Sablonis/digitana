package ch.digitana.dienstplan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.ui.diagnostics.DiagnosticsScreen
import ch.digitana.dienstplan.ui.plan.PlanScreen
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.team.OnboardingScreen
import ch.digitana.dienstplan.ui.team.TeamScreen
import ch.digitana.dienstplan.ui.team.TeamViewModel
import ch.digitana.dienstplan.util.copyToClipboard

private enum class Screen { PLAN, TEAM, DIAGNOSTICS }

@Composable
fun DienstplanRoot(container: AppContainer) {
    val mainViewModel: MainViewModel = viewModel { MainViewModel(container) }
    val state by mainViewModel.state.collectAsStateWithLifecycle()
    val crashReport by mainViewModel.crashReport.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingScreen()
            !state.hasTeam -> {
                val teamViewModel: TeamViewModel = viewModel { TeamViewModel(container) }
                OnboardingScreen(teamViewModel)
            }
            else -> MainNavigation(container, startWithTeam = mainViewModel::consumeJustCreatedTeam)
        }
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
    // Nach „Neues Team“ zuerst den Einladungscode zeigen – mit einmaligem Hinweis.
    var createdHintPending by remember { mutableStateOf(startWithTeam()) }
    var screen by rememberSaveable { mutableStateOf(if (createdHintPending) Screen.TEAM else Screen.PLAN) }
    BackHandler(enabled = screen != Screen.PLAN) { screen = Screen.PLAN }
    when (screen) {
        Screen.PLAN -> {
            val planViewModel: PlanViewModel = viewModel { PlanViewModel(container) }
            PlanScreen(
                viewModel = planViewModel,
                onOpenTeam = { screen = Screen.TEAM },
                onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
            )
        }
        Screen.TEAM -> {
            val teamViewModel: TeamViewModel = viewModel { TeamViewModel(container) }
            TeamScreen(
                viewModel = teamViewModel,
                onBack = { screen = Screen.PLAN },
                showCreatedHint = createdHintPending,
                onCreatedHintShown = { createdHintPending = false },
            )
        }
        Screen.DIAGNOSTICS -> DiagnosticsScreen(container = container, onBack = { screen = Screen.PLAN })
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
