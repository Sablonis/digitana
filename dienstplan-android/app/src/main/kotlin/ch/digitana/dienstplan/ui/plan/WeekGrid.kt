package ch.digitana.dienstplan.ui.plan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.Shift
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.ui.theme.LocalShiftColors
import ch.digitana.dienstplan.ui.theme.ShiftColors
import java.time.LocalDate

private const val NAME_WEIGHT = 2.3f
private const val DAY_WEIGHT = 1f
private const val HOURS_WEIGHT = 1.2f
private val ROW_HEIGHT = 48.dp

/**
 * Wochenraster: Zeilen = Mitarbeitende, Spalten = Mo–So, rechts die Stunden,
 * unten die Besetzung pro Tag. Heute ist hervorgehoben, das Wochenende eingefärbt.
 */
@Composable
fun WeekGrid(
    model: WeekModel,
    onCellClick: (memberId: String, date: LocalDate) -> Unit,
    onCellLongClick: (memberId: String, date: LocalDate) -> Unit,
    onMemberClick: (Member) -> Unit,
    myMemberId: String?,
    modifier: Modifier = Modifier,
    /** Nur lesen (z. B. nach dem Entfernen aus dem Team): keine Eingaben. */
    readOnly: Boolean = false,
) {
    val colors = LocalShiftColors.current
    Column(modifier.fillMaxWidth()) {
        GridHeader(model.days, colors)
        HorizontalDivider()
        if (model.rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.empty_members),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(model.rows, key = { it.member.id }) { row ->
                    MemberRowView(row, model.days, colors, row.member.id == myMemberId, readOnly, onCellClick, onCellLongClick, onMemberClick)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
        HorizontalDivider()
        CoverageRow(model, colors)
    }
}

private fun columnTint(day: WeekModel.DayInfo, colors: ShiftColors): Color = when {
    day.isToday -> colors.todayTint
    day.isWeekend -> colors.weekendTint
    else -> Color.Transparent
}

@Composable
private fun GridHeader(days: List<WeekModel.DayInfo>, colors: ShiftColors) {
    val todayMarker = stringResource(R.string.today_marker)
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.column_name),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(NAME_WEIGHT).padding(start = 12.dp),
        )
        for (day in days) {
            val description = WeekFormat.longDate(day.date) + if (day.isToday) ", $todayMarker" else ""
            Column(
                modifier = Modifier
                    .weight(DAY_WEIGHT)
                    .fillMaxHeight()
                    .background(columnTint(day, colors))
                    .semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val accent = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Text(
                    text = WeekFormat.weekday(day.date),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = accent,
                )
                Text(
                    text = WeekFormat.shortDate(day.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.column_hours),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(HOURS_WEIGHT),
        )
    }
}

@Composable
private fun MemberRowView(
    row: WeekModel.MemberRow,
    days: List<WeekModel.DayInfo>,
    colors: ShiftColors,
    isMe: Boolean,
    readOnly: Boolean,
    onCellClick: (String, LocalDate) -> Unit,
    onCellLongClick: (String, LocalDate) -> Unit,
    onMemberClick: (Member) -> Unit,
) {
    val hoursDescription = stringResource(R.string.member_hours_description, row.member.name, row.hours)
    val meDescription = stringResource(R.string.member_me_description, row.member.name)
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = row.member.name,
            style = MaterialTheme.typography.bodyMedium,
            // Die eigene Zeile ist hervorgehoben.
            fontWeight = if (isMe) FontWeight.Bold else null,
            color = if (isMe) MaterialTheme.colorScheme.primary else Color.Unspecified,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(NAME_WEIGHT)
                .fillMaxHeight()
                .clickable(enabled = !readOnly, onClickLabel = stringResource(R.string.member_click_label)) { onMemberClick(row.member) }
                .semantics { if (isMe) contentDescription = meDescription }
                .padding(horizontal = 12.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        days.forEachIndexed { index, day ->
            ShiftCell(
                shift = row.shifts[index],
                day = day,
                memberName = row.member.name,
                colors = colors,
                enabled = day.editable && !readOnly,
                onClick = { onCellClick(row.member.id, day.date) },
                onLongClick = { onCellLongClick(row.member.id, day.date) },
                modifier = Modifier.weight(DAY_WEIGHT),
            )
        }
        Text(
            text = stringResource(R.string.hours_value, row.hours),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(HOURS_WEIGHT)
                .semantics { contentDescription = hoursDescription },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShiftCell(
    shift: Shift?,
    day: WeekModel.DayInfo,
    memberName: String,
    colors: ShiftColors,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        R.string.cell_description,
        memberName,
        WeekFormat.longDate(day.date),
        shift?.label ?: stringResource(R.string.cell_empty),
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(columnTint(day, colors))
            .padding(horizontal = 2.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(shift?.let { colors[it].container } ?: Color.Transparent)
                .combinedClickable(
                    enabled = enabled,
                    onClickLabel = stringResource(R.string.cell_click_label),
                    onLongClickLabel = stringResource(R.string.cell_long_click_label),
                    // Haptische Rückmeldung beim langen Drücken liefert combinedClickable selbst.
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (shift != null) {
                Text(
                    text = shift.code,
                    color = colors[shift].content,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun CoverageRow(model: WeekModel, colors: ShiftColors) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(NAME_WEIGHT).padding(start = 12.dp)) {
            Text(stringResource(R.string.coverage_label), style = MaterialTheme.typography.labelMedium)
            Text("F · S · N", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        model.days.forEachIndexed { index, day ->
            val coverage = model.coverage[index]
            val description = stringResource(
                R.string.coverage_description,
                WeekFormat.longDate(day.date),
                coverage.frueh,
                coverage.spaet,
                coverage.nacht,
            )
            Column(
                modifier = Modifier
                    .weight(DAY_WEIGHT)
                    .fillMaxHeight()
                    .background(columnTint(day, colors))
                    .semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CoverageNumber(coverage.frueh, colors[Shift.FRUEH].content)
                CoverageNumber(coverage.spaet, colors[Shift.SPAET].content)
                CoverageNumber(coverage.nacht, colors[Shift.NACHT].content)
            }
        }
        Text(
            text = stringResource(R.string.hours_value, model.totalHours),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(HOURS_WEIGHT),
        )
    }
}

@Composable
private fun CoverageNumber(value: Int, color: Color) {
    Text(
        text = value.toString(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (value > 0) FontWeight.Bold else FontWeight.Normal,
        color = if (value > 0) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
}
