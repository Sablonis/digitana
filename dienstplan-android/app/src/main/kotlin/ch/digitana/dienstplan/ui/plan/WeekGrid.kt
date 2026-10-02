package ch.digitana.dienstplan.ui.plan

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.WishStatus
import ch.digitana.dienstplan.core.plan.Cell
import ch.digitana.dienstplan.core.plan.DayInfo
import ch.digitana.dienstplan.core.plan.MemberRow
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.core.plan.WeekModel
import ch.digitana.dienstplan.ui.GridDensity
import ch.digitana.dienstplan.ui.bouncySpring
import ch.digitana.dienstplan.ui.components.EmptyState
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.IndicatorDot
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.gentleSpring
import ch.digitana.dienstplan.ui.theme.LocalShiftPalette
import ch.digitana.dienstplan.ui.theme.tabular
import java.time.LocalDate

private val NAME_WIDTH = 108.dp
private val NAME_WIDTH_NARROW = 64.dp
private val NAME_WIDTH_WIDE = 148.dp
private val ROW_HEIGHT = 58.dp
private val HEADER_HEIGHT = 64.dp

/**
 * Test-Tag für Felder und Tage im Raster: Sieben Spalten passen auf schmalen Handys nur unter
 * 48 dp; die Barrierefreiheitstests nehmen diese Ziele deshalb von der Mindestgrösse aus.
 */
const val DENSE_TARGET_TAG = "rasterfeld"

/** Höchstens so viele Arbeitsschichten erscheinen einzeln in der Besetzung, sonst die Summe. */
internal const val MAX_COVERAGE_TYPES = 4

/** Malen durch Ziehen beim schnellen Eintragen: Beginn, jedes neue Feld, Ende eines Strichs. */
class BrushGestures(
    val onStart: () -> Unit,
    val onPaint: (CellRef) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * Masse des Rasters. Auf schmalen Handys zeigt die Namensspalte nur Avatar und Vornamen, damit
 * die Tagesspalten breiter werden; bei grosser Schrift wachsen die Zeilen mit, statt Text
 * abzuschneiden.
 */
@Immutable
internal data class GridMetrics(val nameWidth: Dp, val rowHeight: Dp, val headerHeight: Dp, val narrow: Boolean, val showTimes: Boolean)

@Composable
internal fun rememberGridMetrics(maxWidth: Dp, density: GridDensity): GridMetrics {
    val fontScale = LocalDensity.current.fontScale
    val grow = maxOf(1f, 0.55f + 0.45f * fontScale)
    val narrow = maxWidth < 380.dp
    val wide = maxWidth >= 600.dp
    return GridMetrics(
        nameWidth = when {
            narrow -> NAME_WIDTH_NARROW
            wide -> NAME_WIDTH_WIDE
            else -> NAME_WIDTH
        },
        rowHeight = ROW_HEIGHT * grow,
        headerHeight = HEADER_HEIGHT * grow,
        narrow = narrow,
        // Zeiten, wenn gewählt oder auf breiten Bildschirmen – nicht bei sehr grosser Schrift.
        showTimes = (density == GridDensity.COMFORTABLE || wide) && fontScale <= 1.3f,
    )
}

/**
 * Wochenraster: Zeilen = Personen (mit Stunden), Spalten = Mo–So, unten die Besetzung. Die
 * eigene Zeile steht oben und bleibt beim Scrollen sichtbar. Heute, Wochenende und Feiertage
 * sind hinterlegt; Wünsche, Notizen, neue und noch nicht gesendete Änderungen erscheinen als
 * kleine Zeichen im Feld.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /** Tag gesperrt (Schloss im Kopf)? */
    isLocked: (LocalDate) -> Boolean = { false },
    /** Darf dieses Gerät die Schicht an dem Tag ändern (langes Drücken leert)? */
    canEditShift: (LocalDate) -> Boolean = { !readOnly },
    markers: PlanMarkers = PlanMarkers(),
    /** Gerade geöffnetes Feld (hervorgehoben, solange das Eintragsfenster offen ist). */
    selected: CellRef? = null,
    /** Schnell eintragen: Wischen über eine Zeile malt die gewählte Schicht. */
    brush: BrushGestures? = null,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val metrics = rememberGridMetrics(maxWidth, markers.density)
        Column(Modifier.fillMaxSize()) {
            GridHeader(model.days, metrics, isLocked, onDayClick)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (model.rows.isEmpty()) {
                EmptyMembers(readOnly = readOnly, onAddMember = onAddMember, modifier = Modifier.weight(1f))
            } else {
                val mine = model.rows.firstOrNull { it.member.id == myMemberId }
                val others = model.rows.filter { it !== mine }
                val row: @Composable (MemberRow, Boolean) -> Unit = { memberRow, isMe ->
                    MemberRowView(
                        row = memberRow,
                        days = model.days,
                        types = model.types,
                        metrics = metrics,
                        isMe = isMe,
                        readOnly = readOnly,
                        canEditShift = canEditShift,
                        markers = markers,
                        selected = selected,
                        brush = brush,
                        onCellClick = onCellClick,
                        onCellLongClick = onCellLongClick,
                        onDayClick = onDayClick,
                        onMemberClick = onMemberClick,
                    )
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (mine != null) {
                        // Eigene Zeile oben und beim Scrollen sichtbar.
                        stickyHeader(key = "me-${mine.member.id}") {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Column {
                                    row(mine, true)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                    items(others, key = { it.member.id }) { other -> row(other, false) }
                    if (emptyContent != null) {
                        item(key = "empty") { emptyContent() }
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
            CoverageFooter(model, metrics)
        }
    }
}

/** Hintergrund einer Tagesspalte: heute kräftiger, Wochenende und Feiertage dezent. */
@Composable
internal fun dayTint(day: DayInfo): Color = when {
    day.isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    day.holiday != null -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
    day.isWeekend -> MaterialTheme.colorScheme.surfaceContainer
    else -> Color.Transparent
}

/** Beschreibung eines Tages für Screenreader: Datum, heute, gesperrt, Feiertag, Notiz. */
@Composable
internal fun dayDescription(day: DayInfo, locked: Boolean): String {
    val todayMarker = stringResource(R.string.today_marker)
    val noteMarker = stringResource(R.string.day_has_note)
    val lockMarker = stringResource(R.string.lock_day_marker)
    return buildString {
        append(WeekFormat.longDate(day.date))
        if (day.isToday) append(", ").append(todayMarker)
        day.holiday?.let { append(", ").append(it.name) }
        if (locked) append(", ").append(lockMarker)
        if (day.note != null) append(", ").append(noteMarker)
    }
}

@Composable
private fun GridHeader(days: List<DayInfo>, metrics: GridMetrics, isLocked: (LocalDate) -> Boolean, onDayClick: (LocalDate) -> Unit) {
    val dayClickLabel = stringResource(R.string.day_click_label)
    Row(Modifier.fillMaxWidth().height(metrics.headerHeight), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.column_team),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(metrics.nameWidth).padding(start = if (metrics.narrow) 8.dp else 16.dp),
        )
        for (day in days) {
            val locked = day.editable && isLocked(day.date)
            val description = dayDescription(day, locked)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(dayTint(day))
                    .clickable(enabled = day.editable, onClickLabel = dayClickLabel) { onDayClick(day.date) }
                    .semantics(mergeDescendants = true) { contentDescription = description }
                    .testTag(DENSE_TARGET_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = WeekFormat.weekday(day.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        day.isToday -> MaterialTheme.colorScheme.primary
                        day.holiday != null -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = when {
                            day.isToday -> MaterialTheme.colorScheme.onPrimary
                            day.holiday != null -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.height(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (day.holiday != null) {
                        Icon(
                            painterResource(R.drawable.ic_celebration),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                    if (locked) {
                        Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                    if (day.note != null) IndicatorDot(MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun MemberRowView(
    row: MemberRow,
    days: List<DayInfo>,
    types: ShiftTypeSet,
    metrics: GridMetrics,
    isMe: Boolean,
    readOnly: Boolean,
    canEditShift: (LocalDate) -> Boolean,
    markers: PlanMarkers,
    selected: CellRef?,
    brush: BrushGestures?,
    onCellClick: (CellRef) -> Unit,
    onCellLongClick: (CellRef) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onMemberClick: (Member) -> Unit,
) {
    val hoursDescription = stringResource(R.string.member_hours_description, row.member.name, Format.hours(row.minutes))
    val meDescription = stringResource(R.string.member_me_description, row.member.name)
    val haptics = LocalHapticFeedback.current
    val nameWidthPx = with(LocalDensity.current) { metrics.nameWidth.toPx() }
    // Schnell eintragen: Wischen über die Zeile setzt jedes überstrichene Feld (mit kurzem Tick).
    val paint = if (brush != null && !readOnly) {
        Modifier.pointerInput(brush, row.member.id, days) {
            var last = -1
            fun paintAt(x: Float) {
                val cellWidth = (size.width - nameWidthPx) / days.size
                if (x < nameWidthPx || cellWidth <= 0f) return
                val index = ((x - nameWidthPx) / cellWidth).toInt().coerceIn(0, days.size - 1)
                if (index == last) return
                last = index
                val day = days[index]
                if (!day.editable) return
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                brush.onPaint(CellRef(row.member.id, day.date))
            }
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    last = -1
                    brush.onStart()
                    paintAt(offset.x)
                },
                onDragEnd = {
                    last = -1
                    brush.onEnd()
                },
                onDragCancel = {
                    last = -1
                    brush.onEnd()
                },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    paintAt(change.position.x)
                },
            )
        }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.rowHeight)
            .background(if (isMe) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent)
            .then(paint),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val nameModifier = Modifier
            .width(metrics.nameWidth)
            .fillMaxHeight()
            .clickable(enabled = !readOnly, onClickLabel = stringResource(R.string.member_click_label)) { onMemberClick(row.member) }
            .semantics(mergeDescendants = true) { contentDescription = if (isMe) "$meDescription, $hoursDescription" else hoursDescription }
        if (metrics.narrow) {
            Column(nameModifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                MemberAvatar(row.member.name, row.member.id, size = 26.dp, highlighted = isMe)
                Text(
                    text = row.member.name.substringBefore(' '),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(nameModifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        days.forEachIndexed { index, day ->
            val ref = CellRef(row.member.id, day.date)
            val keys = remember(ref) { ref.keys() }
            ShiftCellView(
                cell = row.cells[index],
                day = day,
                memberName = row.member.name,
                types = types,
                enabled = day.editable && !readOnly,
                allowLongClick = day.editable && canEditShift(day.date),
                onClick = { onCellClick(ref) },
                onLongClick = { onCellLongClick(ref) },
                modifier = Modifier.weight(1f),
                showTime = metrics.showTimes,
                unseen = keys.any { it in markers.unseen },
                pending = keys.any { it in markers.pending },
                selected = selected == ref,
                onOpenDay = if (day.editable) {
                    { onDayClick(day.date) }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Ein Feld: Kürzel (und Zeiten) in der Farbe der Schicht. Ändert sich die Schicht, wechselt die
 * Farbe sanft und das Kärtchen federt kurz. Ecken: oben links Ruhezeit-Warnung oder „neu“,
 * oben rechts Wunsch, unten links „wird gesendet“ oder „wird abgegeben“, unten rechts Notiz.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShiftCellView(
    cell: Cell,
    day: DayInfo,
    memberName: String,
    types: ShiftTypeSet,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    /** Langes Drücken leert das Feld; aus, wenn der Tag für dieses Gerät gesperrt ist. */
    allowLongClick: Boolean = true,
    /** Ein anderes Gerät hat das Feld geändert, seit hier jemand hingeschaut hat. */
    unseen: Boolean = false,
    /** Eigene Änderung, die noch kein Relay bestätigt hat. */
    pending: Boolean = false,
    selected: Boolean = false,
    /** Für Screenreader: Tagesansicht als eigene Aktion. */
    onOpenDay: (() -> Unit)? = null,
) {
    val palette = LocalShiftPalette.current
    val color = palette.of(cell.type)
    val description = cellDescription(cell, memberName, day.date, types, unseen, pending)
    val longClick = cell.typeId != null && allowLongClick
    val openDayLabel = stringResource(R.string.day_click_label)
    val emptyColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)
    val background by animateColorAsState(if (cell.typeId != null) color.container else emptyColor, gentleSpring(), label = "Feldfarbe")
    // Kurzes Federn, wenn sich die Schicht ändert (nicht beim ersten Anzeigen).
    val pop = remember { Animatable(1f) }
    var shownType by remember { mutableStateOf(cell.typeId) }
    LaunchedEffect(cell.typeId) {
        if (cell.typeId != shownType) {
            shownType = cell.typeId
            pop.snapTo(0.86f)
            pop.animateTo(1f, bouncySpring())
        }
    }
    val selectedScale by animateFloatAsState(if (selected) 1.08f else 1f, bouncySpring(), label = "Auswahl")
    val shape = RoundedCornerShape(12.dp)
    val border = when {
        cell.rest != null -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
        selected -> BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(dayTint(day))
            .padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = pop.value * selectedScale
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(background)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .combinedClickable(
                    enabled = enabled,
                    onClickLabel = stringResource(R.string.cell_click_label),
                    onLongClickLabel = if (longClick) stringResource(R.string.cell_long_click_label) else null,
                    // Die haptische Rückmeldung beim langen Drücken liefert combinedClickable selbst.
                    onLongClick = if (longClick) onLongClick else null,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = description
                    if (onOpenDay != null) {
                        customActions = listOf(CustomAccessibilityAction(openDayLabel) { onOpenDay(); true })
                    }
                }
                .testTag(DENSE_TARGET_TAG),
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
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                val wish = cell.wish
                if (wish != null) {
                    // Ohne Schicht: Herz und Kürzel des Wunsches (bei einer Wunschschicht deren Kürzel).
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(R.drawable.ic_favorite),
                            contentDescription = null,
                            tint = wishColor(cell.wishStatus),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = wish.typeId?.let { types[it]?.code ?: "?" } ?: wish.kind.code,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
            val content = if (cell.typeId != null) color.content else MaterialTheme.colorScheme.onSurfaceVariant
            // Oben links: zu kurze Ruhezeit (zusätzlich zum roten Rand, auch für Farbenblinde) oder „neu“.
            if (cell.rest != null) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(11.dp),
                )
            } else if (unseen) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            if (cell.wish != null && cell.typeId != null) {
                // Mit Schicht: Punkt in der Farbe des Status, grösser, wenn der Wunsch nicht erfüllt ist.
                val status = cell.wishStatus
                IndicatorDot(
                    wishColor(status),
                    Modifier.align(Alignment.TopEnd).padding(3.dp),
                    size = if (status == WishStatus.UNMET) 9.dp else 6.dp,
                )
            }
            if (pending) {
                Icon(
                    painterResource(R.drawable.ic_cloud_upload),
                    contentDescription = null,
                    tint = content.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(10.dp),
                )
            } else if (cell.offered) {
                Icon(
                    painterResource(R.drawable.ic_swap_horiz),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(11.dp),
                )
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
internal fun cellDescription(
    cell: Cell,
    memberName: String,
    date: LocalDate,
    types: ShiftTypeSet,
    unseen: Boolean = false,
    pending: Boolean = false,
): String {
    val type = cell.type
    val shift = when {
        type != null -> type.name + (Format.timeRange(type)?.let { ", $it" } ?: "")
        cell.typeId != null -> stringResource(R.string.shift_unknown)
        else -> stringResource(R.string.cell_empty)
    }
    val wishText = cell.wish?.let { wish ->
        val status = cell.wishStatus
        if (status != null && cell.typeId != null) {
            stringResource(R.string.wish_with_status, wish.label(types), wishStatusText(status))
        } else {
            wish.label(types)
        }
    }
    val restText = cell.rest?.let { stringResource(R.string.rest_marker) + " (" + Format.hours(it.restMinutes.coerceAtLeast(0)) + ")" }
    val newText = stringResource(R.string.marker_unseen)
    val pendingText = stringResource(R.string.marker_pending)
    val offeredText = stringResource(R.string.marker_offered)
    val extras = buildList {
        if (unseen) add(newText)
        restText?.let { add(it) }
        if (cell.offered) add(offeredText)
        wishText?.let { add(it) }
        cell.note?.let { add(it) }
        if (pending) add(pendingText)
    }
    return stringResource(R.string.cell_description, memberName, WeekFormat.longDate(date), (listOf(shift) + extras).joinToString(", "))
}

@Composable
private fun EmptyMembers(readOnly: Boolean, onAddMember: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = R.drawable.ic_group,
        title = stringResource(R.string.empty_members_title),
        text = stringResource(R.string.empty_members),
        modifier = modifier,
        action = if (readOnly) {
            null
        } else {
            stringResource(R.string.action_add_member) to onAddMember
        },
    )
}

/** Besetzung pro Tag: Anzahl je Arbeitsschicht in ihrer Farbe, bei vielen Arten die Summe. */
@Composable
private fun CoverageFooter(model: WeekModel, metrics: GridMetrics) {
    val palette = LocalShiftPalette.current
    val types = model.coverageTypes
    val single = types.size <= MAX_COVERAGE_TYPES
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(metrics.nameWidth).padding(start = if (metrics.narrow) 8.dp else 16.dp, end = 4.dp)) {
                Text(stringResource(R.string.coverage_label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (single && types.isNotEmpty() && !metrics.narrow) {
                    Row {
                        types.forEachIndexed { i, type ->
                            if (i > 0) Text(" · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(type.code, style = MaterialTheme.typography.labelSmall, color = palette.of(type).strong, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (!single) {
                    Text(
                        stringResource(R.string.coverage_total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    Format.hours(model.totalMinutes),
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            model.days.forEachIndexed { index, day ->
                val coverage = model.coverage[index]
                val parts = coverage.counts.map { (type, count) ->
                    val target = coverage.target(type.id)
                    if (target > 0) stringResource(R.string.coverage_target_description, type.name, count, target) else "${type.name} $count"
                }
                val description = stringResource(
                    R.string.coverage_description,
                    WeekFormat.longDate(day.date),
                    parts.joinToString(", ").ifEmpty { "0" },
                )
                Column(
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (single) {
                        for ((type, count) in coverage.counts) {
                            val target = coverage.target(type.id)
                            val under = count < target
                            Text(
                                text = if (target > 0) "$count/$target" else count.toString(),
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                fontWeight = if (count > 0 || under) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    under -> MaterialTheme.colorScheme.error
                                    count > 0 -> palette.of(type).strong
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                maxLines = 1,
                            )
                        }
                    } else {
                        val under = coverage.shortfall > 0
                        Text(
                            text = if (coverage.hasTargets) "${coverage.total}/${coverage.totalTarget}" else coverage.total.toString(),
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = when {
                                under -> MaterialTheme.colorScheme.error
                                coverage.total > 0 -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Kurze Zusammenfassung über dem Plan: Schichten unter dem Soll und zu kurze Ruhezeiten. */
@Composable
internal fun PlanChecks(understaffed: Int, restIssues: Int, modifier: Modifier = Modifier) {
    if (understaffed == 0 && restIssues == 0) return
    val resources = LocalResources.current
    val text = listOfNotNull(
        understaffed.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.checks_understaffed, it, it) },
        restIssues.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.checks_rest, it, it) },
    ).joinToString(" · ")
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
}
