package ch.digitana.dienstplan.ui.team

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.data.DeviceSettings
import ch.digitana.dienstplan.util.findActivity

/** „Das bin ich“ und der Schalter für Benachrichtigungen bei Änderungen der eigenen Dienste. */
@Composable
fun NotificationSection(
    members: List<Member>,
    settings: DeviceSettings,
    onSelectMember: (String?) -> Unit,
    onNotifyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    var menuOpen by remember { mutableStateOf(false) }
    // Nach der Rückkehr aus den Systemeinstellungen neu prüfen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = notificationsAllowed(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = notificationsAllowed(context)
        if (granted) onNotifyChange(true)
    }
    val me = members.firstOrNull { it.id == settings.myMemberId }

    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.notify_section_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notify_section_text), style = MaterialTheme.typography.bodySmall)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.notifyOnChanges,
                        enabled = me != null,
                        role = Role.Switch,
                        onValueChange = { wanted ->
                            if (wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionGranted(context)) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onNotifyChange(wanted)
                            }
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.notify_switch),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(checked = settings.notifyOnChanges, onCheckedChange = null, enabled = me != null)
            }
            if (settings.notifyOnChanges && !allowed) {
                Text(
                    stringResource(R.string.notify_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { openNotificationSettings(context) }) {
                    Text(stringResource(R.string.notify_open_settings))
                }
            }
        }
    }
}

private fun permissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun notificationsAllowed(context: Context): Boolean =
    permissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
