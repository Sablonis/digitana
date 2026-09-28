package ch.digitana.dienstplan.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crypto.TeamSecret

/**
 * Eingabe eines Einladungscodes. Tippfehler erkennt die Prüfsumme. Liegt auf dem Gerät
 * schon ein Plan, fragt die App, ob er übernommen oder verworfen werden soll.
 */
@Composable
fun JoinSection(viewModel: TeamViewModel, enabled: Boolean, modifier: Modifier = Modifier) {
    var code by rememberSaveable { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var pendingSecret by remember { mutableStateOf<TeamSecret?>(null) }

    fun submit() {
        when (val check = viewModel.checkCode(code)) {
            is JoinCheck.Error -> errorRes = check.message
            is JoinCheck.NeedsDecision -> pendingSecret = check.secret
            is JoinCheck.Ready -> {
                viewModel.join(check.secret, keepLocalData = false)
                code = ""
            }
        }
    }

    val error = errorRes
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it.take(MAX_CODE_INPUT)
                errorRes = null
            },
            label = { Text(stringResource(R.string.invite_code_label)) },
            isError = error != null,
            supportingText = if (error != null) {
                { Text(stringResource(error)) }
            } else {
                null
            },
            maxLines = 3,
            // Passwort-Tastatur: keine Vorschläge, kein Lernen des Codes durch die Tastatur.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = ::submit, enabled = enabled && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_join))
        }
    }

    pendingSecret?.let { secret ->
        AlertDialog(
            onDismissRequest = { pendingSecret = null },
            title = { Text(stringResource(R.string.join_keep_title)) },
            text = { Text(stringResource(R.string.join_keep_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingSecret = null
                    viewModel.join(secret, keepLocalData = true)
                    code = ""
                }) { Text(stringResource(R.string.join_keep)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingSecret = null
                    viewModel.join(secret, keepLocalData = false)
                    code = ""
                }) { Text(stringResource(R.string.join_discard)) }
            },
        )
    }
}

private const val MAX_CODE_INPUT = 500
