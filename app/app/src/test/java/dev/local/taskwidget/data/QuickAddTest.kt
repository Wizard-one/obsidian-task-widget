package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class QuickAddTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 11)

    @Test
    fun `plain text no date`() {
        assertEquals("- [ ] 买菜", QuickAdd.buildTaskLine("买菜", today = today))
    }

    @Test
    fun `natural date extracted to due`() {
        assertEquals("- [ ] 交房租 📅 2026-07-12", QuickAdd.buildTaskLine("交房租 tomorrow", today = today))
    }

    @Test
    fun `chinese date`() {
        assertEquals("- [ ] 开会 📅 2026-07-12", QuickAdd.buildTaskLine("明天 开会", today = today))
    }

    @Test
    fun `iso date`() {
        assertEquals("- [ ] 报税 📅 2026-07-15", QuickAdd.buildTaskLine("报税 2026-07-15", today = today))
    }

    @Test
    fun `explicit due overrides natural language`() {
        assertEquals(
            "- [ ] 交房租 📅 2026-08-01",
            QuickAdd.buildTaskLine("交房租 tomorrow", dueOverride = LocalDate.of(2026, 8, 1), today = today)
        )
    }

    @Test
    fun `priority and start and due in obsidian order`() {
        assertEquals(
            "- [ ] 写报告 ⏫ 🛫 2026-07-12 📅 2026-07-20",
            QuickAdd.buildTaskLine(
                "写报告",
                dueOverride = LocalDate.of(2026, 7, 20),
                start = LocalDate.of(2026, 7, 12),
                priority = Priority.HIGH,
                today = today,
            )
        )
    }

    @Test
    fun `start only`() {
        assertEquals(
            "- [ ] 项目 🛫 2026-07-15",
            QuickAdd.buildTaskLine("项目", start = LocalDate.of(2026, 7, 15), today = today)
        )
    }
}
