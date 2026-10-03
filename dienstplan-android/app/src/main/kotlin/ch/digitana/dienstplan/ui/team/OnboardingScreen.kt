package ch.digitana.dienstplan.ui.team

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.group.Fingerprint
import ch.digitana.dienstplan.core.group.JoinCode
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.ui.components.QrCode
import ch.digitana.dienstplan.ui.components.SecureWindow
import ch.digitana.dienstplan.util.copyToClipboard
import ch.digitana.dienstplan.util.shareText
import kotlinx.coroutines.launch

/** Start ohne Team: „Neues Team gründen“ oder „Einem Team beitreten“. */
@Composable
fun OnboardingScreen(viewModel: TeamViewModel) {
    SecureWindow()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var createOpen by rememberSaveable { mutableStateOf(false) }
    TeamScaffold(viewModel) {
        WelcomeHero()
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_text), style = MaterialTheme.typography.bodyMedium)
        Feature(R.drawable.ic_lock, R.string.onboarding_feature_private)
        Feature(R.drawable.ic_cloud, R.string.onboarding_feature_offline)
        Feature(R.drawable.ic_group, R.string.onboarding_feature_equal)
        Button(onClick = { createOpen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_new_team))
        }
        Hint(stringResource(R.string.onboarding_new_team_hint))
        HorizontalDivider()
        Text(stringResource(R.string.onboarding_join_title), style = MaterialTheme.typography.titleMedium)
        Hint(stringResource(R.string.onboarding_join_hint))
        OutlinedButton(onClick = viewModel::startJoining, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_start_join))
        }
    }
    if (createOpen) {
        CreateTeamDialog(
            nameProblem = viewModel::nameProblem,
            labelProblem = viewModel::labelProblem,
            onConfirm = { name, label ->
                createOpen = false
                viewModel.createTeam(name, label)
            },
            onDismiss = { createOpen = false },
        )
    }
}

/**
 * Beitritt läuft: Code teilen, auf die Einladung warten, Einladung bestätigen. Der Code ist
 * kein Geheimnis, wird aber trotzdem nur gekürzt angezeigt (kurz und gut vergleichbar).
 */
@Composable
fun JoiningScreen(viewModel: TeamViewModel, state: TeamState.Joining) {
    SecureWindow()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.join_share_title)
    val shareMessage = stringResource(R.string.join_share_text, state.code)
    val clipLabel = stringResource(R.string.join_clipboard_label)
    val copied = stringResource(R.string.copied)
    TeamScaffold(viewModel) { snackbar ->
        val scope = rememberCoroutineScope()
        Text(stringResource(R.string.join_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.join_steps), style = MaterialTheme.typography.bodyMedium)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.join_code_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.join_qr_hint), style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCode(
                        text = state.code,
                        description = stringResource(R.string.join_qr_description),
                        modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(0.8f),
                    )
                }
                Text(JoinCode.abbreviate(state.code), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text(
                    stringResource(R.string.fingerprint_label, Fingerprint.of(state.publicKey)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.join_code_text), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { shareText(context, shareTitle, shareMessage) }) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share))
                    }
                    OutlinedButton(onClick = {
                        copyToClipboard(context, clipLabel, state.code, sensitive = false)
                        // Ab Android 13 bestätigt das System das Kopieren selbst.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                    }) {
                        Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_copy))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(if (state.published) R.string.join_status_waiting else R.string.join_status_publishing),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = viewModel::cancelJoining, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.join_cancel))
        }
    }

    state.invites.firstOrNull()?.let { invite ->
        InviteDialog(
            invite = invite,
            hasLocalPlan = viewModel.hasLocalPlan(),
            labelProblem = viewModel::labelProblem,
            enabled = !busy,
            onAccept = { keep, label -> viewModel.acceptInvite(invite.id, keep, label) },
            onDecline = { viewModel.declineInvite(invite.id) },
        )
    }
}

/** Gerüst der Teamseiten ohne Team: scrollbar, Fehlermeldungen als Snackbar. */
@Composable
private fun TeamScaffold(viewModel: TeamViewModel, content: @Composable (SnackbarHostState) -> Unit) {
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is TeamEvent.Failed) snackbar.showSnackbar(resources.getString(event.message))
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content(snackbar)
        }
    }
}

/** Kopfbild des Einstiegs: Wochensymbol auf einem Farbverlauf. */
@Composable
private fun WelcomeHero() {
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(gradient), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_view_week),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

/** Ein Vorteil in einer Zeile, mit Symbol. */
@Composable
private fun Feature(icon: Int, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
