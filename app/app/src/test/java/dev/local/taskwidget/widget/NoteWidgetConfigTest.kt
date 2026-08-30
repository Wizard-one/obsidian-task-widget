package dev.local.taskwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteWidgetConfigTest {

    @Test
    fun jsonRoundTrip_preservesOptionalTemplate() {
        val config = NoteWidgetConfig(
            folderUri = "content://notes/tree/root",
            folderName = "工作",
            templateUri = "content://notes/document/template",
            templateName = "周报模板.md",
        )
        assertEquals(config, NoteWidgetConfig.fromJson(config.toJson()))
    }

    @Test
    fun jsonRoundTrip_preservesNoTemplate() {
        val config = NoteWidgetConfig("content://notes/tree/root", "笔记")
        assertEquals(config, NoteWidgetConfig.fromJson(config.toJson()))
    }

    @Test
    fun invalidJsonOrMissingFolder_returnsNull() {
        assertNull(NoteWidgetConfig.fromJson(null))
        assertNull(NoteWidgetConfig.fromJson("not json"))
        assertNull(NoteWidgetConfig.fromJson("{\"folderUri\":\"\"}"))
    }
}
