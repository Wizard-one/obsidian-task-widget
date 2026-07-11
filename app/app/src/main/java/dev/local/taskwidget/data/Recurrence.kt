package dev.local.taskwidget.data

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Obsidian Tasks 的循环规则(🔁 / [repeat:: ...])。
 * 支持常见的 "every ..." 自然语法(不是完整 RRULE):
 *   every day / every 3 days
 *   every week / every 2 weeks
 *   every month / every 2 months(按月推进,自动 clamp 月末)
 *   every year
 *   every weekday(周一到周五,跳过周末)
 *   every monday / tuesday ...(每周某天)
 *   末尾可带 "when done":下一次从完成日算起,而不是从原到期日
 */
data class Recurrence(
    val raw: String,
    val interval: Int,
    val unit: DateUnit,
    val whenDone: Boolean,
    val weekday: DayOfWeek?,
    val weekdaysOnly: Boolean,
) {

    enum class DateUnit { DAY, WEEK, MONTH, YEAR }

    /** 把规则应用到某个日期,得到严格在其之后的下一个日期 */
    fun applyTo(date: LocalDate): LocalDate = when {
        weekdaysOnly -> nextWeekday(date)
        weekday != null -> nextOfWeekday(date, weekday)
        unit == DateUnit.DAY -> date.plusDays(interval.toLong())
        unit == DateUnit.WEEK -> date.plusWeeks(interval.toLong())
        unit == DateUnit.MONTH -> date.plusMonths(interval.toLong())
        else -> date.plusYears(interval.toLong())
    }

    /** 是否用"按天平移"的方式推进(day/week/weekday),否则按月/年整体推进 */
    private val shiftByDays: Boolean
        get() = weekdaysOnly || weekday != null || unit == DateUnit.DAY || unit == DateUnit.WEEK

    /**
     * 给定一个任务的三个参考日期(due/scheduled/start)和完成日期,
     * 计算下一次循环实例的对应日期。返回 null 表示无法循环(没有任何参考日期)。
     */
    fun nextDates(
        due: LocalDate?,
        scheduled: LocalDate?,
        start: LocalDate?,
        doneDate: LocalDate,
    ): NextDates? {
        val ref = due ?: scheduled ?: start ?: return null
        val anchor = if (whenDone) doneDate else ref
        val newRef = applyTo(anchor)

        return if (shiftByDays) {
            val delta = newRef.toEpochDay() - ref.toEpochDay()
            NextDates(
                due = due?.plusDays(delta),
                scheduled = scheduled?.plusDays(delta),
                start = start?.plusDays(delta),
            )
        } else {
            // 按月/年:每个字段各自整体推进(保持各自的"几号")
            NextDates(
                due = due?.let { applyTo(it) },
                scheduled = scheduled?.let { applyTo(it) },
                start = start?.let { applyTo(it) },
            )
        }
    }

    data class NextDates(val due: LocalDate?, val scheduled: LocalDate?, val start: LocalDate?)

    companion object {
        private val WHEN_DONE = Regex("""\bwhen\s+done\b""", RegexOption.IGNORE_CASE)
        private val EVERY_N = Regex("""every\s+(\d+)\s+(day|week|month|year)s?""", RegexOption.IGNORE_CASE)
        private val EVERY_UNIT = Regex("""every\s+(day|week|month|year)\b""", RegexOption.IGNORE_CASE)
        private val EVERY_WEEKDAY = Regex("""every\s+weekday""", RegexOption.IGNORE_CASE)
        private val EVERY_DOW = Regex(
            """every\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)""",
            RegexOption.IGNORE_CASE
        )

        fun parse(text: String?): Recurrence? {
            if (text.isNullOrBlank()) return null
            val whenDone = WHEN_DONE.containsMatchIn(text)

            EVERY_WEEKDAY.find(text)?.let {
                return Recurrence(text.trim(), 1, DateUnit.DAY, whenDone, null, weekdaysOnly = true)
            }
            EVERY_DOW.find(text)?.let { m ->
                return Recurrence(
                    text.trim(), 1, DateUnit.WEEK, whenDone,
                    weekday = dayOfWeek(m.groupValues[1]), weekdaysOnly = false
                )
            }
            EVERY_N.find(text)?.let { m ->
                return Recurrence(
                    text.trim(), m.groupValues[1].toInt(), unit(m.groupValues[2]),
                    whenDone, null, false
                )
            }
            EVERY_UNIT.find(text)?.let { m ->
                return Recurrence(text.trim(), 1, unit(m.groupValues[1]), whenDone, null, false)
            }
            return null
        }

        private fun unit(s: String): DateUnit = when (s.lowercase()) {
            "day" -> DateUnit.DAY
            "week" -> DateUnit.WEEK
            "month" -> DateUnit.MONTH
            else -> DateUnit.YEAR
        }

        private fun dayOfWeek(s: String): DayOfWeek = when (s.lowercase()) {
            "monday" -> DayOfWeek.MONDAY
            "tuesday" -> DayOfWeek.TUESDAY
            "wednesday" -> DayOfWeek.WEDNESDAY
            "thursday" -> DayOfWeek.THURSDAY
            "friday" -> DayOfWeek.FRIDAY
            "saturday" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }

        private fun nextWeekday(date: LocalDate): LocalDate {
            var d = date.plusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) {
                d = d.plusDays(1)
            }
            return d
        }

        private fun nextOfWeekday(date: LocalDate, target: DayOfWeek): LocalDate {
            var d = date.plusDays(1)
            while (d.dayOfWeek != target) d = d.plusDays(1)
            return d
        }
    }
}
