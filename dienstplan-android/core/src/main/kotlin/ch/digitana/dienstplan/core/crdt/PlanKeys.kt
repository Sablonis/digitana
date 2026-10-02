package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.util.Hex
import java.time.DateTimeException
import java.time.LocalDate

/** Ein syntaktisch und inhaltlich gültiger Schlüssel der LWW-Map. */
sealed interface PlanKey {
    /** `m|<id>` → Name (leer = gelöscht). */
    data class Member(val memberId: String) : PlanKey

    /** `z|<id>|<JJJJ-MM-TT>` → ID einer Schichtart (leer = kein Eintrag). */
    data class Shift(val memberId: String, val date: LocalDate) : PlanKey

    /** `d|<geräte-id>` → Name des Geräts in der Geräteliste (leer = ohne Namen). */
    data class Device(val deviceId: String) : PlanKey

    /** `s|<schichtart-id>` → Definition einer Schichtart (leer = Standard bzw. unbekannt). */
    data class ShiftType(val typeId: String) : PlanKey

    /** `r|<rhythmus-id>` → Schichtrhythmus (leer = gelöscht). */
    data class Pattern(val patternId: String) : PlanKey

    /** `n|<JJJJ-MM-TT>` → Notiz zum Tag (leer = keine). */
    data class DayNote(val date: LocalDate) : PlanKey

    /** `n|<id>|<JJJJ-MM-TT>` → Notiz zum Dienst einer Person (leer = keine). */
    data class MemberNote(val memberId: String, val date: LocalDate) : PlanKey

    /** `w|<id>|<JJJJ-MM-TT>` → Wunsch einer Person (leer = keiner). */
    data class Wish(val memberId: String, val date: LocalDate) : PlanKey

    /** `u|<geräte-id>` → ID der Person, der das Gerät gehört (leer = keine). */
    data class DeviceOwner(val deviceId: String) : PlanKey

    /** `c|<name>` → Planungsregel des Teams, z. B. `rest`: Mindestruhezeit in Minuten (leer = Standard). */
    data class Setting(val name: String) : PlanKey

    /** `b|<schichtart-id>` → Soll-Besetzung pro Wochentag, `3,3,3,3,3,2,2` (leer = keins). */
    data class Target(val typeId: String) : PlanKey
}

object PlanKeys {
    val MIN_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(2100, 12, 31)

    private const val DATE = "([0-9]{4})-([0-9]{2})-([0-9]{2})"
    private val ID = Regex("[0-9a-f]{16}")
    private val MEMBER = Regex("m\\|([0-9a-f]{16})")
    private val DEVICE = Regex("d\\|([0-9a-f]{16})")
    private val SHIFT = Regex("z\\|([0-9a-f]{16})\\|$DATE")
    private val SHIFT_TYPE = Regex("s\\|([FSNXU]|[0-9a-f]{8})")
    private val PATTERN = Regex("r\\|([0-9a-f]{8})")
    private val DAY_NOTE = Regex("n\\|$DATE")
    private val MEMBER_NOTE = Regex("n\\|([0-9a-f]{16})\\|$DATE")
    private val WISH = Regex("w\\|([0-9a-f]{16})\\|$DATE")
    private val DEVICE_OWNER = Regex("u\\|([0-9a-f]{16})")
    private val SETTING = Regex("c\\|(rest)")
    private val TARGET = Regex("b\\|([FSNXU]|[0-9a-f]{8})")

    /** Neue zufällige ID (64 Bit, 16 Hex-Zeichen). */
    fun newId(): String = Hex.encode(SecureRandomBytes.next(8))

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun isValidDate(date: LocalDate): Boolean = !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE)

    fun member(id: String): String {
        require(isValidId(id)) { "Ungültige ID" }
        return "m|$id"
    }

    fun device(deviceId: String): String {
        require(isValidId(deviceId)) { "Ungültige Geräte-ID" }
        return "d|$deviceId"
    }

    fun shift(id: String, date: LocalDate): String {
        require(isValidId(id)) { "Ungültige ID" }
        require(isValidDate(date)) { "Datum ausserhalb 2000–2100" }
        return "z|$id|$date"
    }

    fun shiftType(typeId: String): String {
        require(ShiftTypes.isValidId(typeId)) { "Ungültige Schichtart-ID" }
        return "s|$typeId"
    }

    fun pattern(patternId: String): String {
        require(ShiftPatterns.isValidId(patternId)) { "Ungültige Rhythmus-ID" }
        return "r|$patternId"
    }

    fun dayNote(date: LocalDate): String {
        require(isValidDate(date)) { "Datum ausserhalb 2000–2100" }
        return "n|$date"
    }

    fun memberNote(id: String, date: LocalDate): String {
        require(isValidId(id)) { "Ungültige ID" }
        require(isValidDate(date)) { "Datum ausserhalb 2000–2100" }
        return "n|$id|$date"
    }

    fun wish(id: String, date: LocalDate): String {
        require(isValidId(id)) { "Ungültige ID" }
        require(isValidDate(date)) { "Datum ausserhalb 2000–2100" }
        return "w|$id|$date"
    }

    fun deviceOwner(deviceId: String): String {
        require(isValidId(deviceId)) { "Ungültige Geräte-ID" }
        return "u|$deviceId"
    }

    fun setting(name: String): String {
        require(SETTING.matches("c|$name")) { "Unbekannte Einstellung" }
        return "c|$name"
    }

    fun target(typeId: String): String {
        require(ShiftTypes.isValidId(typeId)) { "Ungültige Schichtart-ID" }
        return "b|$typeId"
    }

    /**
     * Prüft Format (Regex, ganze Zeichenkette) und Datum (existiert, 2000–2100).
     * Gibt `null` für alles andere zurück.
     */
    fun parse(key: String): PlanKey? {
        if (key.length > MAX_KEY_LENGTH) return null
        MEMBER.matchEntire(key)?.let { return PlanKey.Member(it.groupValues[1]) }
        DEVICE.matchEntire(key)?.let { return PlanKey.Device(it.groupValues[1]) }
        DEVICE_OWNER.matchEntire(key)?.let { return PlanKey.DeviceOwner(it.groupValues[1]) }
        SETTING.matchEntire(key)?.let { return PlanKey.Setting(it.groupValues[1]) }
        TARGET.matchEntire(key)?.let { return PlanKey.Target(it.groupValues[1]) }
        SHIFT_TYPE.matchEntire(key)?.let { return PlanKey.ShiftType(it.groupValues[1]) }
        PATTERN.matchEntire(key)?.let { return PlanKey.Pattern(it.groupValues[1]) }
        SHIFT.matchEntire(key)?.let { match ->
            val date = date(match, 2) ?: return null
            return PlanKey.Shift(match.groupValues[1], date)
        }
        MEMBER_NOTE.matchEntire(key)?.let { match ->
            val date = date(match, 2) ?: return null
            return PlanKey.MemberNote(match.groupValues[1], date)
        }
        WISH.matchEntire(key)?.let { match ->
            val date = date(match, 2) ?: return null
            return PlanKey.Wish(match.groupValues[1], date)
        }
        DAY_NOTE.matchEntire(key)?.let { match ->
            val date = date(match, 1) ?: return null
            return PlanKey.DayNote(date)
        }
        return null
    }

    /** Datum aus den Gruppen ab [first]; null, wenn es nicht existiert oder ausserhalb liegt. */
    private fun date(match: MatchResult, first: Int): LocalDate? {
        val date = try {
            LocalDate.of(
                match.groupValues[first].toInt(),
                match.groupValues[first + 1].toInt(),
                match.groupValues[first + 2].toInt(),
            )
        } catch (e: DateTimeException) {
            return null
        }
        return if (isValidDate(date)) date else null
    }

    private const val MAX_KEY_LENGTH = 64
}
