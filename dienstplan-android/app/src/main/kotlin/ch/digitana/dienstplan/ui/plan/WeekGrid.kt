package ch.digitana.dienstplan.ui.plan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.plan.Cell
import ch.digitana.dienstplan.core.plan.DayInfo
import ch.digitana.dienstplan.core.plan.MemberRow
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.IndicatorDot
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.theme.LocalShiftPalette
import java.time.LocalDate

private val NAME_WIDTH = 108.dp
private val ROW_HEIGHT = 58.dp

/** Höchstens so viele Arbeitsschichten erscheinen einzeln in der Besetzung, sonst die Summe. */
internal const val MAX_COVERAGE_TYPES = 4

/**
 * Wochenraster: Zeilen = Personen (mit Stunden), Spalten = Mo–So, unten die Besetzung.
 * Heute und das Wochenende sind hinterlegt; Wünsche und Notizen erscheinen als Punkte.
 */
@Composable
fun WeekGrid(
    model: WeekModel,
    myMemberId: String?,
    readOnly: Boolean,
    onCellClick: (CellRef) -> Unit,
    onCellLongClick: (CellRef) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onMemberClick: (Member) -> Unit,
    onAddMember: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        GridHeader(model.days, onDayClick)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (model.rows.isEmpty()) {
            EmptyMembers(readOnly = readOnly, onAddMember = onAddMember, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(model.rows, key = { it.member.id }) { row ->
                    MemberRowView(
                        row = row,
                        days = model.days,
                        isMe = row.member.id == myMemberId,
                        readOnly = readOnly,
                        onCellClick = onCellClick,
                        onCellLongClick = onCellLongClick,
                        onMemberClick = onMemberClick,
                    )
                }
                if (!readOnly) {
                    item(key = "add") {
                        TextButton(onClick = onAddMember, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)) {
                            Icon(painterResource(R.drawable.ic_person_add), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_add_member))
                        }
                    }
                }
            }
        }
        CoverageFooter(model)
    }
}

/** Hintergrund einer Tagesspalte: heute kräftiger, Wochenende dezent. */
@Composable
internal fun dayTint(day: DayInfo): Color = when {
    day.isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    day.isWeekend -> MaterialTheme.colorScheme.surfaceContainer
    else -> Color.Transparent
}

@Composable
private fun GridHeader(days: List<DayInfo>, onDayClick: (LocalDate) -> Unit) {
    val todayMarker = stringResource(R.string.today_marker)
    val noteMarker = stringResource(R.string.day_has_note)
    val dayClickLabel = stringResource(R.string.day_click_label)
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.column_team),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(NAME_WIDTH).padding(start = 16.dp),
        )
        for (day in days) {
            val description = buildString {
                append(WeekFormat.longDate(day.date))
                if (day.isToday) append(", ").append(todayMarker)
                if (day.note != null) append(", ").append(noteMarker)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(dayTint(day))
                    .clickable(enabled = day.editable, onClickLabel = dayClickLabel) { onDayClick(day.date) }
                    .semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = WeekFormat.weekday(day.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (day.isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = day.date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (day.isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(2.dp))
                if (day.note != null) {
                    IndicatorDot(MaterialTheme.colorScheme.tertiary)
                } else {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun MemberRowView(
    row: MemberRow,
    days: List<DayInfo>,
    isMe: Boolean,
    readOnly: Boolean,
    onCellClick: (CellRef) -> Unit,
    onCellLongClick: (CellRef) -> Unit,
    onMemberClick: (Member) -> Unit,
) {
    val hoursDescription = stringResource(R.string.member_hours_description, row.member.name, Format.hours(row.minutes))
    val meDescription = stringResource(R.string.member_me_description, row.member.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (isMe) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(NAME_WIDTH)
                .fillMaxHeight()
                .clickable(enabled = !readOnly, onClickLabel = stringResource(R.string.member_click_label)) { onMemberClick(row.member) }
                .semantics(mergeDescendants = true) { contentDescription = if (isMe) "$meDescription, $hoursDescription" else hoursDescription }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberAvatar(row.member.name, row.member.id, size = 30.dp, highlighted = isMe)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.member.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Format.hours(row.minutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        days.forEachIndexed { index, day ->
            val ref = CellRef(row.member.id, day.date)
            ShiftCellView(
                cell = row.cells[index],
                day = day,
                memberName = row.member.name,
                enabled = day.editable && !readOnly,
                onClick = { onCellClick(ref) },
                onLongClick = { onCellLongClick(ref) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShiftCellView(
    cell: Cell,
    day: DayInfo,
    memberName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
) {
    val palette = LocalShiftPalette.current
    val color = palette.of(cell.type)
    val description = cellDescription(cell, memberName, day.date)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(dayTint(day))
            .padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(if (cell.typeId != null) color.container else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f))
                .combinedClickable(
                    enabled = enabled,
                    onClickLabel = stringResource(R.string.cell_click_label),
                    onLongClickLabel = if (cell.typeId != null) stringResource(R.string.cell_long_click_label) else null,
                    // Die haptische Rückmeldung beim langen Drücken liefert combinedClickable selbst.
                    onLongClick = if (cell.typeId != null) onLongClick else null,
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            val type = cell.type
            if (cell.typeId != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = type?.code ?: "?",
                        color = color.content,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    val time = type?.let { Format.shortTimeRange(it) }
                    if (showTime && time != null) {
                        Text(
                            text = time,
                            color = color.content.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else if (cell.wish != null) {
                Icon(
                    painterResource(R.drawable.ic_favorite),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (cell.wish != null && cell.typeId != null) {
                IndicatorDot(MaterialTheme.colorScheme.tertiary, Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
            if (cell.note != null) {
                IndicatorDot(
                    if (cell.typeId != null) color.content else MaterialTheme.colorScheme.secondary,
                    Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }
    }
}

@Composable
internal fun cellDescription(cell: Cell, memberName: String, date: LocalDate): String {
    val type = cell.type
    val shift = when {
        type != null -> type.name + (Format.timeRange(type)?.let { ", $it" } ?: "")
        cell.typeId != null -> stringResource(R.string.shift_unknown)
        else -> stringResource(R.string.cell_empty)
    }
    val extras = buildList {
        cell.wish?.let { add(it.label) }
        cell.note?.let { add(it) }
    }
    return stringResource(R.string.cell_description, memberName, WeekFormat.longDate(date), (listOf(shift) + extras).joinToString(", "))
}

@Composable
private fun EmptyMembers(readOnly: Boolean, onAddMember: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_group),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_members),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!readOnly) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAddMember) {
                Icon(painterResource(R.drawable.ic_person_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_add_member))
            }
        }
    }
}

/** Besetzung pro Tag: Anzahl je Arbeitsschicht in ihrer Farbe, bei vielen Arten die Summe. */
@Composable
private fun CoverageFooter(model: WeekModel) {
    val palette = LocalShiftPalette.current
    val types = model.coverageTypes
    val single = types.size <= MAX_COVERAGE_TYPES
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(NAME_WIDTH).padding(start = 16.dp, end = 4.dp)) {
                Text(stringResource(R.string.coverage_label), style = MaterialTheme.typography.labelMedium)
                if (single && types.isNotEmpty()) {
                    Row {
                        types.forEachIndexed { i, type ->
                            if (i > 0) Text(" · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(type.code, style = MaterialTheme.typography.labelSmall, color = palette.of(type).strong, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.coverage_total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    Format.hours(model.totalMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            model.days.forEachIndexed { index, day ->
                val coverage = model.coverage[index]
                val description = stringResource(
                    R.string.coverage_description,
                    WeekFormat.longDate(day.date),
                    coverage.counts.joinToString(", ") { (type, count) -> "${type.name} $count" }.ifEmpty { "0" },
                )
                Column(
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (single) {
                        for ((type, count) in coverage.counts) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (count > 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (count > 0) palette.of(type).strong else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        Text(
                            text = coverage.total.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (coverage.total > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
