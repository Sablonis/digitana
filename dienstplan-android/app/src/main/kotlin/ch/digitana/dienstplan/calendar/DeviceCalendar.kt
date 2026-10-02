package ch.digitana.dienstplan.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.util.Logger
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Eigene Dienste in einem lokalen Kalender des Geräts („Dienstplan“), nur auf Wunsch. Der
 * Kalender gehört zu keinem Konto (ACCOUNT_TYPE_LOCAL) und wird deshalb nicht in eine Cloud
 * abgeglichen; andere Apps mit Kalenderberechtigung können ihn aber lesen. Abgeglichen wird
 * ein Fenster von 30 Tagen zurück bis 120 Tage voraus; jedes Ereignis trägt sein Datum in
 * SYNC_DATA1 und wird bei jeder Änderung nachgeführt.
 */
class DeviceCalendar(private val context: Context, private val logger: Logger) {

    private data class Wanted(val title: String, val start: Long, val end: Long, val allDay: Boolean, val timeZone: String)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Kalender auf den Stand von [plan] bringen; ohne Person nichts tun. Blockiert (im Hintergrund aufrufen). */
    fun sync(plan: PlanState, memberId: String?, today: LocalDate = LocalDate.now()) {
        if (memberId == null || !hasPermission()) return
        try {
            val calendarId = findCalendar() ?: createCalendar() ?: return
            val wanted = wanted(plan, memberId, today)
            val existing = existing(calendarId)
            val resolver = context.contentResolver
            for ((date, event) in existing) {
                val target = wanted[date]
                if (target == null) {
                    resolver.delete(asSyncAdapter(ContentUris.withAppendedId(Events.CONTENT_URI, event.first)), null, null)
                } else if (target != event.second) {
                    resolver.update(asSyncAdapter(ContentUris.withAppendedId(Events.CONTENT_URI, event.first)), values(calendarId, date, target), null, null)
                }
            }
            for ((date, target) in wanted) {
                if (date !in existing) resolver.insert(asSyncAdapter(Events.CONTENT_URI), values(calendarId, date, target))
            }
        } catch (e: SecurityException) {
            logger.warn(TAG, "Kalender nicht erlaubt", e)
        } catch (e: IllegalArgumentException) {
            logger.warn(TAG, "Kalender nicht abgeglichen", e)
        }
    }

    /** Kalender samt Ereignissen entfernen (Schalter aus, Team verlassen). */
    fun remove() {
        if (!hasPermission()) return
        try {
            val id = findCalendar() ?: return
            context.contentResolver.delete(asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
        } catch (e: SecurityException) {
            logger.warn(TAG, "Kalender nicht entfernt", e)
        }
    }

    private fun wanted(plan: PlanState, memberId: String, today: LocalDate): Map<String, Wanted> {
        val types = plan.shiftTypes
        val zone = ZoneId.systemDefault()
        val result = HashMap<String, Wanted>()
        var date = today.minusDays(PAST_DAYS)
        val last = today.plusDays(FUTURE_DAYS)
        while (!date.isAfter(last)) {
            if (PlanKeys.isValidDate(date)) {
                val type = types[plan.shift(memberId, date)]
                if (type != null && type.kind != ShiftKind.OFF) {
                    val title = "${type.name} (${type.code})"
                    val start = type.start
                    result[date.toString()] = if (start != null && type.kind == ShiftKind.WORK) {
                        val begin = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
                        Wanted(title, begin, begin + type.durationMinutes * 60_000L, allDay = false, timeZone = zone.id)
                    } else {
                        // Ganztägig: Mitternacht in UTC, wie es CalendarContract verlangt.
                        val begin = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        Wanted(title, begin, begin + DAY_MILLIS, allDay = true, timeZone = "UTC")
                    }
                }
            }
            date = date.plusDays(1)
        }
        return result
    }

    /** Datum → (Ereignis-ID, Stand). */
    private fun existing(calendarId: Long): Map<String, Pair<Long, Wanted>> {
        val projection = arrayOf(Events._ID, Events.SYNC_DATA1, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE)
        val result = HashMap<String, Pair<Long, Wanted>>()
        context.contentResolver.query(
            asSyncAdapter(Events.CONTENT_URI),
            projection,
            "${Events.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val date = cursor.getString(1)
                if (date == null || date in result) {
                    // Fremdes oder doppeltes Ereignis in unserem Kalender: entfernen.
                    context.contentResolver.delete(asSyncAdapter(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
                    continue
                }
                result[date] = id to Wanted(
                    title = cursor.getString(2).orEmpty(),
                    start = cursor.getLong(3),
                    end = cursor.getLong(4),
                    allDay = cursor.getInt(5) == 1,
                    timeZone = cursor.getString(6).orEmpty(),
                )
            }
        }
        return result
    }

    private fun values(calendarId: Long, date: String, event: Wanted) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, event.title)
        put(Events.DTSTART, event.start)
        put(Events.DTEND, event.end)
        put(Events.ALL_DAY, if (event.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, event.timeZone)
        put(Events.SYNC_DATA1, date)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
    }

    private fun findCalendar(): Long? {
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return null
    }

    private fun createCalendar(): Long? {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, ACCOUNT_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, context.getString(R.string.calendar_name))
            put(Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        val uri = context.contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values) ?: return null
        return ContentUris.parseId(uri)
    }

    /** Als „Sync-Adapter“ des eigenen lokalen Kontos: nötig für SYNC_DATA1 und echtes Löschen. */
    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private companion object {
        const val TAG = "DeviceCalendar"
        const val ACCOUNT_NAME = "Dienstplan"
        const val PAST_DAYS = 30L
        const val FUTURE_DAYS = 120L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** Indigo der App. */
        const val CALENDAR_COLOR = 0xFF3648E7.toInt()
    }
}
