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

internal fun noteDisplayName(fileName: String): String =
    if (fileName.endsWith(".md", ignoreCase = true)) fileName.dropLast(3) else fileName

/** 笔记 widget 的可滚动文件名列表数据源。 */
class NoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return NoteListFactory(applicationContext, appWidgetId)
    }
}

private class NoteListFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<NoteItem> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val config = NoteWidgetConfigStore.load(context, appWidgetId)
        items = if (config == null) {
            emptyList()
        } else {
            val result = try {
                runBlocking { NoteRepository.listNotes(context, config.folderUri) }
            } catch (e: Exception) {
                Log.e("NoteWidget", "Failed to load notes for widget $appWidgetId", e)
                NoteListResult.Unavailable
            }
            (result as? NoteListResult.Success)?.items?.take(WIDGET_LIST_MAX) ?: emptyList()
        }
        Log.i("NoteWidget", "Loaded ${items.size} note rows for widget $appWidgetId")
    }

    override fun onDestroy() {
        items = emptyList()
    }

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
                    .setData(Uri.parse("taskwidget://notes/item/${note.uri.hashCode()}"))
            )
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
