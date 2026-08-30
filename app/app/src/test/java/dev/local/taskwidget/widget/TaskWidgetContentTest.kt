package dev.local.taskwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskWidgetContentTest {

    @Test
    fun fromStored_defaultsToTasksForNullOrUnknown() {
        assertEquals(TaskWidgetContent.TASKS, TaskWidgetContent.fromStored(null))
        assertEquals(TaskWidgetContent.TASKS, TaskWidgetContent.fromStored(""))
        assertEquals(TaskWidgetContent.TASKS, TaskWidgetContent.fromStored("bogus"))
    }

    @Test
    fun fromStored_parsesStoredNames() {
        assertEquals(TaskWidgetContent.TASKS, TaskWidgetContent.fromStored("TASKS"))
        assertEquals(TaskWidgetContent.NOTES, TaskWidgetContent.fromStored("NOTES"))
    }

    @Test
    fun toggled_flipsBothWays() {
        assertEquals(TaskWidgetContent.NOTES, TaskWidgetContent.TASKS.toggled())
        assertEquals(TaskWidgetContent.TASKS, TaskWidgetContent.NOTES.toggled())
    }
}
