package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskCalendarTest {
    private val start = "2026-09-23"
    private val end = "2026-09-25"

    private fun task(start: String? = null, due: String? = null, scheduled: String? = null) = TaskItem(
        fileUri = "content://tasks", fileName = "Tasks.md", path = "Tasks.md",
        rawLine = "- [ ] Task", text = "Task", dueDate = due ?: scheduled ?: start,
        priorityOrder = Priority.NONE.order, tags = emptyList(),
        actualDueDate = due, startDate = start, scheduledDate = scheduled,
    )

    @Test fun startOnlyTaskAppearsOnStartDay() {
        val task = task(start = start)
        assertEquals(mapOf(LocalDate.parse(start) to listOf(task)), groupTasksByCalendarDate(listOf(task)))
    }

    @Test fun deadlineOnlyTaskAppearsOnDeadline() {
        val task = task(due = end)
        assertEquals(mapOf(LocalDate.parse(end) to listOf(task)), groupTasksByCalendarDate(listOf(task)))
    }

    @Test fun differentDatesAppearOnBothEndpointsButNotBetween() {
        val task = task(start = start, due = end)
        val dates = groupTasksByCalendarDate(listOf(task))
        assertEquals(listOf(task), dates[LocalDate.parse(start)])
        assertEquals(listOf(task), dates[LocalDate.parse(end)])
        assertFalse(dates.containsKey(LocalDate.parse(start).plusDays(1)))
        assertEquals(2, dates.size)
    }

    @Test fun coincidentDatesDoNotDuplicateTask() {
        val task = task(start = start, due = start)
        assertEquals(mapOf(LocalDate.parse(start) to listOf(task)), groupTasksByCalendarDate(listOf(task)))
    }

    @Test fun undatedAndScheduledOnlyTasksAreNotDeadlines() {
        assertTrue(groupTasksByCalendarDate(listOf(task(), task(scheduled = start))).isEmpty())
    }
}
