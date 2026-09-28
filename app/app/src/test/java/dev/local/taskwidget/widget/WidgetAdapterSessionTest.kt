package dev.local.taskwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAdapterSessionTest {
    @Test
    fun refreshKeepsAdapterIdentityWithinProcess() {
        assertEquals(WidgetAdapterSession.uri("tasks", 1279), WidgetAdapterSession.uri("tasks", 1279))
    }

    @Test
    fun collectionsAndInstancesNeverShareAdapterIdentity() {
        val uris = listOf("tasks", "task-notes", "notes/list").flatMap { collection ->
            listOf(1, 2).map { WidgetAdapterSession.uri(collection, it) }
        }
        assertEquals(6, uris.toSet().size)
    }

    @Test
    fun sessionReplacesLegacyDeadBindingIdentity() {
        val uri = WidgetAdapterSession.uri("tasks", 1279)
        assertNotEquals("taskwidget://tasks/1279", uri)
        assertTrue(uri.substringAfter("?session=", "").isNotBlank())
    }
}
