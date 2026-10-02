package ch.digitana.dienstplan.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import ch.digitana.dienstplan.DienstplanApp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.plan.MyShiftsModel
import ch.digitana.dienstplan.core.plan.WeekFormat
import ch.digitana.dienstplan.ui.MainActivity
import ch.digitana.dienstplan.ui.components.Format
import ch.digitana.dienstplan.ui.theme.DarkShiftPalette
import ch.digitana.dienstplan.ui.theme.LightShiftPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Widget „Meine Dienste“ für den Startbildschirm: die nächsten fünf eigenen Dienste und die
 * Stunden der Woche. Es zeigt nur Daten der Person, die unter „Ich“ gewählt ist.
 */
class MyShiftsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(UPDATE_TIMEOUT_MILLIS) {
                    val container = (context.applicationContext as DienstplanApp).container
                    container.awaitReady(READY_TIMEOUT_MILLIS)
                    val views = WidgetUpdater.build(
                        context,
                        container.planRepository.state.value,
                        container.settingsRepository.settings.value.myMemberId,
                        LocalDate.now(),
                    )
                    appWidgetManager.updateAppWidget(appWidgetIds, views)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val UPDATE_TIMEOUT_MILLIS = 8_000L
        const val READY_TIMEOUT_MILLIS = 6_000L
    }
}

object WidgetUpdater {
    private const val ROWS = 5
    private val rowIds = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4, R.id.widget_row_5)
    private val badgeIds = intArrayOf(R.id.widget_badge_1, R.id.widget_badge_2, R.id.widget_badge_3, R.id.widget_badge_4, R.id.widget_badge_5)
    private val codeIds = intArrayOf(R.id.widget_code_1, R.id.widget_code_2, R.id.widget_code_3, R.id.widget_code_4, R.id.widget_code_5)
    private val dateIds = intArrayOf(R.id.widget_date_1, R.id.widget_date_2, R.id.widget_date_3, R.id.widget_date_4, R.id.widget_date_5)
    private val labelIds = intArrayOf(R.id.widget_label_1, R.id.widget_label_2, R.id.widget_label_3, R.id.widget_label_4, R.id.widget_label_5)

    /** Alle Widgets mit dem aktuellen Stand neu zeichnen (nach Änderungen am Plan oder an „Ich“). */
    fun updateAll(context: Context, plan: PlanState, myMemberId: String?) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, MyShiftsWidget::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, build(context, plan, myMemberId, LocalDate.now()))
    }

    fun build(context: Context, plan: PlanState, myMemberId: String?, today: LocalDate): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_my_shifts)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)

        val model = myMemberId?.let { MyShiftsModel.build(plan, it, today, weeks = 1) }
        val upcoming = if (model != null) MyShiftsModel.upcoming(plan, model.member.id, today, ROWS) else emptyList()
        views.setTextViewText(R.id.widget_hours, model?.let { context.getString(R.string.widget_week_hours, Format.hours(it.weekMinutes)) } ?: "")

        val empty = when {
            plan.members().isEmpty() -> context.getString(R.string.widget_no_plan)
            model == null -> context.getString(R.string.widget_choose_me)
            upcoming.isEmpty() -> context.getString(R.string.widget_no_shifts)
            else -> null
        }
        views.setViewVisibility(R.id.widget_empty, if (empty != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_empty, empty ?: "")

        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val palette = if (night) DarkShiftPalette else LightShiftPalette
        for (i in 0 until ROWS) {
            val day = upcoming.getOrNull(i)
            if (day == null) {
                views.setViewVisibility(rowIds[i], View.GONE)
                continue
            }
            val type = day.cell.type
            val color = palette.of(type)
            views.setViewVisibility(rowIds[i], View.VISIBLE)
            views.setInt(badgeIds[i], "setColorFilter", color.container.toArgb())
            views.setTextViewText(codeIds[i], type?.code ?: "?")
            views.setTextColor(codeIds[i], color.content.toArgb())
            views.setTextViewText(
                dateIds[i],
                when (day.date) {
                    today -> context.getString(R.string.widget_today)
                    today.plusDays(1) -> context.getString(R.string.widget_tomorrow)
                    else -> "${WeekFormat.weekday(day.date)} ${WeekFormat.shortDate(day.date)}"
                },
            )
            val label = type?.let { listOfNotNull(it.name, Format.timeRange(it)).joinToString(" · ") }
                ?: context.getString(R.string.shift_unknown)
            views.setTextViewText(labelIds[i], label)
        }
        return views
    }
}
