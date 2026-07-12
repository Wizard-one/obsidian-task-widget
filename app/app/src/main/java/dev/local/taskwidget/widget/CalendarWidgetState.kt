package dev.local.taskwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import java.time.LocalDate

/** 每个日历 widget 实例的月份偏移与选中日期(按 appWidgetId 存 SharedPreferences) */
object CalendarWidgetState {

    private const val PREFS = "calendar_widgets"

    fun monthOffset(context: Context, id: Int): Int =
        prefs(context).getInt("off_$id", 0)

    fun setMonthOffset(context: Context, id: Int, offset: Int) {
        prefs(context).edit().putInt("off_$id", offset).apply()
    }

    fun selectedDay(context: Context, id: Int): LocalDate {
        val epoch = prefs(context).getLong("sel_$id", -1L)
        return if (epoch >= 0) LocalDate.ofEpochDay(epoch) else LocalDate.now()
    }

    fun setSelectedDay(context: Context, id: Int, date: LocalDate) {
        prefs(context).edit().putLong("sel_$id", date.toEpochDay()).apply()
    }

    fun delete(context: Context, ids: IntArray) {
        val e = prefs(context).edit()
        for (id in ids) e.remove("off_$id").remove("sel_$id")
        e.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun refreshAll(context: Context) {
        MonthMiniWidget().updateAll(context)
        MonthAgendaWidget().updateAll(context)
    }
}

private suspend fun widgetId(context: Context, glanceId: GlanceId): Int =
    try {
        GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    } catch (_: Exception) {
        AppWidgetManager.INVALID_APPWIDGET_ID
    }

class PrevMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = widgetId(context, glanceId)
        CalendarWidgetState.setMonthOffset(context, id, CalendarWidgetState.monthOffset(context, id) - 1)
        CalendarWidgetState.refreshAll(context)
    }
}

class NextMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = widgetId(context, glanceId)
        CalendarWidgetState.setMonthOffset(context, id, CalendarWidgetState.monthOffset(context, id) + 1)
        CalendarWidgetState.refreshAll(context)
    }
}

class TodayMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = widgetId(context, glanceId)
        CalendarWidgetState.setMonthOffset(context, id, 0)
        CalendarWidgetState.setSelectedDay(context, id, LocalDate.now())
        CalendarWidgetState.refreshAll(context)
    }
}

class SelectDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = widgetId(context, glanceId)
        val epoch = parameters[KEY_EPOCH_DAY] ?: return
        CalendarWidgetState.setSelectedDay(context, id, LocalDate.ofEpochDay(epoch))
        CalendarWidgetState.refreshAll(context)
    }

    companion object {
        val KEY_EPOCH_DAY = ActionParameters.Key<Long>("epochDay")
        fun params(date: LocalDate) = actionParametersOf(KEY_EPOCH_DAY to date.toEpochDay())
    }
}
