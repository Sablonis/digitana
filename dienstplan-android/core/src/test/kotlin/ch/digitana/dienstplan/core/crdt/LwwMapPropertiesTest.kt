package ch.digitana.dienstplan.core.crdt

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Eigenschaften des CRDT-Merge mit Zufallsdaten (fester Seed, reproduzierbar).
 * Absichtlich enge Wertebereiche, damit gleiche Zeitstempel und Geräte-IDs häufig
 * vorkommen und die Gleichstandsregeln mitgetestet werden.
 */
class LwwMapPropertiesTest {

    private val random = Random(20260928)
    private val keys = (0 until 12).map { "m|%016x".format(it) }
    private val devices = listOf("0000000000000001", "0000000000000002", "00000000000000ff")
    private val values = listOf("", "Anna", "Ben", "Chiara")

    private fun randomEntry() = Entry(values.random(random), random.nextLong(1, 6), devices.random(random))

    private fun randomMap(): LwwMap {
        var map = LwwMap.EMPTY
        repeat(random.nextInt(0, 16)) { map = map.put(keys.random(random), randomEntry()) }
        return map
    }

    @Test
    fun `kommutativ`() {
        repeat(3000) {
            val a = randomMap()
            val b = randomMap()
            assertEquals(a.merge(b), b.merge(a))
            assertEquals(a.merge(b).digest, b.merge(a).digest)
        }
    }

    @Test
    fun `assoziativ`() {
        repeat(3000) {
            val a = randomMap()
            val b = randomMap()
            val c = randomMap()
            assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
        }
    }

    @Test
    fun `idempotent`() {
        repeat(3000) {
            val a = randomMap()
            assertEquals(a, a.merge(a))
            val b = randomMap()
            val ab = a.merge(b)
            assertEquals(ab, ab.merge(b))
            assertEquals(ab, ab.merge(a))
        }
    }

    @Test
    fun `Reihenfolge und Wiederholung einzelner Aenderungen sind egal`() {
        repeat(500) {
            val updates = List(random.nextInt(1, 30)) { keys.random(random) to randomEntry() }
            val reference = updates.fold(LwwMap.EMPTY) { map, (k, e) -> map.put(k, e) }
            repeat(5) {
                val shuffled = (updates + updates.shuffled(random).take(random.nextInt(0, updates.size + 1))).shuffled(random)
                val result = shuffled.fold(LwwMap.EMPTY) { map, (k, e) -> map.mergeEntries(mapOf(k to e)).map }
                assertEquals(reference, result)
            }
        }
    }

    @Test
    fun `neuerer Zeitstempel gewinnt, dann Geraete-ID, dann Wert`() {
        val base = LwwMap.EMPTY.put("k", Entry("alt", 10, "0000000000000009"))
        assertEquals("neu", base.put("k", Entry("neu", 11, "0000000000000001"))["k"]!!.value)
        assertEquals("alt", base.put("k", Entry("neu", 9, "00000000000000ff"))["k"]!!.value)
        // gleiche Zeit: höhere Geräte-ID gewinnt
        assertEquals("neu", base.put("k", Entry("neu", 10, "000000000000000a"))["k"]!!.value)
        assertEquals("alt", base.put("k", Entry("neu", 10, "0000000000000008"))["k"]!!.value)
        // gleiche Zeit und Geräte-ID (nur bei fehlerhaften Daten): grösserer Wert gewinnt deterministisch
        val a = LwwMap.EMPTY.put("k", Entry("B", 10, "0000000000000009")).put("k", Entry("A", 10, "0000000000000009"))
        val b = LwwMap.EMPTY.put("k", Entry("A", 10, "0000000000000009")).put("k", Entry("B", 10, "0000000000000009"))
        assertEquals(a, b)
        assertEquals("B", a["k"]!!.value)
    }

    @Test
    fun `mergeEntries meldet genau die geaenderten Schluessel`() {
        val base = LwwMap.EMPTY.put("a", Entry("1", 5, devices[0])).put("b", Entry("1", 5, devices[0]))
        val result = base.mergeEntries(
            mapOf(
                "a" to Entry("2", 6, devices[0]), // neuer
                "b" to Entry("0", 4, devices[0]), // älter
                "c" to Entry("1", 1, devices[0]), // neu
            ),
        )
        assertEquals(setOf("a", "c"), result.changedKeys)
        assertSame(base, base.mergeEntries(mapOf("b" to Entry("0", 4, devices[0]))).map)
    }

    @Test
    fun `Digest haengt nur vom Inhalt ab`() {
        val a = LwwMap.EMPTY.put("x", Entry("1", 1, devices[0])).put("y", Entry("2", 2, devices[1]))
        val b = LwwMap.EMPTY.put("y", Entry("2", 2, devices[1])).put("x", Entry("1", 1, devices[0]))
        assertEquals(a.digest, b.digest)
        assertTrue(a.digest != a.put("x", Entry("1", 3, devices[0])).digest)
        // Feldgrenzen sind eindeutig: ("ab","c") ≠ ("a","bc")
        val c = LwwMap.EMPTY.put("ab", Entry("c", 1, devices[0]))
        val d = LwwMap.EMPTY.put("a", Entry("bc", 1, devices[0]))
        assertTrue(c.digest != d.digest)
    }
}
