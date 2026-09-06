package dev.local.taskwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.local.taskwidget.R
import dev.local.taskwidget.WidgetActionActivity
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/** 列表最多条数(集合按需取项,不受 RemoteViews 单次 1MB 限制,可放宽) */
internal const val WIDGET_LIST_MAX = 300

/** 任务清单 widget 的可滚动列表数据源(RemoteViews 集合) */
class TaskWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return TaskListFactory(applicationContext, appWidgetId)
    }
}

private class TaskListFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<TaskItem> = emptyList()
    private var iconColor: Int = 0xFF666666.toInt()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        iconColor = dimIconColor(context)
        val configured = VaultRepository.getVaultUri(context) != null
        val all: List<TaskItem> = if (configured) {
            try { runBlocking { VaultRepository.loadTasks(context) } } catch (_: Exception) { emptyList() }
        } else emptyList()
        val filter = WidgetFilterStore.load(context, appWidgetId)
        items = filter.apply(all)
            .distinctBy { it.fileUri + " " + it.rawLine }
            .take(WIDGET_LIST_MAX)
    }

    override fun onDestroy() { items = emptyList() }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val task = items[position]
        val row = RemoteViews(context.packageName, R.layout.widget_task_row)
        setIcon(row, R.id.row_check, context, R.drawable.ic_check_box_outline, 24, iconColor)
        row.setTextViewText(R.id.row_text, priorityPrefix(task.priorityOrder) + task.text)
        val due = task.actualDue
        val start = task.start
        if (due != null) {
            row.setViewVisibility(R.id.row_due, View.VISIBLE)
            row.setTextViewText(R.id.row_due, dueLabel(due))
            row.setTextColor(
                R.id.row_due,
                if (due.isBefore(LocalDate.now())) 0xFFE53935.toInt() else iconColor
            )
        } else if (start != null) {
            row.setViewVisibility(R.id.row_due, View.VISIBLE)
            row.setTextViewText(R.id.row_due, "🛫 ${start.monthValue}/${start.dayOfMonth}")
            row.setTextColor(R.id.row_due, iconColor)
        } else {
            row.setViewVisibility(R.id.row_due, View.GONE)
        }
        // 集合项点击走 fill-in intent(与 provider 的 setPendingIntentTemplate 合并)。
        // 完成/编辑都指向 WidgetActionActivity,用 EXTRA_ACTION 区分;data 唯一避免各行串号。
        row.setOnClickFillInIntent(
            R.id.row_check,
            Intent()
                .putExtra(WidgetActionActivity.EXTRA_ACTION, WidgetActionActivity.ACTION_COMPLETE)
                .putExtra(WidgetActionActivity.EXTRA_FILE_URI, task.fileUri)
                .putExtra(WidgetActionActivity.EXTRA_RAW_LINE, task.rawLine)
                .setData(Uri.parse("taskwidget://complete/${task.fileUri.hashCode()}/${task.rawLine.hashCode()}"))
        )
        row.setOnClickFillInIntent(
            R.id.row_body,
            Intent()
                .putExtra(WidgetActionActivity.EXTRA_ACTION, WidgetActionActivity.ACTION_EDIT)
                .putExtra(WidgetActionActivity.EXTRA_FILE_URI, task.fileUri)
                .putExtra(WidgetActionActivity.EXTRA_RAW_LINE, task.rawLine)
                .setData(Uri.parse("taskwidget://edit/${task.fileUri.hashCode()}/${task.rawLine.hashCode()}"))
        )
        return row
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
