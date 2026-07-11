package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskParserTest {

    @Test
    fun `plain todo`() {
        val t = TaskParser.parseLine("- [ ] 买菜")!!
        assertEquals("买菜", t.text)
        assertFalse(t.done)
        assertNull(t.dueDate)
        assertEquals(Priority.NONE, t.priority)
    }

    @Test
    fun `done task`() {
        val t = TaskParser.parseLine("- [x] 买菜 ✅ 2026-07-10")!!
        assertTrue(t.done)
        assertEquals("买菜", t.text)
    }

    @Test
    fun `due date and priority stripped from text`() {
        val t = TaskParser.parseLine("- [ ] 交房租 📅 2026-07-15 ⏫")!!
        assertEquals("交房租", t.text)
        assertEquals(LocalDate.of(2026, 7, 15), t.dueDate)
        assertEquals(Priority.HIGH, t.priority)
    }

    @Test
    fun `all priority markers`() {
        assertEquals(Priority.HIGHEST, TaskParser.parseLine("- [ ] a 🔺")!!.priority)
        assertEquals(Priority.HIGH, TaskParser.parseLine("- [ ] a ⏫")!!.priority)
        assertEquals(Priority.MEDIUM, TaskParser.parseLine("- [ ] a 🔼")!!.priority)
        assertEquals(Priority.LOW, TaskParser.parseLine("- [ ] a 🔽")!!.priority)
        assertEquals(Priority.LOWEST, TaskParser.parseLine("- [ ] a ⏬")!!.priority)
    }

    @Test
    fun `indented and star bullets`() {
        assertEquals("子任务", TaskParser.parseLine("    - [ ] 子任务")!!.text)
        assertEquals("星号任务", TaskParser.parseLine("* [ ] 星号任务")!!.text)
    }

    @Test
    fun `non task lines return null`() {
        assertNull(TaskParser.parseLine("普通文本"))
        assertNull(TaskParser.parseLine("- 无复选框列表项"))
    }

    @Test
    fun `status parsing`() {
        assertEquals(TaskStatus.TODO, TaskParser.parseLine("- [ ] a")!!.status)
        assertEquals(TaskStatus.DONE, TaskParser.parseLine("- [x] a")!!.status)
        assertEquals(TaskStatus.CANCELLED, TaskParser.parseLine("- [-] a")!!.status)
        assertEquals(TaskStatus.IN_PROGRESS, TaskParser.parseLine("- [/] a")!!.status)
    }

    @Test
    fun `recurrence and metadata stripped`() {
        val t = TaskParser.parseLine("- [ ] 浇花 🔁 every week 📅 2026-07-12 🆔 abc123")!!
        assertEquals("浇花", t.text)
        assertEquals(LocalDate.of(2026, 7, 12), t.dueDate)
    }

    @Test
    fun `scheduled and start dates stripped but not treated as due`() {
        val t = TaskParser.parseLine("- [ ] 写报告 ⏳ 2026-07-11 🛫 2026-07-09")!!
        assertEquals("写报告", t.text)
        assertNull(t.dueDate)
    }

    @Test
    fun `tags kept in text`() {
        val t = TaskParser.parseLine("- [ ] 复习 #考试 📅 2026-07-20")!!
        assertEquals("复习 #考试", t.text)
    }

    @Test
    fun `complete line appends done date`() {
        val today = LocalDate.of(2026, 7, 11)
        assertEquals(
            "- [x] 买菜 ✅ 2026-07-11",
            TaskParser.completeLine("- [ ] 买菜", today)
        )
        assertEquals(
            "  - [x] 交房租 📅 2026-07-15 ⏫ ✅ 2026-07-11",
            TaskParser.completeLine("  - [ ] 交房租 📅 2026-07-15 ⏫", today)
        )
        assertNull(TaskParser.completeLine("- [x] 已完成", today))
    }

    @Test
    fun `complete in content preserves other lines and crlf`() {
        val today = LocalDate.of(2026, 7, 11)
        val content = "# 标题\r\n- [ ] 甲\r\n- [ ] 乙\r\n"
        val result = TaskParser.completeInContent(content, "- [ ] 乙", today)!!
        assertEquals("# 标题\r\n- [ ] 甲\r\n- [x] 乙 ✅ 2026-07-11\r\n", result)
    }

    @Test
    fun `complete in content lf only`() {
        val today = LocalDate.of(2026, 7, 11)
        val content = "- [ ] 甲\n- [ ] 乙"
        val result = TaskParser.completeInContent(content, "- [ ] 甲", today)!!
        assertEquals("- [x] 甲 ✅ 2026-07-11\n- [ ] 乙", result)
    }

    @Test
    fun `complete in content returns null when line missing`() {
        assertNull(TaskParser.completeInContent("- [ ] 甲", "- [ ] 不存在", LocalDate.now()))
    }

    @Test
    fun `tags parsed including chinese`() {
        val t = TaskParser.parseLine("- [ ] 复习 #考试 #work/urgent 📅 2026-07-20")!!
        assertEquals(listOf("考试", "work/urgent"), t.tags)
    }

    @Test
    fun `edit line rebuilds text due priority`() {
        val edited = TaskParser.editLine(
            "  - [ ] 旧文本 📅 2026-07-15 ⏫",
            "新文本 #tag", LocalDate.of(2026, 8, 1), Priority.LOW
        )
        assertEquals("  - [ ] 新文本 #tag 🔽 📅 2026-08-01", edited)
    }

    @Test
    fun `edit line preserves unmanaged metadata`() {
        val edited = TaskParser.editLine(
            "- [ ] 浇花 🔁 every week ⏳ 2026-07-12 🆔 abc 📅 2026-07-13",
            "浇花草", null, Priority.NONE
        )
        assertEquals("- [ ] 浇花草 🔁 every week ⏳ 2026-07-12 🆔 abc", edited)
    }

    @Test
    fun `edit line clears due and priority`() {
        val edited = TaskParser.editLine("- [ ] 甲 📅 2026-07-15 ⏫", "甲", null, Priority.NONE)
        assertEquals("- [ ] 甲", edited)
    }

    @Test
    fun `edit line on non task returns null`() {
        assertNull(TaskParser.editLine("普通文本", "x", null, Priority.NONE))
    }

    @Test
    fun `edit in content replaces only target line`() {
        val content = "- [ ] 甲\n- [ ] 乙 📅 2026-07-15\n"
        val result = TaskParser.editInContent(
            content, "- [ ] 乙 📅 2026-07-15",
            "乙改", LocalDate.of(2026, 7, 20), Priority.HIGH
        )
        assertEquals("- [ ] 甲\n- [ ] 乙改 ⏫ 📅 2026-07-20\n", result)
    }

    @Test
    fun `parse file returns only open tasks`() {
        val tasks = TaskParser.parseFile("# 笔记\n- [ ] 甲\n- [x] 乙 ✅ 2026-07-01\n- [ ] 丙 📅 2026-07-12\n- [-] 丁\n文字\n")
        assertEquals(listOf("甲", "丙"), tasks.map { it.text })
    }

    // ---- 完整字段 / Dataview / 子任务 ----

    @Test
    fun `all emoji date fields parsed`() {
        val t = TaskParser.parseLine(
            "- [ ] 项目 🛫 2026-07-01 ⏳ 2026-07-05 📅 2026-07-10 ➕ 2026-06-30"
        )!!
        assertEquals(LocalDate.of(2026, 7, 1), t.startDate)
        assertEquals(LocalDate.of(2026, 7, 5), t.scheduledDate)
        assertEquals(LocalDate.of(2026, 7, 10), t.dueDate)
        assertEquals(LocalDate.of(2026, 6, 30), t.createdDate)
        assertEquals("项目", t.text)
    }

    @Test
    fun `id and depends parsed`() {
        val t = TaskParser.parseLine("- [ ] 部署 🆔 deploy1 ⛔ build1, test1")!!
        assertEquals("deploy1", t.id)
        assertEquals(listOf("build1", "test1"), t.dependsOn)
        assertEquals("部署", t.text)
    }

    @Test
    fun `dataview inline fields parsed`() {
        val t = TaskParser.parseLine("- [ ] 报税 [due:: 2026-07-15] [priority:: high] [repeat:: every month]")!!
        assertEquals(LocalDate.of(2026, 7, 15), t.dueDate)
        assertEquals(Priority.HIGH, t.priority)
        assertEquals("every month", t.recurrence)
        assertEquals("报税", t.text)
    }

    @Test
    fun `subtask indent level`() {
        assertEquals(0, TaskParser.parseLine("- [ ] 父")!!.indent)
        assertEquals(1, TaskParser.parseLine("  - [ ] 子")!!.indent)
        assertEquals(2, TaskParser.parseLine("    - [ ] 孙")!!.indent)
        assertEquals(1, TaskParser.parseLine("\t- [ ] tab子")!!.indent)
    }

    // ---- 循环任务 ----

    @Test
    fun `recurring task detected`() {
        val t = TaskParser.parseLine("- [ ] 浇花 🔁 every week 📅 2026-07-12")!!
        assertEquals("every week", t.recurrence)
        assertTrue(t.isRecurring)
    }

    @Test
    fun `complete recurring inserts next instance above`() {
        val today = LocalDate.of(2026, 7, 12)
        val content = "- [ ] 浇花 🔁 every week 📅 2026-07-12\n"
        val result = TaskParser.completeInContent(content, "- [ ] 浇花 🔁 every week 📅 2026-07-12", today)!!
        assertEquals(
            "- [ ] 浇花 🔁 every week 📅 2026-07-19\n- [x] 浇花 🔁 every week 📅 2026-07-12 ✅ 2026-07-12\n",
            result
        )
    }

    @Test
    fun `complete non-recurring has no extra line`() {
        val today = LocalDate.of(2026, 7, 12)
        val result = TaskParser.completeInContent("- [ ] 甲 📅 2026-07-12\n", "- [ ] 甲 📅 2026-07-12", today)!!
        assertEquals("- [x] 甲 📅 2026-07-12 ✅ 2026-07-12\n", result)
    }

    @Test
    fun `recurrence shifts all date fields together`() {
        val next = TaskParser.nextRecurrenceLine(
            "- [ ] a 🛫 2026-07-01 ⏳ 2026-07-05 📅 2026-07-10 🔁 every week",
            LocalDate.of(2026, 7, 10)
        )!!
        assertTrue("start shifted", next.contains("🛫 2026-07-08"))
        assertTrue("scheduled shifted", next.contains("⏳ 2026-07-12"))
        assertTrue("due shifted", next.contains("📅 2026-07-17"))
    }
}
