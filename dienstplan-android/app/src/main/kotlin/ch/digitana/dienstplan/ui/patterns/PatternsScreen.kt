package ch.digitana.dienstplan.ui.patterns

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.Member
import ch.digitana.dienstplan.core.crdt.Names
import ch.digitana.dienstplan.core.crdt.ShiftPattern
import ch.digitana.dienstplan.core.crdt.ShiftPatterns
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.plan.PatternPlanner
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.MemberAvatar
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.plan.LockHint
import ch.digitana.dienstplan.ui.plan.MenuItem
import ch.digitana.dienstplan.ui.plan.PlanMessages
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.plan.SheetLabel
import ch.digitana.dienstplan.ui.plan.lockDateLabel
import ch.digitana.dienstplan.ui.theme.LocalShiftPalette

private val WEEKDAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/** Schichtrhythmen: anlegen, bearbeiten und auf Personen und Wochen anwenden. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternsScreen(viewModel: PlanViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val patterns = remember(state.plan) { state.plan.patterns() }
    var editing by remember { mutableStateOf<ShiftPattern?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var applying by remember { mutableStateOf<ShiftPattern?>(null) }
    var deleting by remember { mutableStateOf<ShiftPattern?>(null) }
    // Gesperrte Tage dürfen Mitglieder nicht überschreiben: frühestens die Woche nach der Sperre.
    val restriction = state.lock?.takeIf { !state.isAdmin }
    val restrictedUntil = restriction?.until
    val canApply = !state.readOnly && (restriction == null || restrictedUntil != null)
    val earliestWeek = restrictedUntil?.let { until -> WeekId.of(until.plusDays(1)).let { if (it.monday.isAfter(until)) it else it.next() } }

    PlanMessages(viewModel, snackbar, resources)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.patterns_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.readOnly) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.patterns_new)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.patterns_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                if (restriction != null && !state.readOnly) {
                    LockHint(
                        if (restrictedUntil == null) {
                            stringResource(R.string.patterns_locked_whole)
                        } else {
                            stringResource(R.string.patterns_locked_until, lockDateLabel(restrictedUntil))
                        },
                        Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            if (patterns.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.patterns_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(patterns, key = { it.id }) { pattern ->
                PatternCard(
                    pattern = pattern,
                    types = state.plan.shiftTypes,
                    readOnly = state.readOnly,
                    canApply = canApply,
                    onApply = { applying = pattern },
                    onEdit = { editing = pattern },
                    onDelete = { deleting = pattern },
                )
            }
        }
    }

    if (creating) {
        PatternEditor(
            initial = null,
            types = state.plan.shiftTypes,
            onSave = { name, days ->
                viewModel.savePattern(ShiftPattern(viewModel.newPatternId(), name, days))
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    editing?.let { pattern ->
        PatternEditor(
            initial = pattern,
            types = state.plan.shiftTypes,
            onSave = { name, days ->
                viewModel.savePattern(ShiftPattern(pattern.id, name, days))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    applying?.let { pattern ->
        val nextWeek = WeekId.of(state.today).next()
        ApplyDialog(
            pattern = pattern,
            members = state.plan.members(),
            firstWeek = earliestWeek?.let { maxOf(it, nextWeek) } ?: nextWeek,
            earliestWeek = earliestWeek,
            onApply = { memberIds, start, weeks, overwrite ->
                viewModel.applyPattern(pattern, memberIds, start.monday, weeks, overwrite)
                applying = null
            },
            onDismiss = { applying = null },
        )
    }
    deleting?.let { pattern ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.patterns_delete_title, pattern.name)) },
            text = { Text(stringResource(R.string.patterns_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePattern(pattern.id)
                        deleting = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PatternCard(
    pattern: ShiftPattern,
    types: ShiftTypeSet,
    readOnly: Boolean,
    canApply: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pattern.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.patterns_weeks, pattern.weeks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!readOnly) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            MenuItem(R.string.patterns_edit, R.drawable.ic_edit) {
                                menuOpen = false
                                onEdit()
                            }
                            MenuItem(R.string.action_delete, R.drawable.ic_delete) {
                                menuOpen = false
                                onDelete()
                            }
                        }
                    }
                }
            }
            PatternPreview(pattern.days, types)
            if (!readOnly) {
                Button(onClick = onApply, enabled = canApply) {
                    Icon(painterResource(R.drawable.ic_repeat), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.patterns_apply))
                }
            }
        }
    }
}

/** Kleine Vorschau: eine Zeile pro Woche mit farbigen Feldern. */
@Composable
private fun PatternPreview(days: List<String?>, types: ShiftTypeSet) {
    val palette = LocalShiftPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { typeId ->
                    val type = types[typeId]
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (typeId != null) palette.of(type).container else MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (typeId != null) {
                            Text(
                                type?.code ?: "?",
                                color = palette.of(type).content,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Rhythmus bearbeiten: Schichtart als „Pinsel“ wählen, dann Tage antippen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PatternEditor(
    initial: ShiftPattern?,
    types: ShiftTypeSet,
    onSave: (String, List<String?>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    val days = remember(initial) { mutableStateListOf<String?>().apply { addAll(initial?.days ?: List(7) { null }) } }
    var brush by remember { mutableStateOf(types.active.firstOrNull()?.id) }
    val palette = LocalShiftPalette.current
    val normalized = Names.normalizeInput(name)
    val nameValid = ShiftPatterns.isValidName(normalized)
    val weeks = days.size / 7

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (initial == null) R.string.patterns_new else R.string.patterns_edit),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                label = { Text(stringResource(R.string.patterns_name)) },
                placeholder = { Text(stringResource(R.string.patterns_name_hint)) },
                singleLine = true,
                isError = name.isNotEmpty() && !nameValid,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.patterns_weeks, weeks), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { repeat(7) { days.removeAt(days.lastIndex) } }, enabled = weeks > 1) { Text("−") }
                TextButton(onClick = { repeat(7) { days.add(null) } }, enabled = weeks < ShiftPatterns.MAX_WEEKS) { Text("+") }
            }

            SheetLabel(stringResource(R.string.patterns_brush))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (type in types.active) {
                    FilterChip(
                        selected = brush == type.id,
                        onClick = { brush = type.id },
                        label = { Text(type.name) },
                        leadingIcon = { ShiftBadge(type = type, typeId = type.id, size = 20.dp) },
                    )
                }
                FilterChip(
                    selected = brush == null,
                    onClick = { brush = null },
                    label = { Text(stringResource(R.string.patterns_brush_empty)) },
                )
            }

            Row(Modifier.fillMaxWidth()) {
                for (label in WEEKDAYS) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            for (week in 0 until weeks) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (day in 0 until 7) {
                        val index = week * 7 + day
                        val typeId = days[index]
                        val type = types[typeId]
                        val description = "${WEEKDAYS[day]}, ${type?.name ?: stringResource(R.string.cell_empty)}"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (typeId != null) palette.of(type).container else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .clickable(onClickLabel = stringResource(R.string.patterns_paint)) {
                                    days[index] = if (days[index] == brush) null else brush
                                }
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (typeId != null) {
                                Text(type?.code ?: "?", color = palette.of(type).content, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.patterns_paint_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(normalized, days.toList()) }, enabled = nameValid) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/** Rhythmus anwenden: Personen, Startwoche, Dauer und ob bestehende Einträge überschrieben werden. */
@Composable
private fun ApplyDialog(
    pattern: ShiftPattern,
    members: List<Member>,
    firstWeek: WeekId,
    /** Frühestmögliche Startwoche (Sperre); null = frei wählbar. */
    earliestWeek: WeekId?,
    onApply: (List<String>, WeekId, Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    var start by remember { mutableStateOf(firstWeek) }
    var weeks by remember { mutableIntStateOf(maxOf(pattern.weeks, 4).coerceAtMost(PatternPlanner.MAX_WEEKS)) }
    var overwrite by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.patterns_apply_title, pattern.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.patterns_apply_people), style = MaterialTheme.typography.labelLarge)
                if (members.isEmpty()) Text(stringResource(R.string.notify_no_members), style = MaterialTheme.typography.bodySmall)
                for (member in members) {
                    val checked = member.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) {
                                if (it) selected.add(member.id) else selected.remove(member.id)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        MemberAvatar(member.name, member.id, size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.patterns_apply_start), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { start = start.previous() }, enabled = earliestWeek == null || start > earliestWeek) {
                        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.week_previous))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(WeekFormat.weekLabel(start), style = MaterialTheme.typography.titleMedium)
                        Text(WeekFormat.rangeLabel(start), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { start = start.next() }) {
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.week_next))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.patterns_apply_weeks, weeks), modifier = Modifier.weight(1f))
                    TextButton(onClick = { weeks-- }, enabled = weeks > 1) { Text("−") }
                    TextButton(onClick = { weeks++ }, enabled = weeks < PatternPlanner.MAX_WEEKS) { Text("+") }
                }
                HorizontalDivider()
                for (mode in listOf(false, true)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = overwrite == mode, role = Role.RadioButton) { overwrite = mode }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = overwrite == mode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (mode) R.string.patterns_mode_overwrite else R.string.patterns_mode_fill))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected.toList(), start, weeks, overwrite) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.patterns_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
