package ch.digitana.dienstplan.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.NameProblem
import ch.digitana.dienstplan.core.crdt.Notes
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.Wish
import ch.digitana.dienstplan.core.crdt.WishStatus
import ch.digitana.dienstplan.core.plan.Cell
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.theme.LocalShiftPalette
import java.time.LocalDate

/**
 * Feld bearbeiten: Schicht wählen, Wunsch setzen, Notiz zum Dienst. [shiftsEditable]: nicht
 * gesperrt oder Admin; [wishesEditable]: eigene Wünsche, Person ohne Gerät oder Admin.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CellSheet(
    member: Member,
    date: LocalDate,
    cell: Cell,
    types: ShiftTypeSet,
    readOnly: Boolean,
    shiftsEditable: Boolean,
    wishesEditable: Boolean,
    noteProblem: (String) -> NameProblem?,
    onSelectType: (String?) -> Unit,
    onWish: (Wish?) -> Unit,
    onSaveNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var choosingShiftWish by rememberSaveable(member.id, date) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(member.name, member.id, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(member.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        WeekFormat.longDate(date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SheetLabel(stringResource(R.string.sheet_shift))
            if (!shiftsEditable && !readOnly) LockHint(stringResource(R.string.sheet_shift_locked))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (type in types.active) {
                    ShiftOption(type = type, typeId = type.id, selected = cell.typeId == type.id, enabled = shiftsEditable) {
                        onSelectType(type.id)
                    }
                }
                // Archivierte oder (noch) unbekannte Art in diesem Feld trotzdem zeigen.
                val currentId = cell.typeId
                if (currentId != null && types.active.none { it.id == currentId }) {
                    ShiftOption(type = cell.type, typeId = currentId, selected = true, enabled = false) {}
                }
            }
            if (cell.typeId != null && shiftsEditable) {
                OutlinedButton(onClick = { onSelectType(null) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sheet_clear))
                }
            }

            SheetLabel(stringResource(R.string.sheet_wish))
            if (!wishesEditable && !readOnly) LockHint(stringResource(R.string.sheet_wish_locked, member.name))
            val wish = cell.wish
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in Wish.SIMPLE) {
                    val selected = wish == option
                    FilterChip(
                        selected = selected,
                        onClick = { onWish(if (selected) null else option) },
                        enabled = wishesEditable,
                        label = { Text(option.kind.label) },
                        leadingIcon = if (selected) {
                            { Icon(painterResource(R.drawable.ic_favorite), contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                    )
                }
                val shiftWish = wish?.typeId != null
                FilterChip(
                    selected = shiftWish || choosingShiftWish,
                    onClick = {
                        if (shiftWish) onWish(null) else choosingShiftWish = !choosingShiftWish
                    },
                    enabled = wishesEditable,
                    label = { Text(if (wish != null && shiftWish) wish.label(types) else stringResource(R.string.wish_shift)) },
                    leadingIcon = if (shiftWish) {
                        { Icon(painterResource(R.drawable.ic_favorite), contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
            AnimatedVisibility(visible = choosingShiftWish && wishesEditable) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wish_shift_choose), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (type in types.active.filter { it.kind == ShiftKind.WORK }) {
                            ShiftOption(type = type, typeId = type.id, selected = false, enabled = true) {
                                choosingShiftWish = false
                                onWish(Wish.shift(type.id))
                            }
                        }
                    }
                }
            }
            val status = cell.wishStatus
            if (wish != null && status != null && cell.typeId != null) {
                WishStatusLine(status)
            }

            SheetLabel(stringResource(R.string.sheet_member_note))
            NoteEditor(
                key = "${member.id}|$date",
                current = cell.note,
                placeholder = stringResource(R.string.sheet_member_note_hint),
                readOnly = readOnly,
                noteProblem = noteProblem,
                onSave = onSaveNote,
            )
        }
    }
}

/** Übersicht eines Tages: wer welche Schicht hat, Wünsche, wer noch fehlt, Notiz zum Tag. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DaySheet(
    plan: PlanState,
    date: LocalDate,
    isToday: Boolean,
    readOnly: Boolean,
    noteProblem: (String) -> NameProblem?,
    onSaveNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val summary = remember(plan, date) { DaySummary.of(plan, date) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WeekFormat.longDate(date), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (isToday) AssistChip(onClick = {}, label = { Text(stringResource(R.string.today_label)) })
            }

            SheetLabel(stringResource(R.string.day_coverage))
            if (summary.groups.isEmpty()) {
                Text(
                    stringResource(R.string.day_nobody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for ((type, members) in summary.groups) {
                Row(verticalAlignment = Alignment.Top) {
                    ShiftBadge(type = type.first, typeId = type.second, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val label = type.first?.let { t -> listOfNotNull(t.name, Format.timeRange(t)).joinToString(" · ") }
                            ?: stringResource(R.string.shift_unknown)
                        Text("$label (${members.size})", style = MaterialTheme.typography.titleSmall)
                        Text(
                            members.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (summary.unassigned.isNotEmpty()) {
                Text(
                    stringResource(R.string.day_unassigned, summary.unassigned.joinToString(", ") { it.name }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (summary.wishes.isNotEmpty()) {
                SheetLabel(stringResource(R.string.day_wishes))
                val types = plan.shiftTypes
                for (entry in summary.wishes) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_favorite),
                            contentDescription = null,
                            tint = wishColor(entry.status),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.wish_with_status, "${entry.member.name}: ${entry.wish.label(types)}", wishStatusText(entry.status)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            SheetLabel(stringResource(R.string.day_note))
            NoteEditor(
                key = date.toString(),
                current = plan.dayNote(date),
                placeholder = stringResource(R.string.day_note_hint),
                readOnly = readOnly,
                noteProblem = noteProblem,
                onSave = onSaveNote,
            )
        }
    }
}

/** Wunsch einer Person an einem Tag und ob der Plan ihn erfüllt. */
internal data class DayWish(val member: Member, val wish: Wish, val status: WishStatus)

/** Wer an einem Tag welche Schicht hat. */
internal data class DaySummary(
    /** (Schichtart, ID) → Personen; Reihenfolge wie die Schichtarten, Unbekannte am Schluss. */
    val groups: List<Pair<Pair<ShiftType?, String>, List<Member>>>,
    val unassigned: List<Member>,
    val wishes: List<DayWish>,
) {
    companion object {
        fun of(plan: PlanState, date: LocalDate): DaySummary {
            val types = plan.shiftTypes
            val members = plan.members()
            val byType = LinkedHashMap<String, MutableList<Member>>()
            val unassigned = ArrayList<Member>()
            for (member in members) {
                val typeId = plan.shift(member.id, date)
                if (typeId == null) unassigned.add(member) else byType.getOrPut(typeId) { ArrayList() }.add(member)
            }
            val order = types.all.map { it.id }
            val groups = byType.entries
                .sortedBy { (id, _) -> order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { (id, list) -> (types[id] to id) to list.toList() }
            val wishes = members.mapNotNull { member ->
                plan.wish(member.id, date)?.let { wish ->
                    val typeId = plan.shift(member.id, date)
                    DayWish(member, wish, wish.status(typeId, types[typeId]))
                }
            }
            return DaySummary(groups, unassigned, wishes)
        }
    }
}

/** Hinweis mit Schloss, z. B. wenn ein Tag gesperrt ist. */
@Composable
internal fun LockHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_lock),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** „erfüllt“, „nicht erfüllt“ oder „offen“ mit passender Farbe. */
@Composable
private fun WishStatusLine(status: WishStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (status == WishStatus.FULFILLED) R.drawable.ic_check else R.drawable.ic_favorite),
            contentDescription = null,
            tint = wishColor(status),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(wishStatusText(status), style = MaterialTheme.typography.bodySmall, color = wishColor(status))
    }
}

@Composable
internal fun wishStatusText(status: WishStatus): String = when (status) {
    WishStatus.FULFILLED -> stringResource(R.string.wish_status_fulfilled)
    WishStatus.UNMET -> stringResource(R.string.wish_status_unmet)
    WishStatus.OPEN -> stringResource(R.string.wish_status_open)
}

/** Erfüllt: Primärfarbe, nicht erfüllt: Fehlerfarbe, offen: Tertiärfarbe. */
@Composable
internal fun wishColor(status: WishStatus?): Color = when (status) {
    WishStatus.FULFILLED -> MaterialTheme.colorScheme.primary
    WishStatus.UNMET -> MaterialTheme.colorScheme.error
    WishStatus.OPEN, null -> MaterialTheme.colorScheme.tertiary
}

@Composable
internal fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Auswahlkarte einer Schichtart: Kürzel, Name, Zeiten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftOption(type: ShiftType?, typeId: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = LocalShiftPalette.current.of(type)
    val name = type?.name ?: stringResource(R.string.shift_unknown)
    val detail = type?.let { Format.timeRange(it) ?: kindLabel(it.kind) } ?: ""
    val selectedDescription = stringResource(R.string.state_selected)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) color.container else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color.strong else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .width(104.dp)
            .semantics { if (selected) contentDescription = "$name, $selectedDescription" },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShiftBadge(type = type, typeId = typeId, size = 34.dp)
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) color.content else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) color.content else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun kindLabel(kind: ShiftKind): String = when (kind) {
    ShiftKind.WORK -> stringResource(R.string.kind_all_day)
    ShiftKind.OFF -> stringResource(R.string.kind_off)
    ShiftKind.ABSENCE -> stringResource(R.string.kind_absence)
}

/** Textfeld für Notizen mit Zähler, Speichern und Löschen. */
@Composable
internal fun NoteEditor(
    key: String,
    current: String?,
    placeholder: String,
    readOnly: Boolean,
    noteProblem: (String) -> NameProblem?,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(key, current) { mutableStateOf(current.orEmpty()) }
    val problem = noteProblem(text)
    val changed = Notes.normalizeInput(text) != current.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_NOTE_INPUT) },
            placeholder = { Text(placeholder) },
            enabled = !readOnly,
            isError = problem != null,
            supportingText = {
                Text(
                    if (problem != null) {
                        noteProblemText(problem)
                    } else {
                        stringResource(R.string.member_name_counter, Notes.normalizeInput(text).codePointCount(0, Notes.normalizeInput(text).length), Notes.MAX_LENGTH)
                    },
                )
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.note_delete)) }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { onSave(text) }, enabled = changed && problem == null) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
internal fun noteProblemText(problem: NameProblem): String = when (problem) {
    NameProblem.EMPTY -> ""
    NameProblem.TOO_LONG -> stringResource(R.string.member_error_too_long, Notes.MAX_LENGTH)
    NameProblem.INVALID_CHARACTERS -> stringResource(R.string.note_error_invalid)
}

private const val MAX_NOTE_INPUT = 400
