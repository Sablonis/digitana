package ch.digitana.dienstplan.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.data.DeviceSettings

/**
 * „Das bin ich“: welche Person dieses Gerät benutzt. Benachrichtigungen, Erinnerungen und der
 * Gerätekalender stehen in den Einstellungen.
 */
@Composable
fun NotificationSection(
    members: List<Member>,
    settings: DeviceSettings,
    onSelectMember: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val me = members.firstOrNull { it.id == settings.myMemberId }

    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.me_section_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.me_section_text), style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { menuOpen = true }, enabled = members.isNotEmpty()) {
                    Text(
                        if (me != null) {
                            stringResource(R.string.notify_me_label, me.name)
                        } else {
                            stringResource(R.string.notify_me_choose)
                        },
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (member in members) {
                        DropdownMenuItem(
                            text = { Text(member.name) },
                            onClick = {
                                menuOpen = false
                                onSelectMember(member.id)
                            },
                        )
                    }
                    if (me != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notify_me_nobody)) },
                            onClick = {
                                menuOpen = false
                                onSelectMember(null)
                            },
                        )
                    }
                }
            }
            if (members.isEmpty()) {
                Text(
                    stringResource(R.string.notify_no_members),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.me_section_settings)) }
        }
    }
}
