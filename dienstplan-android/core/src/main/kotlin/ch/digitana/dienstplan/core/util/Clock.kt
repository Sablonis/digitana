package ch.digitana.dienstplan.core.util

/** Zeitquelle in Millisekunden seit 1970 (austauschbar für Tests). */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
