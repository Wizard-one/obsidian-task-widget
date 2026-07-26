package dev.local.taskwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.local.taskwidget.data.VaultRepository
import java.time.YearMonth

/** 迷你月历:月网格 + 有任务的日子标点;可翻月 */
class MonthMiniWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        } catch (_: Exception) {
            AppWidgetManager.INVALID_APPWIDGET_ID
        }
        val offset = CalendarWidgetState.monthOffset(context, appWidgetId)
        val month = YearMonth.now().plusMonths(offset.toLong())
        val configured = VaultRepository.getVaultUri(context) != null
        var errorMsg: String? = null
        val cells = try {
            CalendarData.monthGrid(context, month)
        } catch (t: Throwable) {
            errorMsg = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(120)
            emptyList()
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
                        MonthGridView(month, cells, selected = null, dayAction = null)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MonthNavHeader(month: YearMonth) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            "${month.year}年${month.monthValue}月",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.defaultWeight())
        NavBtn("‹") { actionRunCallback<PrevMonthAction>() }
        Spacer(GlanceModifier.width(2.dp))
        NavBtn("今") { actionRunCallback<TodayMonthAction>() }
        Spacer(GlanceModifier.width(2.dp))
        NavBtn("›") { actionRunCallback<NextMonthAction>() }
    }
}

@Composable
private fun NavBtn(label: String, action: () -> androidx.glance.action.Action) {
    Text(
        label,
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        modifier = GlanceModifier.padding(horizontal = 6.dp, vertical = 2.dp).clickable(action())
    )
}

class MonthMiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthMiniWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        CalendarWidgetState.delete(context, appWidgetIds)
    }
}
