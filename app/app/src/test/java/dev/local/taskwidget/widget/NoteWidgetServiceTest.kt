package dev.local.taskwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteWidgetServiceTest {

    @Test
    fun noteDisplayName_removesOneTerminalMarkdownExtension() {
        assertEquals("会议记录", noteDisplayName("会议记录.md"))
        assertEquals("README", noteDisplayName("README.MD"))
        assertEquals("ideas.md", noteDisplayName("ideas.md.md"))
    }

    @Test
    fun noteDisplayName_keepsNamesWithoutTerminalMarkdownExtension() {
        assertEquals("draft.md.bak", noteDisplayName("draft.md.bak"))
        assertEquals("无扩展名", noteDisplayName("无扩展名"))
    }
}
