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
        val tasks = filter.apply(VaultRepository.loadTasks(context))
        provideContent {
            GlanceTheme {
                WidgetContent(configured, tasks, filter, appWidgetId)
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
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp)
    ) {
        Header(filter.title, tasks.size, appWidgetId)
        Spacer(GlanceModifier.height(8.dp))
        when {
            !configured -> CenterHint("尚未选择 Vault\n点击这里去设置")
            tasks.isEmpty() -> CenterHint("🎉 没有待办任务")
            else -> TaskList(tasks)
        }
    }
}

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
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
        )
        Text(
            text = "  $count",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp)
        )
        Spacer(GlanceModifier.defaultWeight())
        val addIntent = Intent(context, dev.local.taskwidget.QuickAddActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Image(
            provider = ImageProvider(R.drawable.ic_add),
            contentDescription = "快速添加",
            modifier = GlanceModifier
                .size(22.dp)
                .clickable(actionStartActivityIntent(addIntent))
        )
        Spacer(GlanceModifier.width(10.dp))
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val configIntent = Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("taskwidget://config/$appWidgetId"))
            Image(
                provider = ImageProvider(R.drawable.ic_settings),
                contentDescription = "筛选设置",
                modifier = GlanceModifier
                    .size(22.dp)
                    .clickable(actionStartActivityIntent(configIntent))
            )
            Spacer(GlanceModifier.width(10.dp))
        }
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "刷新",
            modifier = GlanceModifier
                .size(22.dp)
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
private fun TaskList(tasks: List<TaskItem>) {
    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(tasks) { task ->
            TaskRow(task)
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
            .padding(vertical = 4.dp),
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
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                maxLines = 2
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
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}

