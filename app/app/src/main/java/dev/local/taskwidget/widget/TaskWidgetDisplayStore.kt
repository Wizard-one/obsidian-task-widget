package dev.local.taskwidget.widget

import android.content.Context

enum class TaskWidgetContent { TASKS, NOTES;
    fun toggled(): TaskWidgetContent = if (this == TASKS) NOTES else TASKS

    companion object {
        fun fromStored(raw: String?): TaskWidgetContent = entries.firstOrNull { it.name == raw } ?: TASKS
    }
}

object TaskWidgetDisplayStore {
    private const val PREFS = "task_widget_display"

    fun load(context: Context, appWidgetId: Int): TaskWidgetContent =
        TaskWidgetContent.fromStored(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(appWidgetId.toString(), null))

    fun toggle(context: Context, appWidgetId: Int): TaskWidgetContent {
        val next = load(context, appWidgetId).toggled()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(appWidgetId.toString(), next.name).apply()
        return next
    }

    fun delete(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { editor.remove(it.toString()) }
        editor.apply()
    }
}
