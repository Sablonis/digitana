package ch.digitana.dienstplan.notify

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.digitana.dienstplan.DienstplanApp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.data.DeviceSettings
import ch.digitana.dienstplan.core.plan.ReminderMode
import ch.digitana.dienstplan.core.plan.Reminders
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.MainActivity
import ch.digitana.dienstplan.ui.components.Format
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Erinnerungen rein auf dem Gerät: vor dem nächsten eigenen Dienst und vor einer Wunschfrist.
 * Geplant mit dem AlarmManager (ungenau, aber auch im Energiesparmodus); nach jedem Alarm und
 * jeder Änderung am Plan wird die nächste Erinnerung neu gesetzt. Nach einem Neustart setzt sie
 * der nächste Abgleich im Hintergrund wieder.
 */
class ReminderScheduler(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.reminder_channel_name))
            .setDescription(context.getString(R.string.reminder_channel_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Nächste Erinnerungen setzen oder löschen (idempotent). */
    fun schedule(plan: PlanState, settings: DeviceSettings, now: LocalDateTime = LocalDateTime.now()) {
        val me = settings.myMemberId
        val shift = if (me != null && settings.reminderMode != ReminderMode.OFF) Reminders.nextShift(plan, me, now, settings.reminderMode) else null
        if (shift == null) {
            cancel(KIND_SHIFT)
        } else {
            val type = plan.shiftTypes[shift.typeId]
            val label = listOfNotNull(type?.name ?: context.getString(R.string.shift_unknown), type?.let { Format.timeRange(it) }).joinToString(" · ")
            val title = if (settings.reminderMode == ReminderMode.EVENING_BEFORE) {
                context.getString(R.string.reminder_shift_tomorrow, label)
            } else {
                context.getString(R.string.reminder_shift_soon, label)
            }
            set(KIND_SHIFT, shift.at, title, WeekFormat.longDate(shift.date))
        }
        val deadline = if (settings.deadlineReminders) Reminders.nextDeadline(plan, me, now) else null
        if (deadline == null) {
            cancel(KIND_DEADLINE)
        } else {
            set(
                KIND_DEADLINE,
                deadline.at,
                context.getString(R.string.reminder_deadline_title, Format.monthName(deadline.month)),
                context.getString(R.string.reminder_deadline_text, WeekFormat.longDate(deadline.deadline)),
            )
        }
    }

    fun cancelAll() {
        cancel(KIND_SHIFT)
        cancel(KIND_DEADLINE)
    }

    private fun set(kind: Int, at: LocalDateTime, title: String, text: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Ungenauer Alarm, der auch im Doze-Modus kommt; braucht keine Berechtigung für exakte Alarme.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent(kind, title, text))
    }

    private fun cancel(kind: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pendingIntent(kind, null, null))
    }

    private fun pendingIntent(kind: Int, title: String?, text: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_KIND, kind)
        if (title != null) intent.putExtra(EXTRA_TITLE, title)
        if (text != null) intent.putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(context, kind, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Zeigt eine fällige Erinnerung. Auf dem gesperrten Bildschirm nur ohne Details. */
    fun show(kind: Int, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN + kind,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.reminder_public_text))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        manager.notify(NOTIFICATION_BASE + kind, notification)
    }

    companion object {
        const val CHANNEL_ID = "erinnerungen"
        const val ACTION = "ch.digitana.dienstplan.ERINNERUNG"
        const val EXTRA_KIND = "art"
        const val EXTRA_TITLE = "titel"
        const val EXTRA_TEXT = "text"
        const val KIND_SHIFT = 1
        const val KIND_DEADLINE = 2
        private const val NOTIFICATION_BASE = 100
        private const val REQUEST_OPEN = 200
    }
}

/** Fällige Erinnerung zeigen und die nächste planen. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION) return
        val app = context.applicationContext as? DienstplanApp ?: return
        val container = app.container
        val kind = intent.getIntExtra(ReminderScheduler.EXTRA_KIND, 0)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE)
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT)
        if (title != null && text != null) container.reminders.show(kind, title, text)
        val pending = goAsync()
        container.scope.launch {
            try {
                if (container.awaitReady(READY_TIMEOUT_MILLIS)) {
                    container.reminders.schedule(container.planRepository.state.value, container.settingsRepository.settings.value)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val READY_TIMEOUT_MILLIS = 8_000L
    }
}
