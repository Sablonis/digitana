package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.util.JsonGuards
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Sperre des Plans, gesetzt von einem Admin. Sie liegt als JSON in der Beschreibung der
 * MLS-Gruppe; Änderungen daran nimmt die MLS-Schicht nur von Admin-Geräten an.
 *
 * Gesperrt sind alle Tage bis [until] (null = der ganze Plan). Die Sperre besteht aus
 * Abschnitten, die nacheinander dazugekommen sind. Jeder gilt ab seinem Zeitpunkt
 * [Segment.since] (hybride Uhr); was vorher eingetragen wurde, bleibt gültig.
 */
data class PlanLock(
    /** Aufsteigend nach [Segment.until]; nur der letzte darf ohne Ende sein. */
    val segments: List<Segment>,
    /** Geräte, deren Einträge trotz Sperre gelten: die Admins seit Beginn der Sperre. */
    val admins: Set<String>,
    /** Gerät, das die Sperre zuletzt gesetzt hat. */
    val by: String? = null,
) {
    /** Abschnitt bis [until] (einschliesslich, null = ohne Ende), gültig ab [since]. */
    data class Segment(val until: LocalDate?, val since: Long)

    init {
        require(segments.isNotEmpty() && segments.size <= PlanLocks.MAX_SEGMENTS) { "Abschnitte ungültig" }
        require(segments.all { it.since > 0 && it.until.let { date -> date == null || PlanKeys.isValidDate(date) } }) { "Abschnitt ungültig" }
        for (i in 1 until segments.size) {
            val before = requireNotNull(segments[i - 1].until) { "Nur der letzte Abschnitt darf offen sein" }
            val end = segments[i].until
            require(end == null || end.isAfter(before)) { "Abschnitte nicht aufsteigend" }
        }
        require(admins.size <= PlanLocks.MAX_ADMINS && admins.all(EntryValidator::isValidDeviceId)) { "Admins ungültig" }
        require(by == null || EntryValidator.isValidDeviceId(by)) { "Gerät ungültig" }
    }

    /** Letzter gesperrter Tag; null = der ganze Plan. */
    val until: LocalDate? get() = segments.last().until

    /** Beginn der Sperre (ältester Abschnitt). */
    val since: Long get() = segments.minOf { it.since }

    /** Zeitpunkt der letzten Erweiterung. */
    val changedAt: Long get() = segments.maxOf { it.since }

    fun isLocked(date: LocalDate): Boolean = until.let { it == null || !date.isAfter(it) }

    /** Abschnitt, der [date] sperrt; null = der Tag ist offen. */
    fun segmentFor(date: LocalDate): Segment? =
        segments.firstOrNull { segment -> segment.until.let { it == null || !date.isAfter(it) } }
}

object PlanLocks {
    const val MAX_SEGMENTS = 12
    const val MAX_ADMINS = 64

    /**
     * Sperrt bis [until] (null = der ganze Plan). Schon gesperrte Tage behalten den Zeitpunkt
     * ihres Abschnitts, neu gesperrte gelten ab [now], Tage nach [until] sind wieder offen.
     * [admins]: aktuelle Admin-Geräte; frühere aus der laufenden Sperre bleiben eingetragen,
     * damit ihre Einträge gültig bleiben.
     */
    fun lock(current: PlanLock?, until: LocalDate?, now: Long, admins: Set<String>, by: String): PlanLock {
        require(until == null || PlanKeys.isValidDate(until)) { "Datum ausserhalb 2000–2100" }
        require(now > 0) { "Zeitpunkt ungültig" }
        if (current == null) return PlanLock(listOf(PlanLock.Segment(until, now)), cap(admins, admins), by)
        val kept = ArrayList<PlanLock.Segment>()
        for (segment in current.segments) {
            val previous = kept.lastOrNull()?.until
            if (until != null && previous != null && !previous.isBefore(until)) break
            val end = segment.until
            kept += if (until != null && (end == null || end.isAfter(until))) PlanLock.Segment(until, segment.since) else segment
        }
        val coveredUntil = kept.last().until
        if (coveredUntil != null && (until == null || until.isAfter(coveredUntil))) kept += PlanLock.Segment(until, now)
        // Sehr alte Abschnitte zusammenlegen; der jüngere Zeitpunkt gilt (nichts Gültiges geht verloren).
        while (kept.size > MAX_SEGMENTS) {
            val first = kept.removeAt(0)
            kept[0] = PlanLock.Segment(kept[0].until, maxOf(first.since, kept[0].since))
        }
        val allAdmins = cap(current.admins + admins, admins)
        if (kept == current.segments && allAdmins == current.admins) return current
        return PlanLock(kept, allAdmins, by)
    }

    /** Neue Admins während der Sperre: Sie dürfen ab sofort ändern. */
    fun withAdmins(lock: PlanLock, admins: Set<String>): PlanLock {
        val allAdmins = cap(lock.admins + admins, admins)
        return if (allAdmins == lock.admins) lock else lock.copy(admins = allAdmins)
    }

    /** Höchstens [MAX_ADMINS]; die aktuellen haben Vorrang. */
    private fun cap(all: Set<String>, preferred: Set<String>): Set<String> {
        if (all.size <= MAX_ADMINS) return all
        val first = preferred.sorted().take(MAX_ADMINS)
        return (first + (all - preferred).sorted().take(MAX_ADMINS - first.size)).toSet()
    }
}

/**
 * Format in der Teambeschreibung:
 * `{"v":1,"lock":{"seg":[{"until":"2026-10-31","since":…},{"since":…}],"admins":[…],"by":"…"}}`.
 * Leere Beschreibung oder fehlendes `lock` = offen. Unlesbares gilt ebenfalls als offen:
 * Eine Sperre, die ein Gerät nicht versteht, darf nicht dazu führen, dass es Einträge löscht.
 */
object PlanLockCodec {
    private const val VERSION = 1
    /** Gleiche Grenze wie in der MLS-Schicht. */
    const val MAX_BYTES = 4096
    private const val MAX_DEPTH = 4
    private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val json = Json { isLenient = false }

    fun encode(lock: PlanLock?): String {
        if (lock == null) return ""
        return buildJsonObject {
            put("v", VERSION)
            putJsonObject("lock") {
                putJsonArray("seg") {
                    for (segment in lock.segments) {
                        addJsonObject {
                            segment.until?.let { put("until", it.toString()) }
                            put("since", segment.since)
                        }
                    }
                }
                putJsonArray("admins") { lock.admins.sorted().forEach { add(it) } }
                lock.by?.let { put("by", it) }
            }
        }.toString()
    }

    fun decode(description: String): PlanLock? {
        if (description.isEmpty() || JsonGuards.utf8LengthExceeds(description, MAX_BYTES)) return null
        if (JsonGuards.exceedsDepth(description, MAX_DEPTH)) return null
        return try {
            val root = json.parseToJsonElement(description) as? JsonObject ?: return null
            if (number(root["v"]) != VERSION.toLong()) return null
            val lock = root["lock"] as? JsonObject ?: return null
            val segments = (lock["seg"] as? JsonArray ?: return null).map { element ->
                val segment = element as? JsonObject ?: return null
                val until = segment["until"]?.let { date(text(it) ?: return null) ?: return null }
                PlanLock.Segment(until, number(segment["since"]) ?: return null)
            }
            val admins = (lock["admins"] as? JsonArray ?: return null).map { text(it) ?: return null }
            val by = lock["by"]?.let { text(it) ?: return null }
            PlanLock(segments, admins.toSet(), by)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun text(element: Any?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(element: Any?): Long? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun date(text: String): LocalDate? {
        if (!DATE.matches(text)) return null
        val date = try {
            LocalDate.parse(text)
        } catch (e: DateTimeException) {
            return null
        }
        return date.takeIf(PlanKeys::isValidDate)
    }
}

/**
 * Wer welche Planeinträge ändern darf.
 *
 * Ohne Sperre dürfen alle alles. Mit Sperre sind geschützt: Schichten an gesperrten Tagen,
 * Schichtarten und das Löschen von Personen – das ändern nur Admins. Wünsche, Notizen,
 * Rhythmen, Zuordnungen und neue Personen bleiben für alle offen.
 *
 * Empfangene Einträge prüft jedes Gerät nach derselben Regel ([allows]); so kommen alle zum
 * selben Stand. Die Regel stützt sich auf Geräte-ID und Zeitstempel im Eintrag. Beides ist
 * nicht einzeln signiert: Eine veränderte App eines Mitglieds könnte es fälschen (siehe
 * SICHERHEIT.md). Die Sperre schützt vor versehentlichen Änderungen, nicht vor Mitgliedern
 * mit böser Absicht.
 */
data class PlanAccess(val lock: PlanLock?, val adminDevices: Set<String>) {

    private val trusted: Set<String> = if (lock == null) adminDevices else adminDevices + lock.admins

    val isLocked: Boolean get() = lock != null

    fun isAdmin(device: String?): Boolean = device != null && device in adminDevices

    /** Gilt ein empfangener oder gespeicherter Eintrag unter dieser Sperre? */
    fun allows(key: String, entry: Entry): Boolean {
        if (lock == null || !mayBeProtected(key) || entry.device in trusted) return true
        val parsed = PlanKeys.parse(key) ?: return true
        val since = protectedSince(lock, parsed, entry.value) ?: return true
        return entry.timestamp <= since
    }

    /** Darf [device] jetzt [key] auf [value] setzen? */
    fun mayWrite(device: String?, key: String, value: String): Boolean {
        if (lock == null || isAdmin(device) || !mayBeProtected(key)) return true
        val parsed = PlanKeys.parse(key) ?: return true
        return protectedSince(lock, parsed, value) == null
    }

    fun mayEditShift(device: String?, date: LocalDate): Boolean = lock == null || isAdmin(device) || !lock.isLocked(date)

    fun mayEditShiftTypes(device: String?): Boolean = lock == null || isAdmin(device)

    fun mayDeleteMembers(device: String?): Boolean = lock == null || isAdmin(device)

    companion object {
        val OPEN = PlanAccess(null, emptySet())

        /** Schnelle Vorprüfung ohne Regex: Nur Schichten, Schichtarten und Personen können geschützt sein. */
        private fun mayBeProtected(key: String): Boolean =
            key.startsWith("z|") || key.startsWith("s|") || key.startsWith("m|")

        private fun protectedSince(lock: PlanLock, key: PlanKey, value: String): Long? = when (key) {
            is PlanKey.Shift -> lock.segmentFor(key.date)?.since
            is PlanKey.ShiftType -> lock.since
            is PlanKey.Member -> if (value.isEmpty()) lock.since else null
            else -> null
        }
    }
}
