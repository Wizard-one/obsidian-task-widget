package dev.local.taskwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.local.taskwidget.EditTaskActivity
import dev.local.taskwidget.MainActivity
import dev.local.taskwidget.R
import dev.local.taskwidget.WidgetConfigActivity
import dev.local.taskwidget.data.Priority
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.data.WidgetFilter
import java.time.LocalDate

class TaskWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        } catch (_: Exception) {
            AppWidgetManager.INVALID_APPWIDGET_ID
        }
        val filter = WidgetFilterStore.load(context, appWidgetId)
        val configured = VaultRepository.getVaultUri(context) != null
        // 捕获数据阶段异常,把真实错误显示在 widget 上而不是让系统吞成 "Can't show content"
        var errorMsg: String? = null
        val tasks = try {
            filter.apply(VaultRepository.loadTasks(context))
        } catch (t: Throwable) {
            errorMsg = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(140)
            emptyList()
        }
        provideContent {
            GlanceTheme {
                WidgetContent(configured, tasks, filter, appWidgetId, errorMsg)
            }
        }
    }
}

@Composable
private fun WidgetContent(
    configured: Boolean,
    tasks: List<TaskItem>,
    filter: WidgetFilter,
    appWidgetId: Int,
    errorMsg: String?,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Header(filter.title, tasks.size, appWidgetId)
        Spacer(GlanceModifier.height(4.dp))
        when {
            errorMsg != null -> CenterHint("加载出错:\n$errorMsg\n点此打开 App")
            !configured -> CenterHint("尚未选择 Vault\n点击这里去设置")
            tasks.isEmpty() -> CenterHint("🎉 没有待办任务")
            else -> TaskList(tasks.take(WIDGET_MAX_ITEMS), tasks.size)
        }
    }
}

/**
 * widget 单次最多渲染的任务条数。RemoteViews 通过 Binder 传给启动器有约 1MB 上限,
 * 大 vault 一次上百条会超限,启动器报 "Can't show content";故做上限,超出在末尾提示。
 */
internal const val WIDGET_MAX_ITEMS = 50

@Composable
private fun Header(title: String, count: Int, appWidgetId: Int) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
        )
        Text(
            text = "  $count",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
        )
        Spacer(GlanceModifier.defaultWeight())
        val addIntent = Intent(context, dev.local.taskwidget.QuickAddActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Image(
            provider = ImageProvider(R.drawable.ic_add),
            contentDescription = "快速添加",
            modifier = GlanceModifier
                .size(18.dp)
                .clickable(actionStartActivityIntent(addIntent))
        )
        Spacer(GlanceModifier.width(12.dp))
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val configIntent = Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("taskwidget://config/$appWidgetId"))
            Image(
                provider = ImageProvider(R.drawable.ic_settings),
                contentDescription = "筛选设置",
                modifier = GlanceModifier
                    .size(18.dp)
                    .clickable(actionStartActivityIntent(configIntent))
            )
            Spacer(GlanceModifier.width(12.dp))
        }
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "刷新",
            modifier = GlanceModifier
                .size(18.dp)
                .clickable(actionRunCallback<RefreshAction>())
        )
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp)
        )
    }
}

@Composable
private fun TaskList(tasks: List<TaskItem>, total: Int) {
    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        // 稳定 itemId(基于任务身份)——否则某行完成后被移除,下一行会顶上来复用同一 RemoteView,
        // 导致复选框的"已勾选"视觉残留在新任务上;有了稳定 id,Glance 按身份重建行,勾选即消失
        items(tasks, itemId = { taskId(it) }) { task ->
            TaskRow(task)
        }
        if (total > tasks.size) {
            item(itemId = -999L) {
                Text(
                    text = "还有 ${total - tasks.size} 条,点标题在 App 中查看",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    modifier = GlanceModifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem) {
    val context = LocalContext.current
    val editIntent = Intent(context, EditTaskActivity::class.java)
        .putExtra(EditTaskActivity.EXTRA_FILE_URI, task.fileUri)
        .putExtra(EditTaskActivity.EXTRA_RAW_LINE, task.rawLine)
        .setData(Uri.parse("taskwidget://edit/${task.fileUri.hashCode()}/${task.rawLine.hashCode()}"))

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<CompleteTaskAction>(
                CompleteTaskAction.params(task)
            )
        )
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivityIntent(editIntent))
        ) {
            Text(
                text = priorityPrefix(task.priorityOrder) + task.text,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                maxLines = 1
            )
            val due = task.due
            if (due != null) {
                Text(
                    text = dueLabel(due),
                    style = TextStyle(
                        color = if (due.isBefore(LocalDate.now()))
                            ColorProvider(Color(0xFFD32F2F), Color(0xFFFF8A80))
                        else
                            GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

