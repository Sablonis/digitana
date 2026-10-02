package ch.digitana.dienstplan.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.BuildConfig
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.group.Fingerprint
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.sync.RelayConnectionState
import ch.digitana.dienstplan.core.sync.RelayDiagnostics
import ch.digitana.dienstplan.ui.components.SyncStatusChip
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Status pro Relay: verbunden oder Fehlertext, letzte Synchronisierung, Zähler. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(container: AppContainer, onBack: () -> Unit) {
    val status by container.syncController.status.collectAsStateWithLifecycle()
    val relays by container.syncController.diagnostics.collectAsStateWithLifecycle()
    val team by container.teamRepository.state.collectAsStateWithLifecycle()
    val group by container.syncController.groupDiagnostics.collectAsStateWithLifecycle()
    val plan by container.planRepository.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncStatusChip(status = status, onClick = container.syncController::reconnectNow)
                    Text(
                        stringResource(R.string.diagnostics_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(relays, key = { it.url }) { relay -> RelayCard(relay, now) }
            item {
                Button(onClick = container.syncController::reconnectNow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostics_reconnect))
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.diagnostics_local), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.diagnostics_entries, plan.entryCount, plan.buckets.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        container.publicKey?.let { publicKey ->
                            Text(stringResource(R.string.fingerprint_label, Fingerprint.of(publicKey)), style = MaterialTheme.typography.bodySmall)
                        }
                        (team as? TeamState.Member)?.let { member ->
                            Text(
                                stringResource(R.string.diagnostics_epoch, member.team.epoch, member.team.members.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(R.string.diagnostics_group_counters, group.deferred, group.ignored, group.rollbacks, group.invalidMessages, group.lockedEntries),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (group.forked) {
                            Text(stringResource(R.string.forked_title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            stringResource(R.string.diagnostics_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayCard(relay: RelayDiagnostics, now: Long) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(relay.url.removePrefix("wss://"), style = MaterialTheme.typography.titleSmall)
            val (label, color) = when (relay.state) {
                RelayConnectionState.LIVE -> stringResource(R.string.relay_state_live) to Color(0xFF2E7D32)
                RelayConnectionState.SYNCING -> stringResource(R.string.relay_state_syncing) to Color(0xFFB26A00)
                RelayConnectionState.CONNECTING -> stringResource(R.string.relay_state_connecting) to Color(0xFFB26A00)
                RelayConnectionState.DISCONNECTED -> stringResource(R.string.relay_state_disconnected) to MaterialTheme.colorScheme.error
            }
            Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
            Text(lastSyncText(relay.lastSyncAt, now), style = MaterialTheme.typography.bodySmall)
            relay.lastError?.let {
                Text(
                    stringResource(R.string.relay_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (relay.state == RelayConnectionState.LIVE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            relay.lastNotice?.let {
                Text(stringResource(R.string.relay_notice, it), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.relay_counters, relay.eventsReceived, relay.eventsAccepted, relay.eventsRejected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.relay_counters_dropped, relay.undecryptable, relay.invalid, relay.droppedEntries),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun lastSyncText(lastSyncAt: Long?, now: Long): String {
    if (lastSyncAt == null) return stringResource(R.string.relay_last_sync_never)
    val time = Instant.ofEpochMilli(lastSyncAt).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
    val seconds = ((now - lastSyncAt) / 1000).coerceAtLeast(0)
    val formatted = if (seconds < 120) {
        stringResource(R.string.time_seconds_ago, time, seconds.toInt())
    } else {
        stringResource(R.string.time_minutes_ago, time, (seconds / 60).toInt())
    }
    return stringResource(R.string.relay_last_sync, formatted)
}
