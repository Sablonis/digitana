package ch.digitana.dienstplan.ui

import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.digitana.dienstplan.AppContainer
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.export.ExportFormat
import ch.digitana.dienstplan.export.PlanExport
import ch.digitana.dienstplan.ui.activity.ActivityScreen
import ch.digitana.dienstplan.ui.diagnostics.DiagnosticsScreen
import ch.digitana.dienstplan.ui.me.MyShiftsScreen
import ch.digitana.dienstplan.ui.month.MonthScreen
import ch.digitana.dienstplan.ui.patterns.PatternsScreen
import ch.digitana.dienstplan.ui.plan.PlanScreen
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.rules.RulesScreen
import ch.digitana.dienstplan.ui.settings.LicensesScreen
import ch.digitana.dienstplan.ui.settings.SettingsScreen
import ch.digitana.dienstplan.ui.settings.SettingsViewModel
import ch.digitana.dienstplan.ui.stats.StatsScreen
import ch.digitana.dienstplan.ui.team.JoiningScreen
import ch.digitana.dienstplan.ui.team.OnboardingScreen
import ch.digitana.dienstplan.ui.team.TeamScreen
import ch.digitana.dienstplan.ui.team.TeamViewModel
import ch.digitana.dienstplan.ui.team.WhoAmIPrompt
import ch.digitana.dienstplan.ui.types.ShiftTypesScreen
import ch.digitana.dienstplan.util.copyToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Reiter der unteren Leiste (auf breiten Bildschirmen der Leiste am Rand). */
private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    WEEK(R.string.tab_week, R.drawable.ic_view_week),
    MONTH(R.string.tab_month, R.drawable.ic_calendar_month),
    ME(R.string.tab_me, R.drawable.ic_person),
    TEAM(R.string.tab_team, R.drawable.ic_group),
}

/** Seiten, die über den Reitern liegen (mit Zurück-Pfeil, ohne Reiterleiste). */
private enum class Page { SHIFT_TYPES, PATTERNS, RULES, DIAGNOSTICS, SETTINGS, LICENSES, ACTIVITY, STATS }

/** Was geteilt werden soll, bevor das Format gewählt ist. */
private sealed interface ShareRequest {
    data class Week(val week: WeekId) : ShareRequest
    data class Month(val month: YearMonth) : ShareRequest
}

/** Ab dieser Breite steht die Navigation am Rand statt unten (Tablet, Querformat). */
private val RAIL_MIN_WIDTH = 600.dp

@Composable
fun DienstplanRoot(container: AppContainer) {
    val mainViewModel: MainViewModel = viewModel { MainViewModel(container) }
    val state by mainViewModel.state.collectAsStateWithLifecycle()
    val crashReport by mainViewModel.crashReport.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        val team = state.team
        // Sanfter Wechsel zwischen Laden, Einstieg und App.
        val stage = when {
            state.loading -> 0
            team is TeamState.None -> 1
            team is TeamState.Joining -> 2
            else -> 3
        }
        AnimatedContent(
            targetState = stage,
            transitionSpec = { fadeIn(tween(MOTION_MEDIUM)) togetherWith fadeOut(tween(MOTION_SHORT)) },
            label = "Einstieg",
        ) { shown ->
            when (shown) {
                0 -> LoadingScreen()
                1 -> OnboardingScreen(viewModel { TeamViewModel(container) })
                2 -> (team as? TeamState.Joining)?.let { JoiningScreen(viewModel { TeamViewModel(container) }, it) } ?: LoadingScreen()
                else -> MainNavigation(container, startWithTeam = mainViewModel::consumeJustCreatedTeam)
            }
        }
    }

    if (state.upgraded && !state.loading) {
        AlertDialog(
            onDismissRequest = mainViewModel::acknowledgeUpgrade,
            title = { Text(stringResource(R.string.upgrade_title)) },
            text = { Text(stringResource(R.string.upgrade_text)) },
            confirmButton = {
                TextButton(onClick = mainViewModel::acknowledgeUpgrade) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    if (state.storageReset) {
        AlertDialog(
            onDismissRequest = mainViewModel::acknowledgeStorageReset,
            title = { Text(stringResource(R.string.storage_reset_title)) },
            text = { Text(stringResource(R.string.storage_reset_text)) },
            confirmButton = {
                TextButton(onClick = mainViewModel::acknowledgeStorageReset) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    crashReport?.let { report ->
        val context = LocalContext.current
        val label = stringResource(R.string.crash_clipboard_label)
        AlertDialog(
            onDismissRequest = mainViewModel::dismissCrashReport,
            title = { Text(stringResource(R.string.crash_title)) },
            text = { Text(stringResource(R.string.crash_text)) },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, label, report, sensitive = false)
                    mainViewModel.dismissCrashReport()
                }) { Text(stringResource(R.string.crash_copy)) }
            },
            dismissButton = {
                TextButton(onClick = mainViewModel::dismissCrashReport) { Text(stringResource(R.string.crash_discard)) }
            },
        )
    }
}

@Composable
private fun MainNavigation(container: AppContainer, startWithTeam: () -> Boolean) {
    // Nach „Neues Team“ zuerst die Teamseite zeigen (Geräte hinzufügen) – mit einmaligem Hinweis.
    var createdHintPending by remember { mutableStateOf(startWithTeam()) }
    var tab by rememberSaveable { mutableStateOf(if (createdHintPending) Tab.TEAM else Tab.WEEK) }
    // Geöffnete Seiten als Stapel (z. B. Einstellungen → Lizenzen), überdauert Drehen und Prozesstod.
    var stack by rememberSaveable { mutableStateOf("") }
    val pages = remember(stack) { stack.split(',').mapNotNull { name -> Page.entries.firstOrNull { it.name == name } } }
    val page = pages.lastOrNull()
    var forward by remember { mutableStateOf(true) }
    var share by remember { mutableStateOf<ShareRequest?>(null) }
    val planViewModel: PlanViewModel = viewModel { PlanViewModel(container) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun open(target: Page) {
        forward = true
        stack = (pages + target).joinToString(",") { it.name }
    }

    fun back() {
        forward = false
        stack = pages.dropLast(1).joinToString(",") { it.name }
    }

    // Zurückwischen: Die Seite schrumpft mit dem Finger (Predictive Back) und gleitet dann weg.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    var gesturePage by remember { mutableStateOf<Page?>(null) }
    PredictiveBackHandler(enabled = page != null) { progress ->
        gesturePage = page
        try {
            progress.collect { event ->
                backFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                backProgress = event.progress
            }
            back()
        } catch (e: CancellationException) {
            backProgress = 0f
            gesturePage = null
            throw e
        }
    }
    // Nach dem Wegblenden zurücksetzen (die Animation dauert kürzer).
    LaunchedEffect(page) {
        delay(MOTION_LONG.toLong())
        backProgress = 0f
        gesturePage = null
    }
    BackHandler(enabled = page == null && tab != Tab.WEEK) { tab = Tab.WEEK }

    fun export(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.logger.warn("Export", "Export fehlgeschlagen", e)
                Toast.makeText(context, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val spec = if (forward) {
                (slideInHorizontally(tween(MOTION_MEDIUM)) { it / 5 } + fadeIn(tween(MOTION_MEDIUM))) togetherWith
                    fadeOut(tween(MOTION_SHORT))
            } else {
                fadeIn(tween(MOTION_MEDIUM)) togetherWith
                    (slideOutHorizontally(tween(MOTION_MEDIUM)) { it / 5 } + fadeOut(tween(MOTION_MEDIUM)))
            }
            spec.using(SizeTransform(clip = false))
        },
        label = "Seite",
    ) { shown ->
        val gestured = shown != null && shown == gesturePage
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (gestured) {
                        val p = backProgress
                        val scale = 1f - 0.1f * p
                        scaleX = scale
                        scaleY = scale
                        translationX = (if (backFromLeft) 1 else -1) * p * 24.dp.toPx()
                        shape = RoundedCornerShape((28 * p).dp)
                        clip = p > 0f
                    }
                },
        ) {
            when (shown) {
                Page.SHIFT_TYPES -> ShiftTypesScreen(planViewModel, onBack = ::back)
                Page.PATTERNS -> PatternsScreen(planViewModel, onBack = ::back)
                Page.RULES -> RulesScreen(planViewModel, onBack = ::back)
                Page.DIAGNOSTICS -> DiagnosticsScreen(container = container, onBack = ::back)
                Page.SETTINGS -> SettingsScreen(viewModel { SettingsViewModel(container) }, onBack = ::back, onOpenLicenses = { open(Page.LICENSES) })
                Page.LICENSES -> LicensesScreen(onBack = ::back)
                Page.ACTIVITY -> ActivityScreen(
                    viewModel = planViewModel,
                    onBack = ::back,
                    onOpenWeek = { week ->
                        planViewModel.selectWeek(week)
                        tab = Tab.WEEK
                        back()
                    },
                )
                Page.STATS -> StatsScreen(planViewModel, onBack = ::back)
                null -> Tabs(
                    container = container,
                    planViewModel = planViewModel,
                    tab = tab,
                    onTab = { tab = it },
                    createdHintPending = createdHintPending,
                    onCreatedHintShown = { createdHintPending = false },
                    onOpen = ::open,
                    onShare = { share = it },
                    onExportCalendar = { memberId ->
                        val state = planViewModel.uiState.value
                        export { PlanExport.shareCalendar(context, state.plan, memberId, state.today) }
                    },
                )
            }
        }
    }

    // Nach dem Beitritt oder Gründen einmal fragen, wer dieses Gerät benutzt.
    WhoAmIPrompt(viewModel { TeamViewModel(container) })

    share?.let { request ->
        ShareDialog(
            onFormat = { format ->
                share = null
                val state = planViewModel.uiState.value
                export {
                    when (request) {
                        is ShareRequest.Week -> PlanExport.shareWeek(context, state.plan, request.week, state.today, state.teamName, format)
                        is ShareRequest.Month -> PlanExport.shareMonth(context, state.plan, request.month, state.today, state.teamName, format)
                    }
                }
            },
            onDismiss = { share = null },
        )
    }
}

/** Die vier Reiter: unten auf dem Handy, am Rand auf breiten Bildschirmen. Wechsel mit kurzer Überblendung. */
@Composable
private fun Tabs(
    container: AppContainer,
    planViewModel: PlanViewModel,
    tab: Tab,
    onTab: (Tab) -> Unit,
    createdHintPending: Boolean,
    onCreatedHintShown: () -> Unit,
    onOpen: (Page) -> Unit,
    onShare: (ShareRequest) -> Unit,
    onExportCalendar: (String) -> Unit,
) {
    val content: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(MOTION_MEDIUM, delayMillis = MOTION_SHORT / 2)) + scaleIn(tween(MOTION_MEDIUM, delayMillis = MOTION_SHORT / 2), initialScale = 0.97f)) togetherWith
                    fadeOut(tween(MOTION_SHORT))
            },
            label = "Reiter",
            modifier = modifier,
        ) { shown ->
            when (shown) {
                Tab.WEEK -> PlanScreen(
                    viewModel = planViewModel,
                    onOpenDiagnostics = { onOpen(Page.DIAGNOSTICS) },
                    onOpenShiftTypes = { onOpen(Page.SHIFT_TYPES) },
                    onOpenPatterns = { onOpen(Page.PATTERNS) },
                    onOpenRules = { onOpen(Page.RULES) },
                    onOpenActivity = { onOpen(Page.ACTIVITY) },
                    onOpenSettings = { onOpen(Page.SETTINGS) },
                    onShareWeek = { onShare(ShareRequest.Week(it)) },
                )
                Tab.MONTH -> MonthScreen(
                    viewModel = planViewModel,
                    onOpenDiagnostics = { onOpen(Page.DIAGNOSTICS) },
                    onOpenStats = { onOpen(Page.STATS) },
                    onShareMonth = { onShare(ShareRequest.Month(it)) },
                )
                Tab.ME -> MyShiftsScreen(
                    viewModel = planViewModel,
                    onExportCalendar = onExportCalendar,
                    onOpenSettings = { onOpen(Page.SETTINGS) },
                )
                Tab.TEAM -> {
                    val teamViewModel: TeamViewModel = viewModel { TeamViewModel(container) }
                    TeamScreen(
                        viewModel = teamViewModel,
                        onBack = null,
                        showCreatedHint = createdHintPending,
                        onCreatedHintShown = onCreatedHintShown,
                        onOpenShiftTypes = { onOpen(Page.SHIFT_TYPES) },
                        onOpenPatterns = { onOpen(Page.PATTERNS) },
                        onOpenRules = { onOpen(Page.RULES) },
                        onOpenDiagnostics = { onOpen(Page.DIAGNOSTICS) },
                        onOpenSettings = { onOpen(Page.SETTINGS) },
                        onOpenStats = { onOpen(Page.STATS) },
                    )
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= RAIL_MIN_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(header = { Spacer(Modifier.height(8.dp)) }) {
                    Spacer(Modifier.weight(1f))
                    for (item in Tab.entries) {
                        NavigationRailItem(
                            selected = tab == item,
                            onClick = { onTab(item) },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                // Den linken Rand (Ausschnitt, Navigationsleiste) hat die Leiste schon.
                content(Modifier.weight(1f).fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)))
            }
        } else {
            Scaffold(
                // Die Abstände für Status- und Navigationsleiste setzen die Reiter und die untere Leiste selbst.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar {
                        for (item in Tab.entries) {
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { onTab(item) },
                                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                },
            ) { padding ->
                content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
            }
        }
    }
}

/** Format wählen; geteilte Dateien sind nicht verschlüsselt. */
@Composable
private fun ShareDialog(onFormat: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.share_unencrypted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ShareOption(R.drawable.ic_description, R.string.share_pdf) { onFormat(ExportFormat.PDF) }
                ShareOption(R.drawable.ic_image, R.string.share_image) { onFormat(ExportFormat.PNG) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ShareOption(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
    }
}
