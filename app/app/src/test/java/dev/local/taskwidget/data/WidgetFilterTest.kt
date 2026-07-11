package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WidgetFilterTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 11)

    private fun task(
        text: String,
        due: String? = null,
        tags: List<String> = emptyList(),
        path: String = "Tasks.md",
    ) = TaskItem(
        fileUri = "uri://x",
        fileName = path.substringAfterLast('/'),
        path = path,
        rawLine = "- [ ] $text",
        text = text,
        dueDate = due,
        priorityOrder = Priority.NONE.order,
        tags = tags,
    )

    private val tasks = listOf(
        task("过期", due = "2026-07-01"),
        task("今天", due = "2026-07-11"),
        task("周内", due = "2026-07-15"),
        task("下月", due = "2026-08-20"),
        task("无日期"),
        task("带标签", tags = listOf("工作")),
        task("工作目录", path = "工作/项目.md"),
    )

    @Test
    fun `scope all keeps everything`() {
        assertEquals(7, WidgetFilter().apply(tasks, today).size)
    }

    @Test
    fun `scope today keeps overdue and today`() {
        val result = WidgetFilter(dateScope = WidgetFilter.SCOPE_TODAY, includeUndated = false)
            .apply(tasks, today)
        assertEquals(listOf("过期", "今天"), result.map { it.text })
    }

    @Test
    fun `scope week keeps within seven days`() {
        val result = WidgetFilter(dateScope = WidgetFilter.SCOPE_WEEK, includeUndated = false)
            .apply(tasks, today)
        assertEquals(listOf("过期", "今天", "周内"), result.map { it.text })
    }

    @Test
    fun `include undated adds dateless tasks`() {
        val result = WidgetFilter(dateScope = WidgetFilter.SCOPE_TODAY, includeUndated = true)
            .apply(tasks, today)
        assertEquals(listOf("过期", "今天", "无日期", "带标签", "工作目录"), result.map { it.text })
    }

    @Test
    fun `tag filter matches any`() {
        val result = WidgetFilter(tags = listOf("工作", "其他")).apply(tasks, today)
        assertEquals(listOf("带标签"), result.map { it.text })
    }

    @Test
    fun `path filter case insensitive contains`() {
        val result = WidgetFilter(pathContains = "工作/").apply(tasks, today)
        assertEquals(listOf("工作目录"), result.map { it.text })
    }

    @Test
    fun `json round trip`() {
        val original = WidgetFilter(
            title = "工作",
            dateScope = WidgetFilter.SCOPE_WEEK,
            includeUndated = false,
            tags = listOf("工作", "urgent"),
            pathContains = "工作/",
        )
        assertEquals(original, WidgetFilter.fromJson(original.toJson()))
    }

    @Test
    fun `from json null or garbage gives defaults`() {
        assertEquals(WidgetFilter(), WidgetFilter.fromJson(null))
        assertEquals(WidgetFilter(), WidgetFilter.fromJson("not json"))
    }

    @Test
    fun `parse tags input strips hash and splits`() {
        assertEquals(
            listOf("工作", "urgent", "家"),
            WidgetFilter.parseTagsInput("#工作, urgent,家")
        )
    }
}
