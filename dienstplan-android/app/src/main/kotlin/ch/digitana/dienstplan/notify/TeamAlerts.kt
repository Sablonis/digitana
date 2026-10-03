package ch.digitana.dienstplan.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanLock
import ch.digitana.dienstplan.core.crdt.PlanLockCodec
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.SwapStatus
import ch.digitana.dienstplan.core.data.DeviceSettings
import ch.digitana.dienstplan.core.data.SettingsRepository
import ch.digitana.dienstplan.core.group.TeamState
import ch.digitana.dienstplan.core.plan.TradeEvent
import ch.digitana.dienstplan.core.plan.TradeWatch
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.MainActivity
import java.time.LocalDate

/**
 * Benachrichtigungen rund ums Team, nach einem Abgleich im Hintergrund: Tauschvorschläge,
 * Antworten, abgegebene Dienste, Bestätigungen für Admins und neue Sperren („veröffentlicht“).
 * Jedes Ereignis wird nur einmal gemeldet; was beim Öffnen der App zu sehen war, gilt als
 * bekannt.
 */
class TeamAlerts(
    private val context: Context,
    private val settings: SettingsRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.team_channel_name))
            .setDescription(context.getString(R.string.team_channel_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** App geöffnet oder verlassen: Was jetzt gilt, ist bekannt. */
    suspend fun seen(plan: PlanState, team: TeamState, deviceId: String?) {
        val member = team as? TeamState.Member
        settings.update { current ->
            val isAdmin = deviceId != null && member != null && deviceId in member.team.adminDevices
            val events = TradeWatch.events(plan, current.myMemberId, isAdmin, today())
            current.copy(notifiedTrades = ids(events), knownLock = member?.team?.description ?: current.knownLock)
        }
        NotificationManagerCompat.from(context).cancel(TRADE_NOTIFICATION)
    }

    /** Nach einem Abgleich im Hintergrund: neue Ereignisse melden. */
    suspend fun afterBackgroundSync(plan: PlanState, team: TeamState, deviceId: String?) {
        val member = team as? TeamState.Member ?: return
        val isAdmin = deviceId != null && deviceId in member.team.adminDevices
        var newEvents: List<TradeEvent> = emptyList()
        var published: PlanLock? = null
        settings.update { current ->
            val events = TradeWatch.events(plan, current.myMemberId, isAdmin, today())
            if (current.tradeAlerts) newEvents = events.filter { it.id !in current.notifiedTrades }
            val description = member.team.description
            val before = current.knownLock
            if (current.publishAlerts && before != null && description != before) {
                published = newlyLocked(PlanLockCodec.decode(before), member.team.planLock)
            }
            current.copy(notifiedTrades = ids(events), knownLock = description)
        }
        if (newEvents.isNotEmpty()) showTrades(newEvents, plan)
        published?.let { showPublished(it) }
    }

    private fun ids(events: List<TradeEvent>): Set<String> =
        events.map { it.id }.filter { it.length <= DeviceSettings.MAX_TRADE_ID_LENGTH }.take(DeviceSettings.MAX_NOTIFIED_TRADES).toSet()

    /** Sperre, die jetzt mehr Tage umfasst als vorher (oder neu ist); null = nichts Neues. */
    private fun newlyLocked(before: PlanLock?, after: PlanLock?): PlanLock? {
        if (after == null) return null
        if (before == null) return after
        val oldUntil = before.until
        val newUntil = after.until
        return when {
            oldUntil == null -> null
            newUntil == null || newUntil.isAfter(oldUntil) -> after
            else -> null
        }
    }

    private fun showTrades(events: List<TradeEvent>, plan: PlanState) {
        val names = plan.members().associate { it.id to it.name }
        val unknown = context.getString(R.string.activity_unknown_person)
        fun name(id: String) = names[id] ?: unknown
        fun day(date: LocalDate) = "${WeekFormat.weekday(date)} ${WeekFormat.shortDate(date)}"
        fun type(id: String?) = id?.let { plan.shiftTypes[it]?.name } ?: context.getString(R.string.shift_unknown)
        val lines = events.map { event ->
            when (event) {
                is TradeEvent.SwapProposed -> context.getString(R.string.alert_swap_proposed, name(event.swap.swap.fromMember), type(event.swap.request.fromType), day(event.swap.swap.fromDate))
                is TradeEvent.SwapAnswered -> context.getString(
                    when (event.swap.request.status) {
                        SwapStatus.DONE -> R.string.alert_swap_done
                        SwapStatus.DECLINED -> R.string.alert_swap_declined
                        else -> R.string.alert_swap_accepted
                    },
                    name(event.swap.swap.toMember),
                    day(event.swap.swap.fromDate),
                )
                is TradeEvent.SwapNeedsAdmin -> context.getString(R.string.alert_swap_admin, name(event.swap.swap.fromMember), name(event.swap.swap.toMember))
                is TradeEvent.OfferOpen -> context.getString(R.string.alert_offer_open, name(event.offer.memberId), type(event.offer.offer.typeId), day(event.offer.date))
                is TradeEvent.OfferNeedsAdmin -> context.getString(R.string.alert_offer_admin, name(event.offer.memberId), day(event.offer.date))
            }
        }
        val title = if (lines.size == 1) lines.first() else context.getString(R.string.alert_trades_title, lines.size)
        val style = NotificationCompat.InboxStyle()
        lines.take(MAX_LINES).forEach { style.addLine(it) }
        notify(TRADE_NOTIFICATION, title, lines.first(), style)
    }

    private fun showPublished(lock: PlanLock) {
        val until = lock.until
        val text = if (until == null) {
            context.getString(R.string.alert_published_whole)
        } else {
            context.getString(R.string.alert_published_until, WeekFormat.longDate(until))
        }
        notify(PUBLISH_NOTIFICATION, context.getString(R.string.alert_published_title), text, null)
    }

    private fun notify(id: Int, title: String, text: String, style: NotificationCompat.Style?) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_public_text))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        if (style != null) builder.setStyle(style)
        manager.notify(id, builder.build())
    }

    private companion object {
        const val CHANNEL_ID = "team_ereignisse"
        const val TRADE_NOTIFICATION = 2
        const val PUBLISH_NOTIFICATION = 3
        const val MAX_LINES = 5
    }
}
