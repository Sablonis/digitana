package ch.digitana.dienstplan.core.util

/** Billige Vorprüfungen, bevor fremdes JSON überhaupt geparst wird. */
object JsonGuards {

    /**
     * true, wenn Arrays/Objekte tiefer als [maxDepth] verschachtelt sind.
     * Zeichenketten (inklusive maskierter Anführungszeichen) werden übersprungen.
     */
    fun exceedsDepth(text: String, maxDepth: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> {
                    depth++
                    if (depth > maxDepth) return true
                }
                ']', '}' -> depth--
            }
        }
        return false
    }

    /** UTF-8-Länge ohne Kopie; bricht ab, sobald [limit] überschritten ist. */
    fun utf8LengthExceeds(text: String, limit: Int): Boolean {
        if (text.length > limit) return true
        if (text.length.toLong() * 3 <= limit) return false
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            if (bytes > limit) return true
            i++
        }
        return false
    }
}
