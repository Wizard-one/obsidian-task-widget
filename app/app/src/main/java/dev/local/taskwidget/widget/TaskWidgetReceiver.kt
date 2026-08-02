package dev.local.taskwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import dev.local.taskwidget.EditTaskActivity
import dev.local.taskwidget.QuickAddActivity
import dev.local.taskwidget.R
import dev.local.taskwidget.TaskListActivity
import dev.local.taskwidget.WidgetActionActivity
import dev.local.taskwidget.WidgetConfigActivity
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.work.RefreshWorker
import dev.local.taskwidget.work.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** widget 一次最多渲染的任务条数(RemoteViews 经 Binder 传给启动器有 ~1MB 上限,且本 widget 不滚动) */
internal const val WIDGET_MAX_ITEMS = 20

/**
 * 任务清单 widget —— 传统 RemoteViews 实现(**不用 Jetpack Glance**)。
 *
 * 为什么弃用 Glance:在 HyperOS/MIUI 上,桌面不会按需重绘 Glance widget——updateAll、
 * 系统广播、按真实 id 直推全都不生效(现象:点完成/刷新后内容和时间戳都不变)。
 * 传统 RemoteViews 由 `AppWidgetManager.updateAppWidget(id, views)` 直接推送,是系统级刷新,
 * MIUI 自家与绝大多数第三方 widget 都用它,必定刷新。
 */
class TaskWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                renderIds(context, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
        ReminderScheduler.rescheduleAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetFilterStore.delete(context, appWidgetIds)
    }

    companion object {
        // 进程级作用域:onUpdate 里 goAsync + 后台加载任务,渲染完再 finish
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** 刷新全部本类 widget 实例(供 updateAllWidgets / 配置页保存后调用) */
        suspend fun renderAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TaskWidgetReceiver::class.java))
            renderIds(context, mgr, ids)
        }

        private suspend fun renderIds(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val configured = VaultRepository.getVaultUri(context) != null
            // 一次性读出全部任务(已排除全局排除路径 + 乐观隐藏),每个实例再按自己的 filter 过滤
            val all: List<TaskItem> = if (configured) {
                try { VaultRepository.loadTasks(context) } catch (_: Exception) { emptyList() }
            } else emptyList()
            for (id in ids) {
                try {
                    mgr.updateAppWidget(id, buildWidget(context, id, configured, all))
                } catch (_: Exception) {
                    // 单个实例失败不影响其它
                }
            }
        }

        private fun buildWidget(
            context: Context,
            appWidgetId: Int,
            configured: Boolean,
            all: List<TaskItem>,
        ): RemoteViews {
            val filter = WidgetFilterStore.load(context, appWidgetId)
            val tasks = filter.apply(all).distinctBy { it.fileUri + " " + it.rawLine }

            val iconColor = dimIconColor(context)
            val root = RemoteViews(context.packageName, R.layout.widget_task_root)
            root.setTextViewText(R.id.widget_title, filter.title)
            root.setTextViewText(R.id.widget_count, tasks.size.toString())

            // 图标改为在本进程栅格化成位图再推送:RemoteViews 对 VectorDrawable 的 android:src
            // 支持不稳(HyperOS 等会报 "can't load widget"),位图必定可跨进程显示。
            setIcon(root, R.id.btn_settings, context, R.drawable.ic_settings, 22, iconColor)
            setIcon(root, R.id.btn_open, context, R.drawable.ic_open_app, 22, iconColor)
            setIcon(root, R.id.btn_add, context, R.drawable.ic_add, 22, iconColor)
            setIcon(root, R.id.btn_refresh, context, R.drawable.ic_refresh, 22, iconColor)

            // 头部按钮(每个用唯一 requestCode,避免 PendingIntent 复用串号)
            val base = appWidgetId * 16
            root.setOnClickPendingIntent(R.id.widget_title, act(context, base + 1, TaskListActivity::class.java))
            root.setOnClickPendingIntent(R.id.btn_open, act(context, base + 2, TaskListActivity::class.java))
            root.setOnClickPendingIntent(R.id.btn_add, act(context, base + 3, QuickAddActivity::class.java))
            root.setOnClickPendingIntent(
                R.id.btn_settings,
                pi(context, base + 4, Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .setData(Uri.parse("taskwidget://config/$appWidgetId")))
            )
            root.setOnClickPendingIntent(
                R.id.btn_refresh,
                pi(context, base + 5, WidgetActionActivity.refreshIntent(context))
            )

            root.removeAllViews(R.id.list_container)
            when {
                !configured -> showEmpty(root, "尚未选择 Vault,点标题去设置")
                tasks.isEmpty() -> showEmpty(root, "🎉 没有待办任务")
                else -> {
                    root.setViewVisibility(R.id.empty, View.GONE)
                    val shown = tasks.take(WIDGET_MAX_ITEMS)
                    for (t in shown) root.addView(R.id.list_container, buildRow(context, t, iconColor))
                    if (tasks.size > shown.size) {
                        val more = RemoteViews(context.packageName, R.layout.widget_task_more)
                        more.setTextViewText(R.id.more_text, "还有 ${tasks.size - shown.size} 条,点标题在 App 中查看")
                        root.addView(R.id.list_container, more)
                    }
                }
            }
            return root
        }

        private fun showEmpty(root: RemoteViews, msg: String) {
            root.setViewVisibility(R.id.empty, View.VISIBLE)
            root.setTextViewText(R.id.empty, msg)
        }

        private fun buildRow(context: Context, task: TaskItem, iconColor: Int): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_task_row)
            setIcon(row, R.id.row_check, context, R.drawable.ic_check_box_outline, 24, iconColor)
            row.setTextViewText(R.id.row_text, priorityPrefix(task.priorityOrder) + task.text)
            val due = task.due
            if (due != null) {
                row.setViewVisibility(R.id.row_due, View.VISIBLE)
                row.setTextViewText(R.id.row_due, dueLabel(due))
                // 过期用固定红色(在明暗两种桌面上都清晰),否则用主题次要色
                if (due.isBefore(LocalDate.now())) {
                    row.setTextColor(R.id.row_due, 0xFFE53935.toInt())
                }
            } else {
                row.setViewVisibility(R.id.row_due, View.GONE)
            }
            val rc = (task.fileUri + " " + task.rawLine).hashCode()
            row.setOnClickPendingIntent(
                R.id.row_check,
                pi(context, rc, WidgetActionActivity.completeIntent(context, task.fileUri, task.rawLine))
            )
            row.setOnClickPendingIntent(
                R.id.row_body,
                pi(context, rc xor 0x5f5f5f, Intent(context, EditTaskActivity::class.java)
                    .putExtra(EditTaskActivity.EXTRA_FILE_URI, task.fileUri)
                    .putExtra(EditTaskActivity.EXTRA_RAW_LINE, task.rawLine)
                    .setData(Uri.parse("taskwidget://edit/${task.fileUri.hashCode()}/${task.rawLine.hashCode()}")))
            )
            return row
        }

        /** 次要图标色,跟随明暗(与 @color/widget_text_dim 一致) */
        private fun dimIconColor(context: Context): Int {
            val night = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (night) 0xFFAAAAAA.toInt() else 0xFF666666.toInt()
        }

        /** 把矢量图标栅格化成位图并设置到 RemoteViews(避开 RemoteViews 对 VectorDrawable 的不稳定支持) */
        private fun setIcon(views: RemoteViews, viewId: Int, context: Context, resId: Int, sizeDp: Int, color: Int) {
            iconBitmap(context, resId, sizeDp, color)?.let { views.setImageViewBitmap(viewId, it) }
        }

        private fun iconBitmap(context: Context, resId: Int, sizeDp: Int, color: Int): Bitmap? = try {
            val d = ContextCompat.getDrawable(context, resId)?.mutate()
            if (d == null) null else {
                val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                DrawableCompat.setTint(d, color)
                d.setBounds(0, 0, px, px)
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                d.draw(Canvas(bmp))
                bmp
            }
        } catch (_: Exception) {
            null
        }

        private fun act(context: Context, req: Int, cls: Class<*>): PendingIntent =
            pi(context, req, Intent(context, cls))

        private fun pi(context: Context, req: Int, intent: Intent): PendingIntent {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, req, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
