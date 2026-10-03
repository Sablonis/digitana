package ch.digitana.dienstplan.ui.me

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.crdt.WishStatus
import ch.digitana.dienstplan.core.plan.MonthModel
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.plan.LockHint
import ch.digitana.dienstplan.ui.plan.wishColor
import ch.digitana.dienstplan.ui.plan.wishStatusText
import java.time.LocalDate
import java.time.YearMonth

/**
 * „Meine Wünsche“: einen Wunsch wählen und Tage im Monatskalender antippen – schneller als
 * jedes Feld einzeln. Standard ist der nächste Monat, für den meistens gerade geplant wird.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WishCalendar(
    plan: PlanState,
    memberId: String,
    memberName: String,
    today: LocalDate,
    editable: Boolean,
    onSetWish: (LocalDate, Wish?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today).plusMonths(1)) }
    // Code des gewählten Wunsches; leer = entfernen.
    var brush by rememberSaveable { mutableStateOf(Wish.DAY_OFF.code) }
    var choosingShift by remember { mutableStateOf(false) }
    val types = plan.shiftTypes
    val brushWish = Wish.fromCode(brush)

    ElevatedCard(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_favorite),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.me_wishes_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > MonthModel.FIRST_MONTH) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.month_previous))
                }
                Text(Format.monthLabel(month), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < MonthModel.LAST_MONTH) {
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.month_next))
                }
            }
            if (editable) {
                Text(
                    stringResource(R.string.me_wishes_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (option in Wish.SIMPLE) {
                        FilterChip(
                            selected = brush == option.code,
                            onClick = {
                                brush = option.code
                                choosingShift = false
                            },
                            label = { Text(option.kind.label) },
                        )
                    }
                    val shiftBrush = brushWish?.typeId != null
                    FilterChip(
                        selected = shiftBrush || choosingShift,
                        onClick = { choosingShift = !choosingShift },
                        label = { Text(if (brushWish != null && shiftBrush) brushWish.label(types) else stringResource(R.string.wish_shift)) },
                    )
                    FilterChip(
                        selected = brush.isEmpty(),
                        onClick = {
                            brush = ""
                            choosingShift = false
                        },
                        label = { Text(stringResource(R.string.me_wish_clear)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
                if (choosingShift) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (type in types.active.filter { it.kind == ShiftKind.WORK }) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClickLabel = type.name) {
                                        brush = Wish.shift(type.id).code
                                        choosingShift = false
                                    }
                                    .padding(2.dp),
                            ) {
                                ShiftBadge(type = type, typeId = type.id, size = 36.dp)
                            }
                        }
                    }
                }
            } else {
                LockHint(stringResource(R.string.sheet_wish_locked, memberName))
            }

            Row(Modifier.fillMaxWidth()) {
                val monday = month.atDay(1).minusDays(month.atDay(1).dayOfWeek.value - 1L)
                for (i in 0L..6L) {
                    Text(
                        WeekFormat.weekday(monday.plusDays(i)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val length = month.lengthOfMonth()
            val rows = (offset + length + 6) / 7
            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (column in 0 until 7) {
                        val dayOfMonth = row * 7 + column - offset + 1
                        if (dayOfMonth < 1 || dayOfMonth > length) {
                            Spacer(Modifier.weight(1f).height(48.dp))
                            continue
                        }
                        val date = month.atDay(dayOfMonth)
                        val valid = PlanKeys.isValidDate(date)
                        val current = if (valid) plan.wish(memberId, date) else null
                        val typeId = if (valid) plan.shift(memberId, date) else null
                        WishDay(
                            date = date,
                            wish = current,
                            status = current?.status(typeId, types[typeId]),
                            types = types,
                            isToday = date == today,
                            enabled = editable && valid,
                            onClick = {
                                val next = when {
                                    brushWish == null -> null
                                    current == brushWish -> null
                                    else -> brushWish
                                }
                                if (next != current) onSetWish(date, next)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WishDay(
    date: LocalDate,
    wish: Wish?,
    status: WishStatus?,
    types: ShiftTypeSet,
    isToday: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = buildString {
        append(WeekFormat.longDate(date))
        if (wish != null) append(", ").append(wish.label(types))
    }
    val statusText = status?.takeIf { it != WishStatus.OPEN }?.let { wishStatusText(it) }
    val unmet = status == WishStatus.UNMET
    Box(modifier.height(48.dp).padding(2.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(if (wish != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    BorderStroke(
                        if (unmet || isToday) 1.5.dp else 0.dp,
                        when {
                            unmet -> MaterialTheme.colorScheme.error
                            isToday -> MaterialTheme.colorScheme.primary
                            else -> Color.Transparent
                        },
                    ),
                    RoundedCornerShape(10.dp),
                )
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = if (statusText != null) "$description, $statusText" else description },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (wish != null) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            if (wish != null) {
                Text(
                    wish.typeId?.let { types[it]?.code ?: "?" } ?: wish.kind.code,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status == WishStatus.OPEN || status == null) MaterialTheme.colorScheme.onTertiaryContainer else wishColor(status),
                    maxLines = 1,
                )
            }
        }
    }
}
