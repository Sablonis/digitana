package ch.digitana.dienstplan.core.sync

import kotlin.random.Random

/** Exponentieller Backoff mit „Equal Jitter“: Hälfte fest, Hälfte zufällig. */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 60_000,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int): Long {
        val exponential = baseMillis shl attempt.coerceIn(0, 20)
        val capped = minOf(exponential, maxMillis).coerceAtLeast(1)
        val half = capped / 2
        return half + random.nextLong(capped - half + 1)
    }
}
