package ch.digitana.dienstplan.core.crdt

/**
 * Ein Registerwert der Last-Writer-Wins-Map.
 *
 * Reihenfolge: zuerst [timestamp], dann [device], zuletzt [value]. Der letzte
 * Vergleich kommt nur bei fehlerhaften oder böswilligen Daten zum Tragen
 * (gleiche Uhrzeit und Geräte-ID, aber anderer Wert) und sorgt dafür, dass
 * das Zusammenführen auch dann in jeder Reihenfolge dasselbe Ergebnis liefert.
 */
data class Entry(
    val value: String,
    val timestamp: Long,
    val device: String,
) : Comparable<Entry> {

    override fun compareTo(other: Entry): Int {
        val byTime = timestamp.compareTo(other.timestamp)
        if (byTime != 0) return byTime
        val byDevice = device.compareTo(other.device)
        if (byDevice != 0) return byDevice
        return value.compareTo(other.value)
    }

    companion object {
        /** Der „gewinnende“ Eintrag von zweien. */
        fun newer(a: Entry?, b: Entry?): Entry? = when {
            a == null -> b
            b == null -> a
            a >= b -> a
            else -> b
        }
    }
}
