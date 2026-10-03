package ch.digitana.dienstplan.core.plan

import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.PlanState
import ch.digitana.dienstplan.core.crdt.ShiftKind
import ch.digitana.dienstplan.core.util.Hex
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Eigene Dienste als iCalendar-Datei (RFC 5545) zum Import in eine Kalender-App.
 * Dienste mit Zeiten werden in UTC umgerechnet, solche ohne Zeiten sind ganztägig.
 * Freie Tage fehlen. Jeder Tag hat eine feste UID, damit ein erneuter Import die
 * Termine ersetzt statt verdoppelt (sofern die Kalender-App das unterstützt).
 */
object CalendarExport {
    const val MAX_DAYS = 400

    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun ics(
        state: PlanState,
        memberId: String,
        from: LocalDate,
        days: Int,
        zone: ZoneId,
        now: Instant,
    ): String {
        require(days in 1..MAX_DAYS) { "1 bis $MAX_DAYS Tage" }
        val member = state.members().firstOrNull { it.id == memberId }
        val types = state.shiftTypes
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//digitana//Dienstplan//DE"
        lines += "CALSCALE:GREGORIAN"
        lines += "METHOD:PUBLISH"
        lines += "X-WR-CALNAME:" + escape(if (member != null) "Dienstplan – ${member.name}" else "Dienstplan")
        for (offset in 0 until days) {
            val date = from.plusDays(offset.toLong())
            if (!PlanKeys.isValidDate(date)) continue
            val type = types[state.shift(memberId, date)] ?: continue
            if (type.kind == ShiftKind.OFF) continue
            lines += "BEGIN:VEVENT"
            lines += "UID:${uid(memberId, date)}"
            lines += "DTSTAMP:${UTC.format(now)}"
            val start = type.start
            if (start != null) {
                val begin = date.atTime(start).atZone(zone)
                lines += "DTSTART:${UTC.format(begin.toInstant())}"
                lines += "DTEND:${UTC.format(begin.plusMinutes(type.durationMinutes.toLong()).toInstant())}"
            } else {
                lines += "DTSTART;VALUE=DATE:${DATE.format(date)}"
                lines += "DTEND;VALUE=DATE:${DATE.format(date.plusDays(1))}"
            }
            lines += "SUMMARY:" + escape("${type.name} (${type.code})")
            state.memberNote(memberId, date)?.let { lines += "DESCRIPTION:" + escape(it) }
            lines += "TRANSP:" + if (type.kind == ShiftKind.WORK) "OPAQUE" else "TRANSPARENT"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString(separator = "\r\n", postfix = "\r\n") { fold(it) }
    }

    /** Stabil pro Person und Tag, ohne die Mitglieds-ID preiszugeben. */
    private fun uid(memberId: String, date: LocalDate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("dienstplan|$memberId|$date".toByteArray(Charsets.UTF_8))
        return Hex.encode(digest).take(32) + "@dienstplan.digitana.ch"
    }

    /** TEXT-Werte nach RFC 5545: Backslash, Strichpunkt, Komma und Zeilenumbruch maskieren. */
    internal fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    /** Zeilen über 75 Byte (UTF-8) umbrechen, ohne ein Zeichen zu teilen. */
    internal fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var bytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) {
                out.append("\r\n ")
                bytes = 0
                limit = 74 // Fortsetzungszeilen beginnen mit einem Leerzeichen.
            }
            out.appendCodePoint(cp)
            bytes += size
            i += chars
        }
        return out.toString()
    }
}
