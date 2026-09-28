package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.util.Hex
import java.time.DateTimeException
import java.time.LocalDate

/** Ein syntaktisch und inhaltlich gültiger Schlüssel der LWW-Map. */
sealed interface PlanKey {
    val memberId: String

    /** `m|<id>` → Name (leer = gelöscht). */
    data class Member(override val memberId: String) : PlanKey

    /** `z|<id>|<JJJJ-MM-TT>` → Schichtkürzel (leer = kein Eintrag). */
    data class Shift(override val memberId: String, val date: LocalDate) : PlanKey
}

object PlanKeys {
    val MIN_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(2100, 12, 31)

    private val ID = Regex("[0-9a-f]{16}")
    private val MEMBER = Regex("m\\|([0-9a-f]{16})")
    private val SHIFT = Regex("z\\|([0-9a-f]{16})\\|([0-9]{4})-([0-9]{2})-([0-9]{2})")

    /** Neue zufällige ID (64 Bit, 16 Hex-Zeichen). */
    fun newId(): String = Hex.encode(SecureRandomBytes.next(8))

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun isValidDate(date: LocalDate): Boolean = !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE)

    fun member(id: String): String {
        require(isValidId(id)) { "Ungültige ID" }
        return "m|$id"
    }

    fun shift(id: String, date: LocalDate): String {
        require(isValidId(id)) { "Ungültige ID" }
        require(isValidDate(date)) { "Datum ausserhalb 2000–2100" }
        return "z|$id|$date"
    }

    /**
     * Prüft Format (Regex, ganze Zeichenkette) und Datum (existiert, 2000–2100).
     * Gibt `null` für alles andere zurück.
     */
    fun parse(key: String): PlanKey? {
        if (key.length > MAX_KEY_LENGTH) return null
        MEMBER.matchEntire(key)?.let { return PlanKey.Member(it.groupValues[1]) }
        val match = SHIFT.matchEntire(key) ?: return null
        val (id, year, month, day) = match.destructured
        val date = try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (e: DateTimeException) {
            return null
        }
        if (!isValidDate(date)) return null
        return PlanKey.Shift(id, date)
    }

    private const val MAX_KEY_LENGTH = 64
}
