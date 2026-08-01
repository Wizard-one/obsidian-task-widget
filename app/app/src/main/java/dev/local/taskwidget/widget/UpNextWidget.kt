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
import androidx.glance.appwidget.action.actionStartActivity
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
import dev.local.taskwidget.data.VaultRepository

/** "接下来":最近到期的若干任务(含已过期),复选框完成、点按编辑 */
class UpNextWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val configured = VaultRepository.getVaultUri(context) != null
        var errorMsg: String? = null
        val tasks = try {
            CalendarData.upcoming(context, limit = 20)
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
                            "接下来",
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
                        errorMsg != null -> Hint("加载出错:$errorMsg")
                        !configured -> Hint("尚未选择 Vault")
                        tasks.isEmpty() -> Hint("🎉 没有待办任务")
                        // 普通 Column 而非 LazyColumn:后者底层集合适配器在部分启动器上 updateAll 不刷新
                        else -> Column(modifier = GlanceModifier.fillMaxSize()) {
                            for (t in tasks.distinctBy { taskId(it) }) AgendaTaskRow(t, showDate = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
    }
}

class UpNextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpNextWidget()
}
