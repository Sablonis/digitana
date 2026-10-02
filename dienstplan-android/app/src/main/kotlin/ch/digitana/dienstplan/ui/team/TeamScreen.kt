package ch.digitana.dienstplan.ui.team

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.group.Fingerprint
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.ui.components.SecureWindow

/** Team und Geräte: Geräteliste mit Admin-Aktionen, Benachrichtigungen, Austritt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    viewModel: TeamViewModel,
    /** null: als Reiter ohne Zurück-Pfeil. */
    onBack: (() -> Unit)?,
    showCreatedHint: Boolean = false,
    onCreatedHintShown: () -> Unit = {},
    onOpenShiftTypes: () -> Unit = {},
    onOpenPatterns: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    SecureWindow()
    val state by viewModel.teamState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val group by viewModel.groupDiagnostics.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DeviceItem?>(null) }
    var renaming by remember { mutableStateOf<DeviceItem?>(null) }
    var removing by remember { mutableStateOf<DeviceItem?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var offerLocalDelete by remember { mutableStateOf(false) }
    val member = state as? TeamState.Member

    // Fester Schlüssel: Das Zurücksetzen des Hinweises darf die laufende Snackbar nicht abbrechen.
    LaunchedEffect(Unit) {
        if (showCreatedHint) {
            onCreatedHintShown()
            snackbar.showSnackbar(resources.getString(R.string.team_created))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                TeamEvent.Created -> R.string.team_created
                TeamEvent.Joined -> R.string.joined
                TeamEvent.DeviceAdded -> R.string.device_added
                TeamEvent.DeviceRemoved -> R.string.device_removed
                TeamEvent.AdminChanged -> R.string.admin_changed
                is TeamEvent.Failed -> event.message
                TeamEvent.LeaveNotSent -> {
                    offerLocalDelete = true
                    null
                }
                TeamEvent.Left -> null
            }
            if (message != null) snackbar.showSnackbar(resources.getString(message))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(member?.team?.name?.ifBlank { null } ?: stringResource(R.string.team_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (group.forked) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.forked_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.forked_text), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { offerLocalDelete = true }, enabled = !busy) {
                            Text(stringResource(R.string.team_delete_local))
                        }
                    }
                }
            }

            member?.let { current ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.devices_title, devices.size), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (current.isAdmin) R.string.team_role_admin else R.string.team_role_member),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        devices.forEachIndexed { index, device ->
                            if (index > 0) HorizontalDivider()
                            DeviceRow(device, onClick = { selected = device })
                        }
                        if (current.isAdmin) {
                            FilledTonalButton(onClick = { addOpen = true }, enabled = !busy) {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.device_add))
                            }
                        } else {
                            Text(
                                stringResource(R.string.device_add_hint_member),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            PlanningSection(
                readOnly = member == null,
                onOpenShiftTypes = onOpenShiftTypes,
                onOpenPatterns = onOpenPatterns,
                onOpenDiagnostics = onOpenDiagnostics,
            )

            NotificationSection(
                members = members,
                settings = settings,
                onSelectMember = viewModel::setMyMember,
                onNotifyChange = viewModel::setNotifyOnChanges,
            )

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

            member?.let {
                Text(
                    stringResource(R.string.team_my_fingerprint, Fingerprint.of(it.me)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (addOpen) {
        AddDeviceDialog(
            checkCode = viewModel::checkJoinCode,
            labelProblem = viewModel::labelProblem,
            onConfirm = { publicKey, label ->
                addOpen = false
                viewModel.addDevice(publicKey, label)
            },
            onDismiss = { addOpen = false },
        )
    }

    selected?.let { device ->
        DeviceActionsDialog(
            device = device,
            canManage = member?.isAdmin == true,
            onRename = {
                selected = null
                renaming = device
            },
            onToggleAdmin = {
                selected = null
                viewModel.setAdmin(device.publicKey, !device.isAdmin)
            },
            onRemove = {
                selected = null
                removing = device
            },
            onDismiss = { selected = null },
        )
    }

    renaming?.let { device ->
        RenameDeviceDialog(
            initial = device.label.orEmpty(),
            labelProblem = viewModel::labelProblem,
            onConfirm = { label ->
                renaming = null
                viewModel.renameDevice(device.publicKey, label)
            },
            onDismiss = { renaming = null },
        )
    }

    removing?.let { device ->
        ConfirmDialog(
            title = stringResource(R.string.device_remove_title, device.label ?: device.fingerprint),
            text = stringResource(R.string.device_remove_text),
            confirmLabel = stringResource(R.string.device_remove),
            onConfirm = {
                removing = null
                viewModel.removeDevice(device.publicKey)
            },
            onDismiss = { removing = null },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.team_leave_confirm_title),
            text = stringResource(R.string.team_leave_confirm_text),
            confirmLabel = stringResource(R.string.team_leave_confirm),
            onConfirm = {
                confirmLeave = false
                viewModel.leave()
            },
            onDismiss = { confirmLeave = false },
        )
    }

    if (offerLocalDelete) {
        ConfirmDialog(
            title = stringResource(if (group.forked) R.string.forked_title else R.string.team_leave_offline_title),
            text = stringResource(if (group.forked) R.string.removed_delete_text else R.string.team_leave_offline_text),
            confirmLabel = stringResource(R.string.team_delete_local),
            onConfirm = {
                offerLocalDelete = false
                viewModel.deleteLocalData()
            },
            onDismiss = { offerLocalDelete = false },
        )
    }
}

@Composable
private fun DeviceRow(device: DeviceItem, onClick: () -> Unit) {
    val clickLabel = stringResource(R.string.device_click_label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.label ?: stringResource(R.string.device_unnamed),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (device.isMe) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(device.fingerprint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        if (device.isMe) SuggestionChip(onClick = onClick, label = { Text(stringResource(R.string.device_me)) })
        if (device.isAdmin) {
            Spacer(Modifier.width(6.dp))
            SuggestionChip(onClick = onClick, label = { Text(stringResource(R.string.device_admin)) })
        }
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

/** Einstellungen für die Planung und die Diagnose. */
@Composable
private fun PlanningSection(
    readOnly: Boolean,
    onOpenShiftTypes: () -> Unit,
    onOpenPatterns: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.team_planning_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (!readOnly) {
                PlanningRow(R.drawable.ic_palette, R.string.menu_shift_types, R.string.team_planning_types_hint, onOpenShiftTypes)
                PlanningRow(R.drawable.ic_repeat, R.string.menu_patterns, R.string.team_planning_patterns_hint, onOpenPatterns)
            }
            PlanningRow(R.drawable.ic_cloud, R.string.menu_diagnostics, R.string.team_planning_diagnostics_hint, onOpenDiagnostics)
        }
    }
}

@Composable
private fun PlanningRow(@DrawableRes icon: Int, @StringRes title: Int, @StringRes hint: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
