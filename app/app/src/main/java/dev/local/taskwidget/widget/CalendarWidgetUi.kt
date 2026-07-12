package dev.local.taskwidget.widget

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.local.taskwidget.EditTaskActivity
import dev.local.taskwidget.data.Priority
import dev.local.taskwidget.data.TaskItem
import java.time.LocalDate
import java.time.YearMonth

private val OVERDUE = ColorProvider(Color(0xFFD32F2F), Color(0xFFFF8A80))

/** 议程/列表里的一行任务:复选框完成 + 文本(点按编辑) */
@Composable
fun AgendaTaskRow(task: TaskItem, showDate: Boolean) {
    val context = LocalContext.current
    val editIntent = Intent(context, EditTaskActivity::class.java)
        .putExtra(EditTaskActivity.EXTRA_FILE_URI, task.fileUri)
        .putExtra(EditTaskActivity.EXTRA_RAW_LINE, task.rawLine)
        .setData(Uri.parse("taskwidget://edit/${task.fileUri.hashCode()}/${task.rawLine.hashCode()}"))

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<CompleteTaskAction>(CompleteTaskAction.params(task))
        )
        Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(editIntent))) {
            Text(
                text = priorityPrefix(task.priorityOrder) + task.text,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                maxLines = 1
            )
            if (showDate) {
                task.due?.let { due ->
                    Text(
                        text = dueLabel(due),
                        style = TextStyle(
                            color = if (due.isBefore(LocalDate.now())) OVERDUE
                            else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }
    }
}

/** 议程分组表头。null 日期 = 已过期。 */
@Composable
fun AgendaHeader(date: LocalDate?) {
    val label = when {
        date == null -> "已过期"
        date == LocalDate.now() -> "今天"
        date == LocalDate.now().plusDays(1) -> "明天"
        else -> "${date.monthValue}月${date.dayOfMonth}日 ${weekdayCn(date)}"
    }
    Text(
        text = label,
        style = TextStyle(
            color = if (date == null) OVERDUE else GlanceTheme.colors.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        ),
        modifier = GlanceModifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

/**
 * 月历网格(6×7,周一起始)。[onDayAction] 若非空,则每个格子可点击(用于选日期)。
 */
@Composable
fun MonthGridView(
    month: YearMonth,
    cells: List<CalendarData.DayCell>,
    selected: LocalDate?,
    dayAction: ((LocalDate) -> androidx.glance.action.Action)?,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            for (h in CalendarData.WEEKDAY_HEADERS) {
                Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                    Text(h, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp))
                }
            }
        }
        for (week in 0 until 6) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val cell = cells[week * 7 + dow]
                    DayCellView(cell, cell.date == selected, dayAction, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun DayCellView(
    cell: CalendarData.DayCell,
    isSelected: Boolean,
    dayAction: ((LocalDate) -> androidx.glance.action.Action)?,
    weightModifier: GlanceModifier,
) {
    val bg = when {
        isSelected -> GlanceTheme.colors.primaryContainer
        cell.isToday -> GlanceTheme.colors.secondaryContainer
        else -> GlanceTheme.colors.widgetBackground
    }
    var mod = weightModifier.padding(1.dp)
    if (dayAction != null) mod = mod.clickable(dayAction(cell.date))

    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Column(
            modifier = GlanceModifier.background(bg).padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = TextStyle(
                    color = if (cell.inMonth) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal
                )
            )
            // 有任务的日子:显示圆点(用小字符代替,Glance 无绘制圆点原语)
            Text(
                text = if (cell.taskCount > 0) "•" else " ",
                style = TextStyle(
                    color = if (cell.taskCount > 0) GlanceTheme.colors.primary else GlanceTheme.colors.widgetBackground,
                    fontSize = 10.sp
                )
            )
        }
    }
}

internal fun priorityPrefix(order: Int): String = when (order) {
    Priority.HIGHEST.order -> "🔺 "
    Priority.HIGH.order -> "⏫ "
    Priority.MEDIUM.order -> "🔼 "
    else -> ""
}

internal fun dueLabel(due: LocalDate): String {
    val today = LocalDate.now()
    return when {
        due.isBefore(today) -> "已过期 · ${due.monthValue}/${due.dayOfMonth}"
        due == today -> "今天"
        due == today.plusDays(1) -> "明天"
        else -> "${due.monthValue}/${due.dayOfMonth}"
    }
}

private fun weekdayCn(date: LocalDate): String =
    "周" + CalendarData.WEEKDAY_HEADERS[date.dayOfWeek.value - 1]
