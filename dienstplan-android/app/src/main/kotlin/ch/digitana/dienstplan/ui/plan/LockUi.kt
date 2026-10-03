package ch.digitana.dienstplan.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanLock
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.Format
import java.time.LocalDate
import java.time.YearMonth

/** Letzter gesperrter Tag, z. B. „So 31. Oktober“. */
internal fun lockDateLabel(date: LocalDate): String = "${WeekFormat.weekday(date)} ${Format.dayMonth(date)}"

/** „Gesperrt bis …“ oder „Plan gesperrt“. */
@Composable
internal fun lockTitle(lock: PlanLock): String {
    val until = lock.until
    return if (until == null) stringResource(R.string.lock_banner_whole) else stringResource(R.string.lock_banner_until, lockDateLabel(until))
}

/** Hinweis über dem Plan, solange angezeigte Tage gesperrt sind. [onManage]: nur für Admins. */
@Composable
internal fun LockBanner(lock: PlanLock, isAdmin: Boolean, onManage: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(lockTitle(lock), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    stringResource(if (isAdmin) R.string.lock_banner_admin else R.string.lock_banner_member),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (onManage != null) {
                TextButton(onClick = onManage) { Text(stringResource(R.string.lock_banner_manage)) }
            }
        }
    }
}

private class LockOption(val label: String, val until: LocalDate?)

/**
 * Sperren oder öffnen (nur Admins): bis Ende dieser oder nächster Woche, Ende dieses oder
 * nächsten Monats oder der ganze Plan. Öffnen fragt noch einmal nach.
 */
@Composable
internal fun LockDialog(
    current: PlanLock?,
    today: LocalDate,
    onLock: (LocalDate?) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val week = WeekId.of(today)
    val month = YearMonth.from(today)
    val options = listOf(
        LockOption("${stringResource(R.string.lock_option_week)} (${lockDateLabel(week.sunday)})", week.sunday),
        LockOption("${stringResource(R.string.lock_option_next_week)} (${lockDateLabel(week.next().sunday)})", week.next().sunday),
        LockOption(stringResource(R.string.lock_option_month, Format.monthLabel(month)), month.atEndOfMonth()),
        LockOption(stringResource(R.string.lock_option_month, Format.monthLabel(month.plusMonths(1))), month.plusMonths(1).atEndOfMonth()),
        LockOption(stringResource(R.string.lock_option_whole), null),
    ).filter { option -> option.until.let { it == null || PlanKeys.isValidDate(it) } }
    val initial = options.indexOfFirst { current != null && it.until == current.until }.takeIf { it >= 0 }
        ?: options.indexOfFirst { it.until == month.atEndOfMonth() }.coerceAtLeast(0)
    var selected by remember(current) { mutableIntStateOf(initial) }
    var confirmOpen by remember { mutableStateOf(false) }

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            icon = { Icon(painterResource(R.drawable.ic_lock_open), contentDescription = null) },
            title = { Text(stringResource(R.string.lock_open_confirm_title)) },
            text = { Text(stringResource(R.string.lock_open_confirm_text)) },
            confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.lock_action_open)) } },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
        title = { Text(stringResource(R.string.lock_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                current?.let {
                    Text(lockTitle(it), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.lock_dialog_text), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = index == selected, onClick = { selected = index }, role = Role.RadioButton)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = index == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Text(
                    stringResource(R.string.lock_rules),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { options.getOrNull(selected)?.let { onLock(it.until) } }) {
                Text(stringResource(if (current == null) R.string.lock_action_lock else R.string.lock_action_change))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { confirmOpen = true }) { Text(stringResource(R.string.lock_action_open)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
