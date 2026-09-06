package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WidgetFilterTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 11)

    private fun task(
        text: String,
        due: String? = null,
        start: String? = null,
        scheduled: String? = null,
        tags: List<String> = emptyList(),
        path: String = "Tasks.md",
    ) = TaskItem(
        fileUri = "uri://x",
        fileName = path.substringAfterLast('/'),
        path = path,
        rawLine = "- [ ] $text",
        text = text,
        dueDate = due ?: scheduled ?: start,
        priorityOrder = Priority.NONE.order,
        tags = tags,
        actualDueDate = due,
        scheduledDate = scheduled,
        startDate = start,
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
    fun `date scope excludes undated even when includeUndated true`() {
        // 今日+已过期是按日期筛的范围,无日期任务不应出现(即便 includeUndated=true)
        val result = WidgetFilter(dateScope = WidgetFilter.SCOPE_TODAY, includeUndated = true)
            .apply(tasks, today)
        assertEquals(listOf("过期", "今天"), result.map { it.text })
    }

    @Test
    fun `date scopes include tasks once their start date is reached`() {
        val happensTasks = listOf(
            task("开始于昨天,截止未来", start = "2026-07-10", due = "2026-07-20"),
            task("开始于明天,截止今天", start = "2026-07-12", due = "2026-07-11"),
            task("开始和截止都在未来", start = "2026-07-12", due = "2026-07-20"),
            task("只有计划日为今天", scheduled = "2026-07-11"),
            task("无日期"),
        )
        val result = WidgetFilter(dateScope = WidgetFilter.SCOPE_TODAY, includeUndated = true)
            .apply(happensTasks, today)
        assertEquals(
            listOf("开始于昨天,截止未来", "开始于明天,截止今天"),
            result.map { it.text }
        )

        val weekResult = WidgetFilter(dateScope = WidgetFilter.SCOPE_WEEK, includeUndated = true)
            .apply(happensTasks, today)
        assertEquals(
            listOf("开始于昨天,截止未来", "开始于明天,截止今天", "开始和截止都在未来"),
            weekResult.map { it.text }
        )
    }

    @Test
    fun `scope all with includeUndated false drops undated`() {
        // 7 条里有 3 条无日期(无日期/带标签/工作目录),includeUndated=false 应只剩 4 条有日期的
        val result = WidgetFilter(dateScope = WidgetFilter.SCOPE_ALL, includeUndated = false)
            .apply(tasks, today)
        assertEquals(listOf("过期", "今天", "周内", "下月"), result.map { it.text })
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
    fun `exclude paths hide matching tasks`() {
        val result = WidgetFilter(excludePaths = listOf("工作/")).apply(tasks, today)
        assertEquals(6, result.size)
        assert(result.none { it.text == "工作目录" })
    }

    @Test
    fun `exclude paths take priority over include`() {
        // 既 include 工作/ 又 exclude 工作/ → 排除优先,结果为空
        val result = WidgetFilter(pathContains = "工作/", excludePaths = listOf("工作/")).apply(tasks, today)
        assertEquals(0, result.size)
    }

    @Test
    fun `parse paths input keeps slashes`() {
        assertEquals(
            listOf("Templates/", "Archive/2025"),
            WidgetFilter.parsePathsInput("Templates/, Archive/2025")
        )
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
