package ch.digitana.dienstplan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.sync.SyncStatus

/** „Live · 3/3 Relays“ – Tipp öffnet die Diagnose. [compact]: nur „Live“, „Offline“ … */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusChip(status: SyncStatus, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = syncStatusLabel(status)
    val shown = if (compact) compactSyncStatusLabel(status) else label
    val dot = when {
        status.liveRelays > 0 && status.pendingBuckets == 0 -> Color(0xFF2E7D32)
        status.liveRelays > 0 || status.connectingRelays > 0 -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.outline
    }
    val description = stringResource(R.string.sync_status_description, label)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(shown, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun syncStatusLabel(status: SyncStatus): String {
    val base = when {
        !status.running -> stringResource(R.string.sync_stopped)
        status.liveRelays > 0 -> stringResource(R.string.sync_live, status.liveRelays, status.totalRelays)
        status.connectingRelays > 0 -> stringResource(R.string.sync_connecting, status.liveRelays, status.totalRelays)
        else -> stringResource(R.string.sync_offline)
    }
    return if (status.liveRelays > 0 && status.pendingBuckets > 0) {
        base + " · " + stringResource(R.string.sync_pending, status.pendingBuckets)
    } else {
        base
    }
}

@Composable
fun compactSyncStatusLabel(status: SyncStatus): String = when {
    !status.running -> stringResource(R.string.sync_stopped_short)
    status.liveRelays > 0 && status.pendingBuckets > 0 -> stringResource(R.string.sync_pending_short)
    status.liveRelays > 0 -> stringResource(R.string.sync_live_short)
    status.connectingRelays > 0 -> stringResource(R.string.sync_connecting_short)
    else -> stringResource(R.string.sync_offline)
}
