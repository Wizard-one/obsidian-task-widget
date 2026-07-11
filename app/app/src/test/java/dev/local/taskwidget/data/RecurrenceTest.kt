package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceTest {

    private fun due(rule: String, from: String, done: String = from): LocalDate? =
        Recurrence.parse(rule)!!.nextDates(LocalDate.parse(from), null, null, LocalDate.parse(done))?.due

    @Test
    fun `every day`() {
        assertEquals(LocalDate.of(2026, 7, 12), due("every day", "2026-07-11"))
    }

    @Test
    fun `every N days`() {
        assertEquals(LocalDate.of(2026, 7, 14), due("every 3 days", "2026-07-11"))
    }

    @Test
    fun `every week`() {
        assertEquals(LocalDate.of(2026, 7, 18), due("every week", "2026-07-11"))
    }

    @Test
    fun `every 2 weeks`() {
        assertEquals(LocalDate.of(2026, 7, 25), due("every 2 weeks", "2026-07-11"))
    }

    @Test
    fun `every month clamps end of month`() {
        assertEquals(LocalDate.of(2026, 2, 28), due("every month", "2026-01-31"))
    }

    @Test
    fun `every year`() {
        assertEquals(LocalDate.of(2027, 7, 11), due("every year", "2026-07-11"))
    }

    @Test
    fun `every weekday skips weekend`() {
        // 2026-07-10 是周五,下一个工作日是周一 07-13
        val r = Recurrence.parse("every weekday")!!
        val next = r.applyTo(LocalDate.of(2026, 7, 10))
        assertEquals(LocalDate.of(2026, 7, 13), next)
        assertTrue(next.dayOfWeek != DayOfWeek.SATURDAY && next.dayOfWeek != DayOfWeek.SUNDAY)
    }

    @Test
    fun `every specific weekday`() {
        // 从周三 07-08 起,下一个周一是 07-13
        assertEquals(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 7, 8).dayOfWeek)
        assertEquals(LocalDate.of(2026, 7, 13), due("every monday", "2026-07-08"))
    }

    @Test
    fun `when done anchors on completion date`() {
        // 到期 07-01(已过期),07-11 完成,every week when done → 07-18
        assertEquals(
            LocalDate.of(2026, 7, 18),
            due("every week when done", from = "2026-07-01", done = "2026-07-11")
        )
    }

    @Test
    fun `without when done anchors on due date`() {
        assertEquals(
            LocalDate.of(2026, 7, 8),
            due("every week", from = "2026-07-01", done = "2026-07-11")
        )
    }

    @Test
    fun `no reference date cannot recur`() {
        assertNull(Recurrence.parse("every week")!!.nextDates(null, null, null, LocalDate.now()))
    }

    @Test
    fun `unparseable rule`() {
        assertNull(Recurrence.parse("someday maybe"))
        assertNull(Recurrence.parse(null))
        assertNull(Recurrence.parse(""))
    }
}
