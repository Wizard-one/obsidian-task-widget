package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NaturalDateTest {

    // 2026-07-11 是周六
    private val today: LocalDate = LocalDate.of(2026, 7, 11)

    private fun d(text: String) = NaturalDate.parse(text, today)

    @Test
    fun `keywords`() {
        assertEquals(today, d("today"))
        assertEquals(today.plusDays(1), d("tomorrow"))
        assertEquals(today.minusDays(1), d("yesterday"))
        assertEquals(today.plusDays(2), d("day after tomorrow"))
    }

    @Test
    fun `chinese keywords`() {
        assertEquals(today.plusDays(1), d("明天"))
        assertEquals(today.plusDays(2), d("后天"))
        assertEquals(today.plusWeeks(1), d("下周"))
    }

    @Test
    fun `in N units`() {
        assertEquals(today.plusDays(3), d("in 3 days"))
        assertEquals(today.plusWeeks(2), d("in 2 weeks"))
        assertEquals(today.plusMonths(1), d("in 1 month"))
    }

    @Test
    fun `next unit`() {
        assertEquals(today.plusWeeks(1), d("next week"))
        assertEquals(today.plusMonths(1), d("next month"))
    }

    @Test
    fun `day of week is next occurrence`() {
        // 周六起,下一个周一是 07-13
        assertEquals(LocalDate.of(2026, 7, 13), d("monday"))
        assertEquals(LocalDate.of(2026, 7, 13), d("next mon"))
    }

    @Test
    fun `iso and month-day`() {
        assertEquals(LocalDate.of(2026, 7, 15), d("2026-07-15"))
        assertEquals(LocalDate.of(2026, 7, 15), d("7/15"))
    }

    @Test
    fun `no date`() {
        assertNull(d("买菜"))
        assertNull(d("call mom"))
    }

    @Test
    fun `extract strips phrase from text`() {
        val (text, date) = NaturalDate.extractDue("交房租 tomorrow", today)
        assertEquals("交房租", text)
        assertEquals(today.plusDays(1), date)
    }

    @Test
    fun `extract chinese`() {
        val (text, date) = NaturalDate.extractDue("明天 开会", today)
        assertEquals("开会", text)
        assertEquals(today.plusDays(1), date)
    }
}
