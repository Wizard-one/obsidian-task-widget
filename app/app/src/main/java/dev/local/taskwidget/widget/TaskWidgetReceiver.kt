package dev.local.taskwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import dev.local.taskwidget.QuickAddActivity
import dev.local.taskwidget.QuickAddNoteActivity
import dev.local.taskwidget.R
import dev.local.taskwidget.TaskListActivity
import dev.local.taskwidget.WidgetActionActivity
import dev.local.taskwidget.data.NoteListResult
import dev.local.taskwidget.data.NoteRepository
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.work.RefreshWorker
import dev.local.taskwidget.work.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 任务与笔记双页 widget，继续使用 HyperOS 可靠的传统 RemoteViews 集合。 */
class TaskWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch { try { renderIds(context, manager, appWidgetIds) } finally { pending.finish() } }
    }
    override fun onEnabled(context: Context) { RefreshWorker.schedule(context); ReminderScheduler.rescheduleAll(context) }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetFilterStore.delete(context, appWidgetIds)
        TaskWidgetNoteConfigStore.delete(context, appWidgetIds)
        TaskWidgetDisplayStore.delete(context, appWidgetIds)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        suspend fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            renderIds(context, manager, manager.getAppWidgetIds(ComponentName(context, TaskWidgetReceiver::class.java)))
        }
        suspend fun render(context: Context, ids: IntArray) = renderIds(context, AppWidgetManager.getInstance(context), ids)
        suspend fun toggleContent(context: Context, id: Int) {
            TaskWidgetDisplayStore.toggle(context, id)
            render(context, intArrayOf(id))
        }
        suspend fun renderNotesFolder(context: Context, folderUri: String) {
            val manager = AppWidgetManager.getInstance(context)
            val live = manager.getAppWidgetIds(ComponentName(context, TaskWidgetReceiver::class.java)).toSet()
            renderIds(context, manager, TaskWidgetNoteConfigStore.idsForFolder(context, folderUri).filter { it in live }.toIntArray())
        }

        private suspend fun renderIds(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val configured = VaultRepository.getVaultUri(context) != null
            val all = if (configured) runCatching { VaultRepository.loadTasks(context) }.getOrDefault(emptyList()) else emptyList()
            ids.forEach { id -> runCatching {
                manager.updateAppWidget(id, buildWidget(context, id, configured, all))
                manager.notifyAppWidgetViewDataChanged(id, R.id.list)
                manager.notifyAppWidgetViewDataChanged(id, R.id.task_note_list)
            }.onFailure { Log.e("TaskWidget", "Failed to render widget $id", it) } }
        }

        private suspend fun buildWidget(context: Context, id: Int, vaultConfigured: Boolean, all: List<TaskItem>): RemoteViews {
            val filter = WidgetFilterStore.load(context, id)
            val noteConfig = TaskWidgetNoteConfigStore.load(context, id)
            val mode = TaskWidgetDisplayStore.load(context, id)
            val taskCount = if (vaultConfigured) filter.apply(all).distinctBy { it.fileUri + it.rawLine }.size else 0
            val notes = noteConfig?.let { NoteRepository.listNotes(context, it.folderUri) }
            val noteCount = (notes as? NoteListResult.Success)?.items?.size ?: 0
            val root = RemoteViews(context.packageName, R.layout.widget_task_root)
            val notesMode = mode == TaskWidgetContent.NOTES
            root.setDisplayedChild(R.id.task_content_flipper, if (notesMode) 1 else 0)
            root.setTextViewText(R.id.widget_title, if (notesMode) noteConfig?.folderName ?: "笔记" else filter.title)
            root.setTextViewText(R.id.widget_count, (if (notesMode) noteCount else taskCount).toString())
            val color = dimIconColor(context)
            setIcon(root, R.id.btn_switch, context, R.drawable.ic_switch_content, 22, color)
            setIcon(root, R.id.btn_add, context, R.drawable.ic_add, 22, color)
            setIcon(root, R.id.btn_refresh, context, R.drawable.ic_refresh, 22, color)
            root.setTextViewText(R.id.empty, when { !vaultConfigured -> "尚未选择 Vault,点标题去设置"; taskCount == 0 -> "🎉 没有待办任务"; else -> "" })
            root.setTextViewText(R.id.task_note_empty, when { noteConfig == null -> "尚未选择笔记文件夹,请重新配置"; notes is NoteListResult.Unavailable -> "无法访问笔记文件夹,请重新配置"; noteCount == 0 -> "这里还没有 Markdown 笔记"; else -> "" })
            root.setEmptyView(R.id.list, R.id.empty); root.setEmptyView(R.id.task_note_list, R.id.task_note_empty)
            val base = id * 32
            root.setOnClickPendingIntent(R.id.widget_title, pi(context, base + 1, Intent(context, TaskListActivity::class.java)))
            root.setOnClickPendingIntent(R.id.btn_switch, pi(context, base + 2, WidgetActionActivity.toggleIntent(context, id)))
            root.setOnClickPendingIntent(R.id.btn_add, pi(context, base + 3, if (notesMode) QuickAddNoteActivity.taskWidgetIntent(context, id) else Intent(context, QuickAddActivity::class.java)))
            root.setOnClickPendingIntent(R.id.btn_refresh, pi(context, base + 4, WidgetActionActivity.refreshIntent(context, id, notesMode)))
            root.setRemoteAdapter(R.id.list, Intent(context, TaskWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse(WidgetAdapterSession.uri("tasks", id))))
            root.setRemoteAdapter(R.id.task_note_list, Intent(context, TaskWidgetNoteService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse(WidgetAdapterSession.uri("task-notes", id))))
            root.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(context, base + 8, Intent(context, WidgetActionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            root.setPendingIntentTemplate(R.id.task_note_list, PendingIntent.getActivity(context, base + 9, Intent(context, dev.local.taskwidget.NoteWidgetActionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            return root
        }
        private fun pi(context: Context, req: Int, intent: Intent) = PendingIntent.getActivity(context, req, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
