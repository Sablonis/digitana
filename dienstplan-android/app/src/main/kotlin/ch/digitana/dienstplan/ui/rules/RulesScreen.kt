package ch.digitana.dienstplan.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanRules
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.components.ShiftBadge
import ch.digitana.dienstplan.ui.plan.LockHint
import ch.digitana.dienstplan.ui.plan.PlanMessages
import ch.digitana.dienstplan.ui.plan.PlanViewModel
import ch.digitana.dienstplan.ui.plan.kindLabel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Planungsregeln des Teams: Mindestruhezeit zwischen zwei Diensten und Soll-Besetzung pro
 * Arbeitsschicht und Wochentag. Änderungen gelten erst mit „Speichern“.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: PlanViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val editable = state.canEditShiftTypes
    val workTypes = remember(state.plan) { state.plan.shiftTypes.active.filter { it.kind == ShiftKind.WORK } }
    val storedTargets = state.plan.targets
    // Entwürfe: nur, was geändert wurde (Ruhezeit in Minuten, Soll als Text pro Wochentag).
    var restDraft by remember { mutableStateOf<Int?>(null) }
    val targetDrafts = remember { mutableStateMapOf<String, List<String>>() }
    val rest = restDraft ?: state.plan.restMinutes
    fun storedTexts(typeId: String): List<String> = (storedTargets[typeId] ?: List(7) { 0 }).map { if (it == 0) "" else it.toString() }
    fun parsed(texts: List<String>): List<Int> = texts.map { it.toIntOrNull()?.coerceIn(0, PlanRules.MAX_TARGET) ?: 0 }
    val changedTargets = targetDrafts.filter { (typeId, texts) -> parsed(texts) != parsed(storedTexts(typeId)) }
    val restChanged = restDraft != null && restDraft != state.plan.restMinutes
    val changed = restChanged || changedTargets.isNotEmpty()

    PlanMessages(viewModel, snackbar, resources)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (restChanged) viewModel.setRestMinutes(rest)
                            for ((typeId, texts) in changedTargets) viewModel.setTargets(typeId, parsed(texts))
                            restDraft = null
                            targetDrafts.clear()
                            scope.launch { snackbar.showSnackbar(resources.getString(R.string.rules_saved)) }
                        },
                        enabled = editable && changed,
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!editable && !state.readOnly) {
                item { LockHint(stringResource(R.string.rules_locked)) }
            }
            item {
                RestCard(
                    minutes = rest,
                    enabled = editable,
                    onChange = { restDraft = it },
                )
            }
            item {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.rules_targets_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.rules_targets_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (workTypes.isEmpty()) {
                item {
                    Text(stringResource(R.string.rules_targets_none), style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(workTypes, key = { it.id }) { type ->
                TargetCard(
                    type = type,
                    texts = targetDrafts[type.id] ?: storedTexts(type.id),
                    enabled = editable,
                    onChange = { targetDrafts[type.id] = it },
                )
            }
        }
    }
}

/** Mindestruhezeit: ein- oder ausschalten und in halben Stunden zwischen 4 und 16 Stunden wählen. */
@Composable
private fun RestCard(minutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val on = minutes > 0
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rules_rest_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rules_rest_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rules_rest_warn), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = { onChange(if (it) PlanRules.DEFAULT_REST_MINUTES else 0) },
                    enabled = enabled,
                )
            }
            if (on) {
                val shorter = stringResource(R.string.rules_rest_shorter)
                val longer = stringResource(R.string.rules_rest_longer)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { onChange((minutes - PlanRules.REST_STEP_MINUTES).coerceAtLeast(MIN_REST_MINUTES)) },
                        enabled = enabled && minutes > MIN_REST_MINUTES,
                        modifier = Modifier.semantics { contentDescription = shorter },
                    ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text(
                        Format.hours(minutes),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(120.dp),
                    )
                    TextButton(
                        onClick = { onChange((minutes + PlanRules.REST_STEP_MINUTES).coerceAtMost(PlanRules.MAX_REST_MINUTES)) },
                        enabled = enabled && minutes < PlanRules.MAX_REST_MINUTES,
                        modifier = Modifier.semantics { contentDescription = longer },
                    ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

/** Soll einer Arbeitsschicht pro Wochentag, Montag zuerst. */
@Composable
private fun TargetCard(type: ShiftType, texts: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    val monday = LocalDate.of(2024, 1, 1) // ein Montag, nur für die Beschriftung
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShiftBadge(type = type, typeId = type.id, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(type.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Format.timeRange(type) ?: kindLabel(type.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 7) {
                    val dayName = WeekFormat.weekday(monday.plusDays(i.toLong()))
                    val description = stringResource(R.string.rules_targets_field, type.name, dayName)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        BasicTextField(
                            value = texts[i],
                            onValueChange = { input ->
                                val digits = input.filter(Char::isDigit).take(2)
                                onChange(texts.toMutableList().also { it[i] = digits })
                            },
                            enabled = enabled,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .semantics { contentDescription = description },
                        )
                    }
                }
            }
            TextButton(onClick = { onChange(List(7) { texts[0] }) }, enabled = enabled && texts[0].isNotEmpty()) {
                Text(stringResource(R.string.rules_targets_copy))
            }
        }
    }
}

private const val MIN_REST_MINUTES = 4 * 60
