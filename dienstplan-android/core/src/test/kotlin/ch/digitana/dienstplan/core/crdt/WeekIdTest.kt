package ch.digitana.dienstplan.core.crdt

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class WeekIdTest {

    @Test
    fun `ISO-Kalenderwochen an Jahresgrenzen`() {
        assertEquals(WeekId(2026, 1), WeekId.of(LocalDate.of(2026, 1, 1))) // Donnerstag
        assertEquals(WeekId(2025, 1), WeekId.of(LocalDate.of(2024, 12, 30))) // Montag gehört zu 2025-W01
        assertEquals(WeekId(2026, 53), WeekId.of(LocalDate.of(2027, 1, 1))) // 2026 hat 53 Wochen
        assertEquals(WeekId(2021, 52), WeekId.of(LocalDate.of(2022, 1, 2)))
        assertEquals(WeekId(1999, 52), WeekId.of(LocalDate.of(2000, 1, 1)))
    }

    @Test
    fun `Montag, Sonntag und Bucket-Name`() {
        val week = WeekId.of(LocalDate.of(2026, 9, 28))
        assertEquals(WeekId(2026, 40), week)
        assertEquals(LocalDate.of(2026, 9, 28), week.monday)
        assertEquals(LocalDate.of(2026, 10, 4), week.sunday)
        assertEquals("2026-W40", week.bucketName)
        assertEquals(7, week.days.size)
        assertEquals(WeekId(2026, 41), week.next())
        assertEquals(WeekId(2026, 39), week.previous())
        assertEquals(WeekId(2027, 1), WeekId(2026, 53).next())
        assertEquals(WeekId(2026, 53), WeekId(2027, 1).previous())
    }

    @Test
    fun `jede Woche von 2000 bis 2100 hat einen gueltigen Bucket-Namen`() {
        var date = PlanKeys.MIN_DATE
        while (!date.isAfter(PlanKeys.MAX_DATE)) {
            val week = WeekId.of(date)
            assertEquals(week, Buckets.parseWeek(week.bucketName), week.bucketName)
            assertEquals(true, week.contains(date))
            date = date.plusDays(1)
        }
    }
}
