package ch.digitana.dienstplan.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Names

/** Eingabe eines Namens (max. 40 Zeichen) mit Zähler und Fehlermeldung. */
@Composable
fun MemberNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    nameProblem: (String) -> NameProblem?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extraContent: @Composable () -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf(initialName) }
    val problem = nameProblem(text)
    // Solange das Feld leer ist, nur den Zähler zeigen – noch keine Fehlermeldung.
    val visibleProblem = problem.takeIf { text.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    // Harte Obergrenze gegen versehentlich eingefügte Romane; geprüft wird auf 40.
                    onValueChange = { text = it.take(MAX_INPUT_CHARS) },
                    label = { Text(stringResource(R.string.member_name_label)) },
                    singleLine = true,
                    isError = visibleProblem != null,
                    supportingText = {
                        Text(
                            if (visibleProblem != null) {
                                nameProblemText(visibleProblem)
                            } else {
                                stringResource(
                                    R.string.member_name_counter,
                                    Names.length(Names.normalizeInput(text)),
                                    Limits.MAX_NAME_LENGTH_LOCAL,
                                )
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (problem == null) onConfirm(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                extraContent()
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = problem == null) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Umbenennen oder Löschen (mit Rückfrage). */
@Composable
fun EditMemberDialog(
    member: Member,
    nameProblem: (String) -> NameProblem?,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by rememberSaveable(member.id) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.member_delete_title, member.name)) },
            text = { Text(stringResource(R.string.member_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        return
    }
    MemberNameDialog(
        title = stringResource(R.string.member_edit_title),
        initialName = member.name,
        confirmLabel = stringResource(R.string.action_save),
        nameProblem = nameProblem,
        onConfirm = onRename,
        onDismiss = onDismiss,
        extraContent = {
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
    )
}

@Composable
internal fun nameProblemText(problem: NameProblem): String = when (problem) {
    NameProblem.EMPTY -> stringResource(R.string.member_error_empty)
    NameProblem.TOO_LONG -> stringResource(R.string.member_error_too_long, Limits.MAX_NAME_LENGTH_LOCAL)
    NameProblem.INVALID_CHARACTERS -> stringResource(R.string.member_error_invalid)
}

private const val MAX_INPUT_CHARS = 200
