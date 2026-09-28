package ch.digitana.dienstplan.core.crdt

import ch.digitana.dienstplan.core.util.Clock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridClockTest {

    private var now = 1_000L
    private val clock = HybridClock(Clock { now })

    @Test
    fun `folgt der Systemuhr`() {
        assertEquals(1_000, clock.next())
        now = 5_000
        assertEquals(5_000, clock.next())
    }

    @Test
    fun `streng monoton innerhalb derselben Millisekunde`() {
        val a = clock.next()
        val b = clock.next()
        val c = clock.next()
        assertEquals(listOf(1_000L, 1_001L, 1_002L), listOf(a, b, c))
    }

    @Test
    fun `springt die Systemuhr zurueck, laeuft die Uhr trotzdem vorwaerts`() {
        now = 10_000
        val a = clock.next()
        now = 2_000
        val b = clock.next()
        assertTrue(b > a)
        assertEquals(a + 1, b)
    }

    @Test
    fun `Empfang zieht die Uhr nach`() {
        clock.observe(50_000)
        assertEquals(50_001, clock.next())
        clock.observe(10) // älter: ohne Wirkung
        assertEquals(50_002, clock.next())
    }
}
