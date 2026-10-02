package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.PlanKey
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftTypeSet
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.data.DeviceSettings
import java.time.LocalDate

/** Änderung an einem eigenen Dienst (IDs der Schichtarten); `null` = kein Eintrag. */
data class ShiftChange(val date: LocalDate, val before: String?, val after: String?)

/** Was mit der Benachrichtigung über eigene Dienständerungen geschehen soll. */
sealed interface ChangeAlert {
    data object None : ChangeAlert
    data class Show(val changes: List<ShiftChange>) : ChangeAlert
    data object Cancel : ChangeAlert
}

/**
 * Erkennt Änderungen an den künftigen Diensten der Person, die dieses Gerät benutzt.
 * Grundlage ist der Stand, den die Person zuletzt in der App gesehen hat. Gemeldet werden
 * alle Abweichungen davon, jeder neue Stand nur einmal.
 */
object ShiftWatch {

    /** Künftige Dienste (ab [today]) eines Mitglieds als IDs der Schichtarten; leere Felder fehlen. */
    fun upcomingShifts(state: PlanState, memberId: String, today: LocalDate): Map<LocalDate, String> {
        val firstWeek = WeekId.of(today)
        val prefix = "z|$memberId|"
        val result = HashMap<LocalDate, String>()
        for ((bucket, map) in state.buckets) {
            val week = Buckets.parseWeek(bucket) ?: continue
            if (week < firstWeek) continue
            for ((key, entry) in map.entries) {
                if (!key.startsWith(prefix)) continue
                val parsed = PlanKeys.parse(key) as? PlanKey.Shift ?: continue
                if (parsed.date.isBefore(today)) continue
                if (!ShiftTypes.isValidId(entry.value)) continue
                result[parsed.date] = entry.value
            }
        }
        return result
    }

    /** Abweichungen ab [today], nach Datum sortiert. */
    fun diff(before: Map<LocalDate, String>, after: Map<LocalDate, String>, today: LocalDate): List<ShiftChange> =
        (before.keys + after.keys)
            .filter { !it.isBefore(today) && before[it] != after[it] }
            .sorted()
            .map { ShiftChange(it, before[it], after[it]) }

    /** Die Person hat den Plan gesehen: neue Grundlage, eine offene Benachrichtigung ist erledigt. */
    fun seen(settings: DeviceSettings, state: PlanState, today: LocalDate): DeviceSettings {
        val me = settings.myMemberId ?: return settings.copy(seenShifts = null, notifiedShifts = null)
        return settings.copy(seenShifts = upcomingShifts(state, me, today), notifiedShifts = null)
    }

    /** Nach einem Abgleich im Hintergrund: neue Einstellungen und was mit der Benachrichtigung passiert. */
    fun afterSync(settings: DeviceSettings, state: PlanState, today: LocalDate): Pair<DeviceSettings, ChangeAlert> {
        val me = settings.myMemberId
        if (me == null || !settings.notifyOnChanges) {
            return settings.copy(notifiedShifts = null) to cancelIfOpen(settings)
        }
        val current = upcomingShifts(state, me, today)
        val seen = settings.seenShifts
            ?: return settings.copy(seenShifts = current, notifiedShifts = null) to cancelIfOpen(settings)
        val changes = diff(seen, current, today)
        val alreadyNotified = settings.notifiedShifts?.filterKeys { !it.isBefore(today) }
        return when {
            changes.isEmpty() -> settings.copy(notifiedShifts = null) to cancelIfOpen(settings)
            current == alreadyNotified -> settings to ChangeAlert.None
            else -> settings.copy(notifiedShifts = current) to ChangeAlert.Show(changes)
        }
    }

    private fun cancelIfOpen(settings: DeviceSettings): ChangeAlert =
        if (settings.notifiedShifts != null) ChangeAlert.Cancel else ChangeAlert.None
}

/** Texte für die Benachrichtigung, z. B. „Mo 6.10.: Früh → Spät“. */
object ShiftChangeText {
    fun title(changes: List<ShiftChange>): String =
        if (changes.size == 1) "Dein Dienstplan wurde geändert" else "${changes.size} Änderungen an deinem Dienstplan"

    fun line(change: ShiftChange, types: ShiftTypeSet): String {
        val day = "${WeekFormat.weekday(change.date)} ${WeekFormat.shortDate(change.date)}"
        val before = change.before?.let { label(it, types) }
        val after = change.after?.let { label(it, types) }
        val what = when {
            before != null && after != null -> "$before → $after"
            after != null -> "$after (neu)"
            before != null -> "$before entfällt"
            else -> "unverändert"
        }
        return "$day: $what"
    }

    private fun label(typeId: String, types: ShiftTypeSet): String = types[typeId]?.name ?: "Unbekannte Schicht"
}
