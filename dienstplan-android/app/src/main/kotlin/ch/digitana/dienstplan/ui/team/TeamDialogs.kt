package ch.digitana.dienstplan.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.group.Fingerprint
import ch.digitana.dienstplan.core.group.Invite
import ch.digitana.dienstplan.ui.plan.nameProblemText

/** Ein Namensfeld mit Hinweis oder Fehlermeldung (erst, wenn etwas eingegeben ist). */
@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String?,
    problem: NameProblem?,
) {
    val visibleProblem = problem.takeIf { value.isNotEmpty() }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_INPUT_CHARS)) },
        label = { Text(label) },
        singleLine = true,
        isError = visibleProblem != null,
        supportingText = when {
            visibleProblem != null -> {
                { Text(nameProblemText(visibleProblem)) }
            }
            hint != null -> {
                { Text(hint) }
            }
            else -> null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** „Neues Team“: Name des Teams und (freiwillig) der eigene Name für die Geräteliste. */
@Composable
fun CreateTeamDialog(
    nameProblem: (String) -> NameProblem?,
    labelProblem: (String) -> NameProblem?,
    onConfirm: (name: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    val nameError = nameProblem(name)
    val labelError = labelProblem(label)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_team_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NameField(name, { name = it }, stringResource(R.string.create_team_name), stringResource(R.string.create_team_name_hint), nameError)
                NameField(label, { label = it }, stringResource(R.string.device_label_own), stringResource(R.string.device_label_hint), labelError)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, label) }, enabled = nameError == null && labelError == null) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Eine eingegangene Einladung: Team, einladendes Gerät (Fingerabdruck) und Anzahl Geräte.
 * Liegt schon ein Plan auf dem Gerät, wird gefragt, ob er übernommen werden soll.
 */
@Composable
fun InviteDialog(
    invite: Invite,
    hasLocalPlan: Boolean,
    labelProblem: (String) -> NameProblem?,
    enabled: Boolean,
    onAccept: (keepLocalData: Boolean, label: String) -> Unit,
    onDecline: () -> Unit,
) {
    var keep by rememberSaveable(invite.id) { mutableStateOf(false) }
    var label by rememberSaveable(invite.id) { mutableStateOf("") }
    val labelError = labelProblem(label)
    AlertDialog(
        // Nicht versehentlich wegtippen: Annehmen oder Ablehnen.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.invite_title, invite.teamName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.invite_text, Fingerprint.of(invite.inviter), invite.memberCount))
                NameField(label, { label = it }, stringResource(R.string.device_label_own), stringResource(R.string.device_label_hint), labelError)
                if (hasLocalPlan) {
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = keep, role = Role.Checkbox, onValueChange = { keep = it }),
                    ) {
                        Checkbox(checked = keep, onCheckedChange = null)
                        Text(stringResource(R.string.invite_keep), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        stringResource(R.string.invite_keep_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAccept(keep, label) }, enabled = enabled && labelError == null) {
                Text(stringResource(R.string.action_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, enabled = enabled) { Text(stringResource(R.string.invite_decline)) }
        },
    )
}

/** Gerät hinzufügen: Beitrittscode des neuen Geräts und (freiwillig) sein Name. */
@Composable
fun AddDeviceDialog(
    checkCode: (String) -> JoinCodeCheck,
    labelProblem: (String) -> NameProblem?,
    onConfirm: (publicKey: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var codeError by rememberSaveable { mutableStateOf<Int?>(null) }
    val labelError = labelProblem(label)
    fun submit() {
        when (val check = checkCode(code)) {
            is JoinCodeCheck.Error -> codeError = check.message
            is JoinCodeCheck.Valid -> onConfirm(check.publicKey, label)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_add_text), style = MaterialTheme.typography.bodySmall)
                val error = codeError
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.take(MAX_CODE_INPUT)
                        codeError = null
                    },
                    label = { Text(stringResource(R.string.device_add_code_label)) },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(stringResource(error)) }
                    } else {
                        null
                    },
                    maxLines = 3,
                    // Keine Vorschläge, kein Lernen durch die Tastatur.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                NameField(label, { label = it }, stringResource(R.string.device_label_other), null, labelError)
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = code.isNotBlank() && labelError == null) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Aktionen für ein Gerät: umbenennen und (für Admins) Admin-Rechte oder Entfernen. */
@Composable
fun DeviceActionsDialog(
    device: DeviceItem,
    canManage: Boolean,
    onRename: () -> Unit,
    onToggleAdmin: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.label ?: stringResource(R.string.device_unnamed)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.fingerprint_label, device.fingerprint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRename) { Text(stringResource(R.string.device_rename)) }
                if (canManage) {
                    TextButton(onClick = onToggleAdmin) {
                        Text(stringResource(if (device.isAdmin) R.string.device_revoke_admin else R.string.device_make_admin))
                    }
                    if (!device.isMe) {
                        TextButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(stringResource(R.string.device_remove)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Gerätename ändern; leer entfernt den Namen. */
@Composable
fun RenameDeviceDialog(
    initial: String,
    labelProblem: (String) -> NameProblem?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(initial) }
    val error = labelProblem(label)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_rename_title)) },
        text = { NameField(label, { label = it }, stringResource(R.string.member_name_label), null, error) },
        confirmButton = {
            TextButton(onClick = { onConfirm(label) }, enabled = error == null) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Rückfrage mit roter Bestätigung. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val MAX_INPUT_CHARS = 200
private const val MAX_CODE_INPUT = 500
