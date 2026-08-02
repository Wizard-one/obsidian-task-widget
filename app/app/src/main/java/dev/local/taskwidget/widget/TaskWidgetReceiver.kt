package dev.local.taskwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.local.taskwidget.QuickAddActivity
import dev.local.taskwidget.R
import dev.local.taskwidget.TaskListActivity
import dev.local.taskwidget.WidgetActionActivity
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.work.RefreshWorker
import dev.local.taskwidget.work.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 任务清单 widget —— 传统 RemoteViews **集合(ListView)** 实现,不用 Jetpack Glance。
 *
 * 为什么这么做:
 * 1. Glance widget 在 HyperOS/MIUI 上不会按需重绘(点完成/刷新内容不变)。传统 RemoteViews 由
 *    AppWidgetManager 直接推送,是系统级刷新,必定生效。
 * 2. 用 ListView + RemoteViewsService 集合适配器,列表**可滚动**;刷新走 notifyAppWidgetViewDataChanged。
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
        // 进程级作用域:onUpdate 里 goAsync + 后台加载任务计数,渲染完再 finish
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
            // 只为头部计数读一次(已缓存,快);列表项由 RemoteViewsService 各自加载
            val all: List<TaskItem> = if (configured) {
                try { VaultRepository.loadTasks(context) } catch (_: Exception) { emptyList() }
            } else emptyList()
            for (id in ids) {
                try {
                    mgr.updateAppWidget(id, buildWidget(context, id, configured, all))
                    // 通知集合适配器重新取数据(真正刷新列表内容)
                    mgr.notifyAppWidgetViewDataChanged(id, R.id.list)
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
            val count = if (configured)
                filter.apply(all).distinctBy { it.fileUri + " " + it.rawLine }.size else 0
            val iconColor = dimIconColor(context)

            val root = RemoteViews(context.packageName, R.layout.widget_task_root)
            root.setTextViewText(R.id.widget_title, filter.title)
            root.setTextViewText(R.id.widget_count, if (configured) count.toString() else "")

            // 头部图标(本进程栅格化成位图,避开 VectorDrawable 在 RemoteViews 的不稳定支持)
            setIcon(root, R.id.btn_open, context, R.drawable.ic_open_app, 22, iconColor)
            setIcon(root, R.id.btn_add, context, R.drawable.ic_add, 22, iconColor)
            setIcon(root, R.id.btn_refresh, context, R.drawable.ic_refresh, 22, iconColor)

            val base = appWidgetId * 16
            root.setOnClickPendingIntent(R.id.widget_title, act(context, base + 1, TaskListActivity::class.java))
            root.setOnClickPendingIntent(R.id.btn_open, act(context, base + 2, TaskListActivity::class.java))
            root.setOnClickPendingIntent(R.id.btn_add, act(context, base + 3, QuickAddActivity::class.java))
            root.setOnClickPendingIntent(R.id.btn_refresh, pi(context, base + 5, WidgetActionActivity.refreshIntent(context)))

            // 空状态文案
            root.setTextViewText(
                R.id.empty,
                when {
                    !configured -> "尚未选择 Vault,点标题去设置"
                    count == 0 -> "🎉 没有待办任务"
                    else -> ""
                }
            )
            root.setEmptyView(R.id.list, R.id.empty)

            // 绑定可滚动列表的数据源(data 唯一,保证每个 widget 实例各用一份 factory)
            val svc = Intent(context, TaskWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("taskwidget://list/$appWidgetId"))
            root.setRemoteAdapter(R.id.list, svc)

            // 列表项点击模板:各行提供 fill-in intent 合并进来(须 MUTABLE)
            val template = PendingIntent.getActivity(
                context, base + 9,
                Intent(context, WidgetActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            root.setPendingIntentTemplate(R.id.list, template)

            return root
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
