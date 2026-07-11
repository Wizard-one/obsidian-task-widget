package dev.local.taskwidget.widget

import android.content.Context
import dev.local.taskwidget.data.WidgetFilter

/** 按 appWidgetId 持久化每个 widget 实例的筛选配置 */
object WidgetFilterStore {

    private const val PREFS = "widget_filters"

    fun load(context: Context, appWidgetId: Int): WidgetFilter =
        WidgetFilter.fromJson(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(appWidgetId.toString(), null)
        )

    fun save(context: Context, appWidgetId: Int, filter: WidgetFilter) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(appWidgetId.toString(), filter.toJson()).apply()
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) editor.remove(id.toString())
        editor.apply()
    }
}
