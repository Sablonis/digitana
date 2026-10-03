package ch.digitana.dienstplan.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.plan.ShiftTrades
import ch.digitana.dienstplan.ui.components.EmptyState
import ch.digitana.dienstplan.ui.components.NavigationRow
import ch.digitana.dienstplan.ui.components.RadioRow
import ch.digitana.dienstplan.ui.components.SectionHeader
import ch.digitana.dienstplan.ui.components.SwitchRow
import ch.digitana.dienstplan.ui.plan.DeadlineBanner
import ch.digitana.dienstplan.ui.plan.OpenShiftsBar
import ch.digitana.dienstplan.ui.plan.PlanMarkers
import ch.digitana.dienstplan.ui.plan.TipCard
import ch.digitana.dienstplan.ui.plan.Tips
import ch.digitana.dienstplan.ui.plan.WeekGrid
import java.time.YearMonth

/** Wochenraster des Beispielteams; Anna ist „Ich“, eine Änderung ist neu, eine noch unterwegs. */
@Composable
internal fun SampleWeek() {
    val model = SampleTeam.week()
    val monday = SampleTeam.WEEK.monday
    val markers = PlanMarkers(
        unseen = setOf("z|${SampleTeam.CARLA}|${monday.plusDays(4)}"),
        pending = setOf("z|${SampleTeam.ANNA}|${monday.plusDays(2)}"),
    )
    WeekGrid(
        model = model,
        myMemberId = SampleTeam.ANNA,
        readOnly = false,
        onCellClick = {},
        onCellLongClick = {},
        onDayClick = {},
        onMemberClick = {},
        onAddMember = {},
        markers = markers,
    )
}

/** Hinweise rund um das Raster: Tipp, Wunschfrist, offene Dienste und ein Leerzustand. */
@Composable
internal fun SampleHints() {
    val plan = SampleTeam.plan
    val open = ShiftTrades.openShifts(plan, SampleTeam.TODAY, SampleTeam.TODAY.plusDays(6))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TipCard(tip = Tips.BRUSH, onDismiss = {})
        DeadlineBanner(month = YearMonth.of(2027, 1), deadline = SampleTeam.TODAY.plusDays(3))
        OpenShiftsBar(open = open, onOpenDay = {})
        EmptyState(
            icon = R.drawable.ic_event,
            title = stringResource(R.string.empty_week_title),
            text = stringResource(R.string.empty_week_text),
            action = stringResource(R.string.empty_week_pattern) to {},
            secondary = stringResource(R.string.suggest_menu) to {},
        )
    }
}

/** Ausschnitt der Einstellungen: Schalter, Auswahl und Verweise. */
@Composable
internal fun SampleSettings() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.settings_appearance))
        RadioRow(stringResource(R.string.settings_theme_system), selected = true, onClick = {})
        RadioRow(stringResource(R.string.settings_theme_light), selected = false, onClick = {})
        RadioRow(stringResource(R.string.settings_theme_dark), selected = false, onClick = {})
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            checked = false,
            onCheckedChange = {},
            subtitle = stringResource(R.string.settings_dynamic_color_hint),
        )
        SectionHeader(stringResource(R.string.settings_notifications))
        SwitchRow(
            title = stringResource(R.string.settings_trade_alerts),
            checked = true,
            onCheckedChange = {},
            subtitle = stringResource(R.string.settings_trade_alerts_hint),
        )
        NavigationRow(
            title = stringResource(R.string.settings_licenses),
            onClick = {},
            subtitle = stringResource(R.string.settings_licenses_hint),
        )
    }
}
