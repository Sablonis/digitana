package ch.digitana.dienstplan.ui.types

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.plan.LockHint
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.plan.SheetLabel
import ch.digitana.dienstplan.ui.plan.kindLabel
import ch.digitana.dienstplan.ui.theme.LocalShiftPalette
import ch.digitana.dienstplan.ui.theme.ShiftColorNames
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime

/** Schichtarten des Teams: anlegen, ändern, archivieren, Standardarten zurücksetzen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftTypesScreen(viewModel: PlanViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val types = state.plan.shiftTypes
    // Während einer Sperre ändern nur Admins die Schichtarten (sie verändern den gesperrten Plan mit).
    val editable = state.canEditShiftTypes
    var editing by remember { mutableStateOf<ShiftType?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.types_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (editable) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.types_new)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.types_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (!editable && !state.readOnly) {
                    LockHint(stringResource(R.string.types_locked), Modifier.padding(bottom = 8.dp))
                }
            }
            items(types.active, key = { it.id }) { type ->
                TypeRow(type, enabled = editable) { editing = type }
            }
            val archived = types.all.filter { it.archived }
            if (archived.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.types_archived),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(archived, key = { "a-" + it.id }) { type ->
                    TypeRow(type, enabled = editable) { editing = type }
                }
            }
        }
    }

    if (creating) {
        TypeEditor(
            initial = null,
            existingCodes = types.active.map { it.code },
            onSave = { draft ->
                viewModel.saveShiftType(draft.toType(viewModel.newShiftTypeId(), archived = false))
                creating = false
            },
            onArchive = null,
            onReset = null,
            onDismiss = { creating = false },
        )
    }
    editing?.let { type ->
        val isModifiedDefault = type.isBuiltIn && ShiftTypes.default(type.id) != type
        TypeEditor(
            initial = type,
            existingCodes = types.active.filter { it.id != type.id }.map { it.code },
            onSave = { draft ->
                viewModel.saveShiftType(draft.toType(type.id, type.archived))
                editing = null
            },
            onArchive = {
                viewModel.saveShiftType(type.copy(archived = !type.archived))
                editing = null
            },
            onReset = if (isModifiedDefault) {
                {
                    viewModel.resetShiftType(type.id)
                    editing = null
                }
            } else {
                null
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeRow(type: ShiftType, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShiftBadge(type = type, typeId = type.id, size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(type.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(Format.timeRange(type) ?: kindLabel(type.kind), kindHint(type)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Format.hours(type.paidMinutes), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun kindHint(type: ShiftType): String? = when {
    type.kind == ShiftKind.WORK && type.hasTimes && type.breakMinutes > 0 -> stringResource(R.string.types_break_short, type.breakMinutes)
    type.kind == ShiftKind.ABSENCE && type.hasTimes -> kindLabel(type.kind)
    type.kind == ShiftKind.OFF && type.hasTimes -> kindLabel(type.kind)
    else -> null
}

/** Eingaben im Editor, bevor daraus eine gültige Schichtart wird. */
private data class TypeDraft(
    val code: String,
    val name: String,
    val kind: ShiftKind,
    val withTimes: Boolean,
    val start: LocalTime,
    val end: LocalTime,
    val breakText: String,
    val creditText: String,
    val color: Int,
) {
    val normalizedName: String get() = Names.normalizeInput(name)
    val breakMinutes: Int? get() = breakText.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..ShiftTypes.MAX_BREAK_MINUTES }
    val creditMinutes: Int?
        get() {
            val hours = creditText.trim().replace(',', '.').ifEmpty { "0" }.toBigDecimalOrNull() ?: return null
            val minutes = hours.multiply(BigDecimal(60)).setScale(0, RoundingMode.HALF_UP).toInt()
            return minutes.takeIf { it in 0..ShiftTypes.MAX_CREDIT_MINUTES }
        }

    val codeValid: Boolean get() = ShiftTypes.isValidCode(code)
    val nameValid: Boolean get() = ShiftTypes.isValidName(normalizedName)
    val valid: Boolean get() = codeValid && nameValid && (if (withTimes) breakMinutes != null else creditMinutes != null)

    fun toType(id: String, archived: Boolean): ShiftType = ShiftType(
        id = id,
        code = code,
        name = normalizedName,
        start = if (withTimes) start else null,
        end = if (withTimes) end else null,
        breakMinutes = if (withTimes) breakMinutes ?: 0 else 0,
        kind = kind,
        color = color,
        creditMinutes = if (withTimes) 0 else creditMinutes ?: 0,
        archived = archived,
    )

    /** Bezahlte Minuten für die Vorschau; null, solange etwas ungültig ist. */
    fun previewMinutes(): Int? = if (valid) toType("F", archived = false).paidMinutes else null

    companion object {
        fun of(type: ShiftType?): TypeDraft = TypeDraft(
            code = type?.code.orEmpty(),
            name = type?.name.orEmpty(),
            kind = type?.kind ?: ShiftKind.WORK,
            withTimes = type?.hasTimes ?: true,
            start = type?.start ?: LocalTime.of(8, 0),
            end = type?.end ?: LocalTime.of(17, 0),
            breakText = type?.breakMinutes?.takeIf { it > 0 }?.toString().orEmpty(),
            creditText = type?.creditMinutes?.takeIf { it > 0 }?.let { Format.hoursNumber(it) }.orEmpty(),
            color = type?.color ?: 0,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TypeEditor(
    initial: ShiftType?,
    existingCodes: List<String>,
    onSave: (TypeDraft) -> Unit,
    onArchive: (() -> Unit)?,
    onReset: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(initial) { mutableStateOf(TypeDraft.of(initial)) }
    var pickTime by remember { mutableStateOf<Boolean?>(null) } // true = Beginn, false = Ende
    val palette = LocalShiftPalette.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val previewType = if (draft.valid) draft.toType(initial?.id ?: "F", archived = false) else null
                ShiftBadge(type = previewType, typeId = "preview", size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        stringResource(if (initial == null) R.string.types_new else R.string.types_edit),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val minutes = draft.previewMinutes()
                    if (minutes != null) {
                        Text(
                            stringResource(R.string.types_counts_as, Format.hours(minutes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.code,
                    onValueChange = { input -> draft = draft.copy(code = input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(3)) },
                    label = { Text(stringResource(R.string.types_code)) },
                    singleLine = true,
                    isError = draft.code.isNotEmpty() && !draft.codeValid,
                    supportingText = {
                        if (draft.code in existingCodes) Text(stringResource(R.string.types_code_duplicate))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.width(110.dp),
                )
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(120)) },
                    label = { Text(stringResource(R.string.types_name)) },
                    singleLine = true,
                    isError = draft.name.isNotEmpty() && !draft.nameValid,
                    supportingText = { Text(stringResource(R.string.member_name_counter, Names.length(draft.normalizedName), ShiftTypes.MAX_NAME_LENGTH)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
            }

            SheetLabel(stringResource(R.string.types_kind))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in ShiftKind.entries) {
                    FilterChip(
                        selected = draft.kind == kind,
                        onClick = { draft = draft.copy(kind = kind) },
                        label = { Text(kindName(kind)) },
                    )
                }
            }
            Text(
                stringResource(
                    when (draft.kind) {
                        ShiftKind.WORK -> R.string.types_kind_work_hint
                        ShiftKind.OFF -> R.string.types_kind_off_hint
                        ShiftKind.ABSENCE -> R.string.types_kind_absence_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.types_with_times), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = draft.withTimes, onCheckedChange = { draft = draft.copy(withTimes = it) })
            }
            if (draft.withTimes) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.types_start, ShiftTypes.formatTime(draft.start)))
                    }
                    OutlinedButton(onClick = { pickTime = false }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.types_end, ShiftTypes.formatTime(draft.end)))
                    }
                }
                OutlinedTextField(
                    value = draft.breakText,
                    onValueChange = { input -> draft = draft.copy(breakText = input.filter { it.isDigit() }.take(3)) },
                    label = { Text(stringResource(R.string.types_break)) },
                    singleLine = true,
                    isError = draft.breakMinutes == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = draft.creditText,
                    onValueChange = { input -> draft = draft.copy(creditText = input.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
                    label = { Text(stringResource(R.string.types_credit)) },
                    supportingText = { Text(stringResource(R.string.types_credit_hint)) },
                    singleLine = true,
                    isError = draft.creditMinutes == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SheetLabel(stringResource(R.string.types_color))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.colors.forEachIndexed { index, color ->
                    val selected = draft.color == index
                    val name = ShiftColorNames.getOrElse(index) { index.toString() }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color.container)
                            .border(if (selected) 3.dp else 1.dp, if (selected) color.strong else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable(onClickLabel = name) { draft = draft.copy(color = index) }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = color.content, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onReset != null) TextButton(onClick = onReset) { Text(stringResource(R.string.types_reset)) }
                if (onArchive != null && initial != null) {
                    TextButton(onClick = onArchive) {
                        Icon(painterResource(R.drawable.ic_archive), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (initial.archived) R.string.types_restore else R.string.types_archive))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(draft) }, enabled = draft.valid) {
                    Text(stringResource(R.string.action_save))
                }
            }
            if (initial != null && !initial.archived) {
                Text(
                    stringResource(R.string.types_archive_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pickTime?.let { isStart ->
        TimeDialog(
            initial = if (isStart) draft.start else draft.end,
            title = stringResource(if (isStart) R.string.types_start_title else R.string.types_end_title),
            onConfirm = { time ->
                draft = if (isStart) draft.copy(start = time) else draft.copy(end = time)
                pickTime = null
            },
            onDismiss = { pickTime = null },
        )
    }
}

@Composable
private fun kindName(kind: ShiftKind): String = when (kind) {
    ShiftKind.WORK -> stringResource(R.string.kind_work)
    ShiftKind.OFF -> stringResource(R.string.kind_off)
    ShiftKind.ABSENCE -> stringResource(R.string.kind_absence)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, title: String, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors()) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
