package dev.local.taskwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.local.taskwidget.R
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import java.time.LocalDate

/** 每日议程:未来 7 天按天分组 + 已过期置顶 */
class DailyAgendaWidget : GlanceAppWidget() {

    private sealed interface Entry {
        data class Head(val date: LocalDate?) : Entry
        data class Task(val item: TaskItem) : Entry
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val configured = VaultRepository.getVaultUri(context) != null
        var errorMsg: String? = null
        val entries = try {
            buildList {
                for ((date, tasks) in CalendarData.agenda(context, days = 7)) {
                    add(Entry.Head(date))
                    tasks.forEach { add(Entry.Task(it)) }
                }
            }
        } catch (t: Throwable) {
            errorMsg = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(120)
            emptyList()
        }
        provideContent {
            val ctx = androidx.glance.LocalContext.current
            GlanceTheme {
                Column(
                    modifier = GlanceModifier.fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground).padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                        Text(
                            "议程",
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        Image(
                            provider = ImageProvider(R.drawable.ic_refresh),
                            contentDescription = "刷新",
                            modifier = GlanceModifier.size(20.dp).clickable(
                                androidx.glance.appwidget.action.actionStartActivity(
                                    dev.local.taskwidget.WidgetActionActivity.refreshIntent(ctx)
                                )
                            )
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    when {
                        errorMsg != null -> Centered("加载出错:$errorMsg")
                        !configured -> Centered("尚未选择 Vault")
                        entries.isEmpty() -> Centered("🎉 未来 7 天没有到期任务")
                        else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                            items(
                                entries.take(WIDGET_MAX_ITEMS),
                                itemId = { e ->
                                    when (e) {
                                        is Entry.Head -> (e.date?.toEpochDay() ?: (Long.MAX_VALUE - 1))
                                        is Entry.Task -> taskId(e.item)
                                    }
                                }
                            ) { entry ->
                                when (entry) {
                                    is Entry.Head -> AgendaHeader(entry.date)
                                    is Entry.Task -> AgendaTaskRow(entry.item, showDate = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
    }
}

class DailyAgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyAgendaWidget()
}
