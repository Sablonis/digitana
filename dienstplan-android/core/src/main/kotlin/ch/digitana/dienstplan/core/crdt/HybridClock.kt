package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.util.Clock

/**
 * Hybride logische Uhr (Millisekunden).
 *
 * - Lokale Änderung: `neu = max(jetzt, letzter + 1)` – streng monoton, auch wenn
 *   die Systemuhr zurückspringt.
 * - Empfang: Die Uhr wird auf den höchsten gesehenen (bereits validierten)
 *   Zeitstempel nachgezogen, damit eine spätere lokale Änderung ihn überholt.
 */
class HybridClock(private val clock: Clock, initial: Long = 0) {
    private var last: Long = initial

    @Synchronized
    fun next(): Long {
        val now = clock.nowMillis()
        last = maxOf(now, last + 1)
        return last
    }

    @Synchronized
    fun observe(timestamp: Long) {
        if (timestamp > last) last = timestamp
    }

    @Synchronized
    fun current(): Long = last
}
