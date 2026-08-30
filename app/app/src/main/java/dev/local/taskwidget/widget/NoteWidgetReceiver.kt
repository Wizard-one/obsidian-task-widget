package dev.local.taskwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.local.taskwidget.NoteWidgetActionActivity
import dev.local.taskwidget.QuickAddNoteActivity
import dev.local.taskwidget.R
import dev.local.taskwidget.data.NoteListResult
import dev.local.taskwidget.data.NoteRepository
import dev.local.taskwidget.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 文件夹笔记 widget —— 与任务清单相同的传统 RemoteViews 可滚动集合实现。 */
class NoteWidgetReceiver : AppWidgetProvider() {

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
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        NoteWidgetConfigStore.delete(context, appWidgetIds)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        suspend fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NoteWidgetReceiver::class.java))
            renderIds(context, manager, ids)
        }

        suspend fun render(context: Context, appWidgetIds: IntArray) {
            renderIds(context, AppWidgetManager.getInstance(context), appWidgetIds)
        }

        suspend fun renderFolder(context: Context, folderUri: String) {
            val manager = AppWidgetManager.getInstance(context)
            val live = manager.getAppWidgetIds(ComponentName(context, NoteWidgetReceiver::class.java)).toSet()
            val ids = NoteWidgetConfigStore.idsForFolder(context, folderUri)
                .filter { it in live }
                .toIntArray()
            renderIds(context, manager, ids)
        }

        private suspend fun renderIds(context: Context, manager: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                try {
                    manager.updateAppWidget(id, buildWidget(context, id))
                    manager.notifyAppWidgetViewDataChanged(id, R.id.note_list)
                } catch (_: Exception) {
                    // 单个实例或 SAF 授权失败不影响其它 widget
                }
            }
        }

        private suspend fun buildWidget(context: Context, appWidgetId: Int): RemoteViews {
            val config = NoteWidgetConfigStore.load(context, appWidgetId)
            val listing = config?.let { NoteRepository.listNotes(context, it.folderUri) }
            val count = (listing as? NoteListResult.Success)?.items?.size
            val iconColor = dimIconColor(context)
            val root = RemoteViews(context.packageName, R.layout.widget_note_root)

            root.setTextViewText(R.id.note_widget_title, config?.folderName?.ifBlank { "笔记" } ?: "笔记")
            root.setTextViewText(R.id.note_widget_count, count?.toString() ?: "")
            root.setTextViewText(
                R.id.note_empty,
                when {
                    config == null -> "尚未选择文件夹,请重新配置"
                    listing is NoteListResult.Unavailable -> "无法访问文件夹,请重新配置"
                    listing is NoteListResult.Success && listing.items.isEmpty() -> "这里还没有 Markdown 笔记"
                    else -> ""
                }
            )
            root.setEmptyView(R.id.note_list, R.id.note_empty)

            setIcon(root, R.id.note_btn_refresh, context, R.drawable.ic_refresh, 22, iconColor)
            setIcon(root, R.id.note_btn_add, context, R.drawable.ic_add, 22, iconColor)

            val service = Intent(context, NoteWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("taskwidget://notes/list/$appWidgetId"))
            root.setRemoteAdapter(R.id.note_list, service)

            val template = PendingIntent.getActivity(
                context,
                appWidgetId,
                Intent(context, NoteWidgetActionActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .setData(Uri.parse("taskwidget://notes/open/$appWidgetId"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            root.setPendingIntentTemplate(R.id.note_list, template)

            root.setOnClickPendingIntent(
                R.id.note_btn_refresh,
                activityPendingIntent(
                    context,
                    appWidgetId * 2 + 1,
                    NoteWidgetActionActivity.refreshIntent(context, appWidgetId),
                )
            )
            if (config != null) {
                root.setOnClickPendingIntent(
                    R.id.note_btn_add,
                    activityPendingIntent(
                        context,
                        appWidgetId * 2 + 2,
                        QuickAddNoteActivity.intent(context, appWidgetId),
                    )
                )
            }
            return root
        }

        private fun activityPendingIntent(
            context: Context,
            requestCode: Int,
            intent: Intent,
        ): PendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
