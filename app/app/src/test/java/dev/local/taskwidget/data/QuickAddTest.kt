package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class QuickAddTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 11)

    @Test
    fun `plain text no date`() {
        assertEquals("- [ ] 买菜", QuickAdd.buildTaskLine("买菜", today))
    }

    @Test
    fun `natural date extracted to due`() {
        assertEquals("- [ ] 交房租 📅 2026-07-12", QuickAdd.buildTaskLine("交房租 tomorrow", today))
    }

    @Test
    fun `chinese date`() {
        assertEquals("- [ ] 开会 📅 2026-07-12", QuickAdd.buildTaskLine("明天 开会", today))
    }

    @Test
    fun `iso date`() {
        assertEquals("- [ ] 报税 📅 2026-07-15", QuickAdd.buildTaskLine("报税 2026-07-15", today))
    }
}
