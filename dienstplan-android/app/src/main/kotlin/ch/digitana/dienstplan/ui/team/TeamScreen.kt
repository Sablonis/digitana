package ch.digitana.dienstplan.ui.team

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crypto.InviteCode
import ch.digitana.dienstplan.ui.components.SecureWindow
import ch.digitana.dienstplan.util.copyToClipboard
import ch.digitana.dienstplan.util.shareText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    viewModel: TeamViewModel,
    onBack: () -> Unit,
    showCreatedHint: Boolean = false,
    onCreatedHintShown: () -> Unit = {},
) {
    SecureWindow()
    val team by viewModel.team.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    // Fester Schlüssel: Das Zurücksetzen des Hinweises darf die laufende Snackbar nicht abbrechen.
    LaunchedEffect(Unit) {
        if (showCreatedHint) {
            onCreatedHintShown()
            snackbar.showSnackbar(context.getString(R.string.team_created))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                TeamEvent.Created -> R.string.team_created
                TeamEvent.Rotated -> R.string.team_rotated
                TeamEvent.Joined -> R.string.joined
                TeamEvent.Failed -> R.string.error_generic
                TeamEvent.Left -> null
            }
            if (message != null) snackbar.showSnackbar(context.getString(message))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.team_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            team?.let { current ->
                val code = current.inviteCode
                val shareTitle = stringResource(R.string.share_title)
                val shareMessage = stringResource(R.string.share_text, code)
                val clipLabel = stringResource(R.string.clipboard_label)
                val copiedText = stringResource(R.string.copied_sensitive)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.team_invite_title), style = MaterialTheme.typography.titleMedium)
                        // Nur gekürzt anzeigen; der volle Code geht über Teilen oder Kopieren.
                        Text(
                            InviteCode.abbreviate(code),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(stringResource(R.string.team_invite_text), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = { shareText(context, shareTitle, shareMessage) }) {
                                Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_share))
                            }
                            OutlinedButton(onClick = {
                                copyToClipboard(context, clipLabel, code, sensitive = true)
                                // Ab Android 13 bestätigt das System das Kopieren selbst.
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    scope.launch { snackbar.showSnackbar(copiedText) }
                                }
                            }) {
                                Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_copy))
                            }
                        }
                    }
                }
            }

            Section(
                title = stringResource(R.string.team_rotate_title),
                text = stringResource(R.string.team_rotate_text),
            ) {
                OutlinedButton(onClick = { confirmRotate = true }, enabled = !busy) {
                    Text(stringResource(R.string.team_rotate_confirm))
                }
            }

            Section(
                title = stringResource(R.string.team_join_other_title),
                text = stringResource(R.string.team_join_other_text),
            ) {
                JoinSection(viewModel = viewModel, enabled = !busy)
            }

            Section(
                title = stringResource(R.string.team_leave_title),
                text = stringResource(R.string.team_leave_text),
            ) {
                OutlinedButton(
                    onClick = { confirmLeave = true },
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.team_leave_title)) }
            }

            Section(
                title = stringResource(R.string.team_limits_title),
                text = stringResource(R.string.team_limits_text),
            )

            team?.let {
                Text(
                    stringResource(R.string.team_device_id, it.deviceId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.team_rotate_confirm_title)) },
            text = { Text(stringResource(R.string.team_rotate_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    viewModel.rotate()
                }) { Text(stringResource(R.string.team_rotate_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.team_leave_confirm_title)) },
            text = { Text(stringResource(R.string.team_leave_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        viewModel.leave()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.team_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Section(title: String, text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}
