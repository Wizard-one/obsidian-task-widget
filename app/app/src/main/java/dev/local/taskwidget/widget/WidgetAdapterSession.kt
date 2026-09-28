package dev.local.taskwidget.widget

import java.util.UUID

/**
 * HyperOS can keep a dead RemoteViewsService binding after reclaiming our process.
 * A data URI from the new process forces the host to create a fresh adapter; intent
 * extras alone do not participate in Intent.filterEquals(). Keep it stable within
 * the process so ordinary refreshes preserve the list position and adapter cache.
 */
internal object WidgetAdapterSession {
    private val session = UUID.randomUUID().toString()

    fun uri(collection: String, appWidgetId: Int): String =
        "taskwidget://$collection/$appWidgetId?session=$session"
}
