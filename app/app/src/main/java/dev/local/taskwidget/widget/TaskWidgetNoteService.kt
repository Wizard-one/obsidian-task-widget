package dev.local.taskwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.local.taskwidget.NoteWidgetActionActivity
import dev.local.taskwidget.R
import dev.local.taskwidget.data.NoteItem
import dev.local.taskwidget.data.NoteListResult
import dev.local.taskwidget.data.NoteRepository
import kotlinx.coroutines.runBlocking

/** 任务清单 widget 内笔记页的独立配置数据源。 */
class TaskWidgetNoteService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TaskWidgetNoteFactory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
    )
}

private class TaskWidgetNoteFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<NoteItem> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val config = TaskWidgetNoteConfigStore.load(context, appWidgetId)
        items = if (config == null) emptyList() else {
            val result = runCatching { runBlocking { NoteRepository.listNotes(context, config.folderUri) } }
                .onFailure { Log.e("TaskWidget", "Failed to load notes for widget $appWidgetId", it) }
                .getOrDefault(NoteListResult.Unavailable)
            (result as? NoteListResult.Success)?.items?.take(WIDGET_LIST_MAX) ?: emptyList()
        }
        Log.i("TaskWidget", "Loaded ${items.size} note rows for widget $appWidgetId")
    }

    override fun onDestroy() { items = emptyList() }
    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val note = items.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_note_row)
        return RemoteViews(context.packageName, R.layout.widget_note_row).apply {
            setTextViewText(R.id.note_row_text, noteDisplayName(note.name))
            setOnClickFillInIntent(
                R.id.note_row_body,
                Intent()
                    .putExtra(NoteWidgetActionActivity.EXTRA_ACTION, NoteWidgetActionActivity.ACTION_OPEN)
                    .putExtra(NoteWidgetActionActivity.EXTRA_NOTE_URI, note.uri)
                    .setData(Uri.parse("taskwidget://task-notes/item/${note.uri.hashCode()}"))
            )
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
