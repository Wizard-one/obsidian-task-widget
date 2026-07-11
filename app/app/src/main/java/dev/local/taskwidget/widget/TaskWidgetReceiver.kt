package dev.local.taskwidget.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dev.local.taskwidget.work.RefreshWorker
import dev.local.taskwidget.work.ReminderScheduler

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TaskWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)
        ReminderScheduler.rescheduleAll(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetFilterStore.delete(context, appWidgetIds)
    }
}
