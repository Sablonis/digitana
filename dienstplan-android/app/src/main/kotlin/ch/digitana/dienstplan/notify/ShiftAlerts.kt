package ch.digitana.dienstplan.notify

import ch.digitana.dienstplan.core.data.PlanRepository
import ch.digitana.dienstplan.core.data.SettingsRepository
import ch.digitana.dienstplan.core.plan.ChangeAlert
import ch.digitana.dienstplan.core.plan.ShiftWatch
import java.time.LocalDate

/** Verbindet die Regeln aus [ShiftWatch] mit Einstellungen, Plan und Benachrichtigung. */
class ShiftAlerts(
    private val settings: SettingsRepository,
    private val plan: PlanRepository,
    private val notifications: ShiftNotifications,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    /** Die Person hat den Plan gesehen (App geöffnet oder verlassen). */
    suspend fun planSeen() {
        settings.update { ShiftWatch.seen(it, plan.state.value, today()) }
        notifications.cancel()
    }

    /** „Das bin ich“ oder der Schalter wurde geändert: neue Grundlage, keine Meldung. */
    suspend fun configure(myMemberId: String?, notify: Boolean) {
        settings.update {
            val changed = it.copy(myMemberId = myMemberId, notifyOnChanges = notify && myMemberId != null)
            ShiftWatch.seen(changed, plan.state.value, today())
        }
        notifications.cancel()
    }

    /** Nach einem Abgleich im Hintergrund: Benachrichtigung zeigen, aktualisieren oder schliessen. */
    suspend fun afterBackgroundSync() {
        var alert: ChangeAlert = ChangeAlert.None
        settings.update { current ->
            val (next, decided) = ShiftWatch.afterSync(current, plan.state.value, today())
            alert = decided
            next
        }
        when (val decided = alert) {
            is ChangeAlert.Show -> notifications.show(decided.changes, plan.state.value.shiftTypes)
            ChangeAlert.Cancel -> notifications.cancel()
            ChangeAlert.None -> Unit
        }
    }
}
