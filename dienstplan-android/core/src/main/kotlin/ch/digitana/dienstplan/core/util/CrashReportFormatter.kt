package ch.digitana.dienstplan.core.util

import java.time.Instant

/**
 * Erstellt einen lokalen Absturzbericht ohne sensible Daten: Klassen, Methoden und
 * Zeilennummern bleiben erhalten; Meldungstexte werden bereinigt (Codes,
 * Hex- und Base64-Folgen entfernt) und gekürzt. Meldungen von JSON-Parsern entfallen
 * ganz, weil sie Ausschnitte der Eingabe enthalten können.
 */
object CrashReportFormatter {
    private const val MAX_FRAMES = 40
    private const val MAX_CAUSES = 5
    private const val MAX_MESSAGE = 160
    private const val MAX_REPORT = 16_000

    /** Einladungs- (DP2) und Beitrittscodes (DP3). */
    private val CODE = Regex("DP([23])-[A-Za-z0-9_-]*", RegexOption.IGNORE_CASE)
    private val HEX_RUN = Regex("[0-9a-fA-F]{16,}")
    private val TOKEN_RUN = Regex("[A-Za-z0-9+/_-]{24,}={0,2}")
    private val SUPPRESSED_MESSAGE_PACKAGES = listOf("kotlinx.serialization.", "org.json.")

    fun format(
        throwable: Throwable,
        threadName: String,
        appVersion: String,
        androidVersion: String,
        device: String,
        timeMillis: Long,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Dienstplan – Absturzbericht (ohne persönliche Daten)")
        sb.appendLine("Zeit: ${Instant.ofEpochMilli(timeMillis)}")
        sb.appendLine("App: $appVersion")
        sb.appendLine("Android: $androidVersion")
        sb.appendLine("Gerät: ${sanitize(device)}")
        sb.appendLine("Thread: ${sanitize(threadName)}")
        sb.appendLine()
        var current: Throwable? = throwable
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth <= MAX_CAUSES && seen.add(current)) {
            if (depth > 0) sb.append("Caused by: ")
            sb.appendLine(describe(current))
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { sb.append("    at ").appendLine(it.toString()) }
            if (frames.size > MAX_FRAMES) sb.appendLine("    … ${frames.size - MAX_FRAMES} weitere")
            current = current.cause
            depth++
        }
        return if (sb.length > MAX_REPORT) sb.substring(0, MAX_REPORT) + "\n[gekürzt]" else sb.toString()
    }

    fun sanitize(text: String): String {
        var result = text.filter { !it.isISOControl() || it == ' ' }
        result = CODE.replace(result) { "DP${it.groupValues[1]}-[entfernt]" }
        result = HEX_RUN.replace(result, "[hex]")
        result = TOKEN_RUN.replace(result) { match ->
            val value = match.value
            if (value.any { it.isDigit() } && value.any { it.isLetter() }) "[daten]" else value
        }
        return result
    }

    private fun describe(t: Throwable): String {
        val name = t.javaClass.name
        val message = t.message
        if (message.isNullOrBlank() || SUPPRESSED_MESSAGE_PACKAGES.any { name.startsWith(it) }) return name
        return "$name: ${sanitize(message).take(MAX_MESSAGE)}"
    }
}
