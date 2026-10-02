package ch.digitana.dienstplan.ui.team

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.plan.nameProblemText

/**
 * Nach dem Beitritt oder Gründen einmal fragen, wer dieses Gerät benutzt („Das bin ich“). Die
 * Antwort ordnet Dienste, Wünsche, Erinnerungen und Tausch der richtigen Person zu.
 */
@Composable
fun WhoAmIPrompt(viewModel: TeamViewModel) {
    val team by viewModel.teamState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    if (team !is TeamState.Member || settings.myMemberId != null || settings.askedWho) return

    var name by rememberSaveable { mutableStateOf("") }
    val problem = viewModel.nameProblem(name)
    AlertDialog(
        onDismissRequest = viewModel::skipWhoAmI,
        icon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null) },
        title = { Text(stringResource(R.string.who_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.who_text), style = MaterialTheme.typography.bodyMedium)
                if (members.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        for (member in members) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.chooseMe(member.id) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MemberAvatar(member.name, member.id, size = 32.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(member.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.who_new_title), style = MaterialTheme.typography.titleSmall)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_INPUT) },
                    label = { Text(stringResource(R.string.who_new_label)) },
                    singleLine = true,
                    isError = name.isNotEmpty() && problem != null,
                    supportingText = if (name.isNotEmpty() && problem != null) {
                        { Text(nameProblemText(problem)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.createMe(name) }, enabled = name.isNotBlank() && problem == null) {
                Text(stringResource(R.string.who_new_action))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::skipWhoAmI) { Text(stringResource(R.string.who_later)) }
        },
    )
}

private const val MAX_INPUT = 200
