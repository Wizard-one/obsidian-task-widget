package dev.local.taskwidget.data

import java.time.LocalDate

/** Index each task on its start and deadline, counting coincident dates only once. */
fun groupTasksByCalendarDate(tasks: List<TaskItem>): Map<LocalDate, List<TaskItem>> {
    val byDate = mutableMapOf<LocalDate, MutableList<TaskItem>>()
    tasks.forEach { task ->
        setOfNotNull(task.start, task.actualDue).forEach { date ->
            byDate.getOrPut(date) { mutableListOf() }.add(task)
        }
    }
    return byDate
}
