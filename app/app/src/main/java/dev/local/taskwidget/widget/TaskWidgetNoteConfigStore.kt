package dev.local.taskwidget.widget

import android.content.Context

/** TaskWidgetReceiver 实例专属的笔记文件夹/模板配置。 */
object TaskWidgetNoteConfigStore {
    private const val PREFS = "task_widget_note_configs"

    fun load(context: Context, appWidgetId: Int): NoteWidgetConfig? =
        NoteWidgetConfig.fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(appWidgetId.toString(), null))

    fun save(context: Context, appWidgetId: Int, config: NoteWidgetConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(appWidgetId.toString(), config.toJson()).apply()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { editor.remove(it.toString()) }
        editor.apply()
    }

    fun idsForFolder(context: Context, folderUri: String): IntArray =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, raw) ->
            val config = NoteWidgetConfig.fromJson(raw as? String) ?: return@mapNotNull null
            key.toIntOrNull()?.takeIf { config.folderUri == folderUri }
        }.toIntArray()
}
