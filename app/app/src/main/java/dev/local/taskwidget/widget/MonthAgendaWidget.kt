package dev.local.taskwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.local.taskwidget.data.VaultRepository
import java.time.YearMonth

/** 月+议程:上方月网格(可点选日期),下方显示选中日的任务 */
class MonthAgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        } catch (_: Exception) {
            AppWidgetManager.INVALID_APPWIDGET_ID
        }
        val offset = CalendarWidgetState.monthOffset(context, appWidgetId)
        val month = YearMonth.now().plusMonths(offset.toLong())
        val selected = CalendarWidgetState.selectedDay(context, appWidgetId)
        val configured = VaultRepository.getVaultUri(context) != null
        var errorMsg: String? = null
        var cells: List<CalendarData.DayCell> = emptyList()
        var dayTasks: List<dev.local.taskwidget.data.TaskItem> = emptyList()
        try {
            cells = CalendarData.monthGrid(context, month)
            dayTasks = CalendarData.tasksOn(context, selected).take(WIDGET_MAX_ITEMS)
        } catch (t: Throwable) {
            errorMsg = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(120)
        }

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier.fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground).padding(10.dp)
                ) {
                    MonthNavHeader(month)
                    Spacer(GlanceModifier.height(4.dp))
                    if (errorMsg != null) {
                        Text("加载出错:$errorMsg", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                    } else if (!configured) {
                        Text("尚未选择 Vault", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                    } else {
                        MonthGridView(month, cells, selected) { date -> SelectDayAction.params(date).let {
                            androidx.glance.appwidget.action.actionRunCallback<SelectDayAction>(it)
                        } }
                        Spacer(GlanceModifier.height(6.dp))
                        Text(
                            "${selected.monthValue}月${selected.dayOfMonth}日",
                            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        )
                        if (dayTasks.isEmpty()) {
                            Text("当天无任务", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                        } else {
                            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                                items(dayTasks, itemId = { taskId(it) }) { AgendaTaskRow(it, showDate = false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

class MonthAgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthAgendaWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        CalendarWidgetState.delete(context, appWidgetIds)
    }
}
