package dev.local.taskwidget.widget

import android.content.Context
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import java.time.LocalDate
import java.time.YearMonth

/** 日历类 widget 共用的取数与月历网格计算 */
object CalendarData {

    /** 月历里的一个格子 */
    data class DayCell(
        val date: LocalDate,
        val taskCount: Int,
        val inMonth: Boolean,
        val isToday: Boolean,
    )

    /** 缓存里所有真正带截止日期的待办任务,按日期分组。开始日不参与逾期日程。 */
    fun tasksByDate(context: Context): Map<LocalDate, List<TaskItem>> =
        VaultRepository.loadTasks(context)
            .filter { it.actualDue != null }
            .groupBy { it.actualDue!! }

    /** 某天的任务(按优先级排序) */
    fun tasksOn(context: Context, date: LocalDate): List<TaskItem> =
        (tasksByDate(context)[date] ?: emptyList()).sortedBy { it.priorityOrder }

    /**
     * "接下来":已过期 + 今天 + 未来,按到期日→优先级排序,取前 [limit] 个。
     */
    fun upcoming(context: Context, today: LocalDate = LocalDate.now(), limit: Int = 12): List<TaskItem> =
        VaultRepository.loadTasks(context)
            .filter { it.actualDue != null }
            .sortedWith(compareBy({ it.actualDue }, { it.priorityOrder }))
            .take(limit)

    /**
     * 每日议程:从今天起 [days] 天,每天一个分组(含"已过期"置顶);只保留有任务的分组。
     */
    fun agenda(
        context: Context,
        today: LocalDate = LocalDate.now(),
        days: Int = 7,
    ): List<Pair<LocalDate?, List<TaskItem>>> {
        val byDate = tasksByDate(context)
        val result = mutableListOf<Pair<LocalDate?, List<TaskItem>>>()

        val overdue = byDate.filterKeys { it.isBefore(today) }.values.flatten()
            .sortedWith(compareBy({ it.actualDue }, { it.priorityOrder }))
        if (overdue.isNotEmpty()) result += null to overdue // null 表头 = 已过期

        for (i in 0 until days) {
            val d = today.plusDays(i.toLong())
            val tasks = byDate[d]?.sortedBy { it.priorityOrder }
            if (!tasks.isNullOrEmpty()) result += d to tasks
        }
        return result
    }

    /**
     * 生成某月的 6×7 网格(周一起始),含前后月补齐。
     */
    fun monthGrid(
        context: Context,
        month: YearMonth,
        today: LocalDate = LocalDate.now(),
    ): List<DayCell> {
        val counts = tasksByDate(context).mapValues { it.value.size }
        val first = month.atDay(1)
        val startOffset = first.dayOfWeek.value - 1 // 周一=1 → 0
        val gridStart = first.minusDays(startOffset.toLong())
        return (0 until 42).map { i ->
            val d = gridStart.plusDays(i.toLong())
            DayCell(
                date = d,
                taskCount = counts[d] ?: 0,
                inMonth = d.month == month.month && d.year == month.year,
                isToday = d == today,
            )
        }
    }

    val WEEKDAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")
}
