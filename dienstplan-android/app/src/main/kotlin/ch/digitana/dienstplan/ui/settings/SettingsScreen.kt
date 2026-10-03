package ch.digitana.dienstplan.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.plan.ReminderMode
import ch.digitana.dienstplan.ui.GridDensity
import ch.digitana.dienstplan.ui.components.NavigationRow
import ch.digitana.dienstplan.ui.components.RadioRow
import ch.digitana.dienstplan.ui.components.SectionHeader
import ch.digitana.dienstplan.ui.components.SwitchRow
import ch.digitana.dienstplan.ui.components.hasCalendarPermission
import ch.digitana.dienstplan.ui.components.hasNotificationPermission
import ch.digitana.dienstplan.ui.components.notificationsEnabled
import ch.digitana.dienstplan.ui.components.openAppNotificationSettings
import ch.digitana.dienstplan.ui.theme.ThemeMode
import ch.digitana.dienstplan.ui.theme.dynamicColorAvailable

/** Einstellungen dieses Geräts. Sie gelten nur hier und werden nicht abgeglichen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    var calendarAllowed by remember { mutableStateOf(hasCalendarPermission(context)) }
    // Nach der Rückkehr aus den Systemeinstellungen neu prüfen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsOn = notificationsEnabled(context)
        calendarAllowed = hasCalendarPermission(context)
    }
    // Was nach der Rückfrage zur Berechtigung eingeschaltet werden soll.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOn = notificationsEnabled(context)
        if (granted) afterPermission?.invoke()
        afterPermission = null
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        calendarAllowed = hasCalendarPermission(context)
        if (result.values.all { it }) viewModel.setCalendarSync(true)
    }

    /** Schaltet etwas ein, das Benachrichtigungen braucht – bei Bedarf mit Rückfrage (Android 13+). */
    fun withNotifications(enable: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
            afterPermission = enable
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enable()
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                    val modes = listOf(
                        ThemeMode.SYSTEM to R.string.settings_theme_system,
                        ThemeMode.LIGHT to R.string.settings_theme_light,
                        ThemeMode.DARK to R.string.settings_theme_dark,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = ui.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                }
            }
            if (dynamicColorAvailable) {
                item {
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_hint),
                        checked = ui.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_density), style = MaterialTheme.typography.bodyLarge)
                    val densities = listOf(
                        GridDensity.COMFORTABLE to R.string.settings_density_times,
                        GridDensity.COMPACT to R.string.settings_density_compact,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        densities.forEachIndexed { index, (density, label) ->
                            SegmentedButton(
                                selected = ui.density == density,
                                onClick = { viewModel.setDensity(density) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = densities.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    Text(
                        stringResource(R.string.settings_density_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_notifications)) }
            if (me == null) {
                item { Hint(stringResource(R.string.settings_choose_me_first)) }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.notify_switch),
                    checked = settings.notifyOnChanges,
                    enabled = me != null,
                    icon = R.drawable.ic_notifications,
                    onCheckedChange = { wanted -> if (wanted) withNotifications { viewModel.setNotifyOnChanges(true) } else viewModel.setNotifyOnChanges(false) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_trade_alerts),
                    subtitle = stringResource(R.string.settings_trade_alerts_hint),
                    checked = settings.tradeAlerts,
                    icon = R.drawable.ic_swap_horiz,
                    onCheckedChange = { wanted -> if (wanted) withNotifications { viewModel.setTradeAlerts(true) } else viewModel.setTradeAlerts(false) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_publish_alerts),
                    subtitle = stringResource(R.string.settings_publish_alerts_hint),
                    checked = settings.publishAlerts,
                    icon = R.drawable.ic_lock,
                    onCheckedChange = { wanted -> if (wanted) withNotifications { viewModel.setPublishAlerts(true) } else viewModel.setPublishAlerts(false) },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_deadline_reminders),
                    subtitle = stringResource(R.string.settings_deadline_reminders_hint),
                    checked = settings.deadlineReminders,
                    icon = R.drawable.ic_event_available,
                    onCheckedChange = { wanted -> if (wanted) withNotifications { viewModel.setDeadlineReminders(true) } else viewModel.setDeadlineReminders(false) },
                )
            }
            item {
                Text(
                    stringResource(R.string.settings_reminder),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            val reminders = listOf(
                ReminderMode.OFF to R.string.settings_reminder_off,
                ReminderMode.EVENING_BEFORE to R.string.settings_reminder_evening,
                ReminderMode.TWO_HOURS to R.string.settings_reminder_two_hours,
                ReminderMode.ONE_HOUR to R.string.settings_reminder_one_hour,
            )
            for ((mode, label) in reminders) {
                item(key = "reminder-$mode") {
                    RadioRow(
                        title = stringResource(label),
                        selected = settings.reminderMode == mode,
                        enabled = me != null,
                        onClick = {
                            if (mode == ReminderMode.OFF) viewModel.setReminderMode(mode) else withNotifications { viewModel.setReminderMode(mode) }
                        },
                    )
                }
            }
            item { Hint(stringResource(R.string.settings_notifications_limits)) }
            if (!notificationsOn && (settings.notifyOnChanges || settings.reminderMode != ReminderMode.OFF || settings.tradeAlerts)) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(R.string.notify_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { openAppNotificationSettings(context) }) {
                            Text(stringResource(R.string.notify_open_settings))
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_calendar)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_calendar_sync),
                    subtitle = stringResource(R.string.settings_calendar_sync_hint),
                    checked = settings.calendarSync && calendarAllowed,
                    enabled = me != null,
                    icon = R.drawable.ic_event,
                    onCheckedChange = { wanted ->
                        when {
                            !wanted -> viewModel.setCalendarSync(false)
                            hasCalendarPermission(context) -> viewModel.setCalendarSync(true)
                            else -> calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        }
                    },
                )
            }
            item { Hint(stringResource(R.string.settings_calendar_privacy)) }

            item { SectionHeader(stringResource(R.string.settings_help)) }
            item {
                NavigationRow(
                    title = stringResource(R.string.settings_tips_reset),
                    subtitle = stringResource(R.string.settings_tips_reset_hint),
                    icon = R.drawable.ic_lightbulb,
                    onClick = viewModel::resetTips,
                )
            }

            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                NavigationRow(
                    title = stringResource(R.string.settings_licenses),
                    subtitle = stringResource(R.string.settings_licenses_hint),
                    icon = R.drawable.ic_info,
                    onClick = onOpenLicenses,
                )
            }
            item {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Hint(stringResource(R.string.settings_version, viewModel.versionName))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
