package dev.local.taskwidget.widget

import android.content.Context
import org.json.JSONObject

/** 每个笔记 widget 实例独立保存的目标文件夹与默认模板。 */
data class NoteWidgetConfig(
    val folderUri: String,
    val folderName: String,
    val templateUri: String? = null,
    val templateName: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("folderUri", folderUri)
        .put("folderName", folderName)
        .put("templateUri", templateUri ?: JSONObject.NULL)
        .put("templateName", templateName ?: JSONObject.NULL)
        .toString()

    companion object {
        fun fromJson(raw: String?): NoteWidgetConfig? {
            if (raw == null) return null
            return try {
                val obj = JSONObject(raw)
                val folderUri = obj.optString("folderUri")
                if (folderUri.isBlank()) return null
                NoteWidgetConfig(
                    folderUri = folderUri,
                    folderName = obj.optString("folderName", "笔记"),
                    templateUri = if (obj.isNull("templateUri")) null else obj.optString("templateUri"),
                    templateName = if (obj.isNull("templateName")) null else obj.optString("templateName"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** 按 appWidgetId 持久化笔记 widget 配置。 */
object NoteWidgetConfigStore {

    private const val PREFS = "note_widget_configs"

    fun load(context: Context, appWidgetId: Int): NoteWidgetConfig? =
        NoteWidgetConfig.fromJson(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(appWidgetId.toString(), null)
        )

    fun save(context: Context, appWidgetId: Int, config: NoteWidgetConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(appWidgetId.toString(), config.toJson()).apply()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) editor.remove(id.toString())
        editor.apply()
    }

    fun idsForFolder(context: Context, folderUri: String): IntArray =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) ->
                val id = key.toIntOrNull() ?: return@mapNotNull null
                val config = NoteWidgetConfig.fromJson(value as? String) ?: return@mapNotNull null
                id.takeIf { config.folderUri == folderUri }
            }
            .toIntArray()
}
