package dev.local.taskwidget.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 轻量自然语言日期解析(对标 TaskForge 的快速添加输入)。
 * 支持:today/tomorrow/yesterday、下周几、"in N days/weeks"、周几名、
 * next week/next month、以及 ISO `2026-07-15`、`07-15`、`7/15`。
 * 中文同义词:今天/明天/后天/下周。
 *
 * [extractDue] 从一段快速添加文本里识别并**剥离**日期短语,返回(纯文本, 日期)。
 */
object NaturalDate {

    private val ISO = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
    private val MD = Regex("""\b(\d{1,2})[-/](\d{1,2})\b""")
    private val IN_N = Regex("""\bin\s+(\d+)\s+(day|week|month)s?\b""", RegexOption.IGNORE_CASE)
    private val NEXT_UNIT = Regex("""\bnext\s+(week|month|year)\b""", RegexOption.IGNORE_CASE)
    private val DOW = Regex(
        """\b(?:next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ISO_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** 只解析一个短语为日期(不剥离),识别不了返回 null */
    fun parse(text: String, today: LocalDate = LocalDate.now()): LocalDate? =
        extractDue(text, today).second

    /**
     * 从 [text] 中提取截止日期短语并剥离,返回(剩余文本, 日期或 null)。
     * 只提取第一个命中的日期短语。
     */
    fun extractDue(text: String, today: LocalDate = LocalDate.now()): Pair<String, LocalDate?> {
        // 关键词(含中文),按从长到短匹配避免子串误伤
        val keywords = listOf(
            "day after tomorrow" to today.plusDays(2),
            "后天" to today.plusDays(2),
            "tomorrow" to today.plusDays(1),
            "明天" to today.plusDays(1),
            "yesterday" to today.minusDays(1),
            "昨天" to today.minusDays(1),
            "today" to today,
            "今天" to today,
            "下周" to today.plusWeeks(1),
        )
        for ((kw, date) in keywords) {
            val idx = text.indexOf(kw, ignoreCase = true)
            if (idx >= 0) return strip(text, idx, kw.length) to date
        }

        ISO.find(text)?.let { m ->
            parseIso(m.groupValues[1])?.let { return strip(text, m.range) to it }
        }
        IN_N.find(text)?.let { m ->
            val n = m.groupValues[1].toLong()
            val d = when (m.groupValues[2].lowercase()) {
                "day" -> today.plusDays(n)
                "week" -> today.plusWeeks(n)
                else -> today.plusMonths(n)
            }
            return strip(text, m.range) to d
        }
        NEXT_UNIT.find(text)?.let { m ->
            val d = when (m.groupValues[1].lowercase()) {
                "week" -> today.plusWeeks(1)
                "month" -> today.plusMonths(1)
                else -> today.plusYears(1)
            }
            return strip(text, m.range) to d
        }
        DOW.find(text)?.let { m ->
            val target = dayOfWeek(m.groupValues[1])
            var d = today.plusDays(1)
            while (d.dayOfWeek != target) d = d.plusDays(1)
            return strip(text, m.range) to d
        }
        MD.find(text)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            if (month in 1..12 && day in 1..31) {
                val d = try {
                    LocalDate.of(today.year, month, day)
                } catch (_: Exception) {
                    null
                }
                if (d != null) return strip(text, m.range) to d
            }
        }
        return text to null
    }

    private fun strip(text: String, start: Int, len: Int): String =
        (text.substring(0, start) + text.substring(start + len)).replace(Regex(""" {2,}"""), " ").trim()

    private fun strip(text: String, range: IntRange): String =
        strip(text, range.first, range.last - range.first + 1)

    private fun parseIso(s: String): LocalDate? = try {
        LocalDate.parse(s, ISO_FMT)
    } catch (_: Exception) {
        null
    }

    private fun dayOfWeek(s: String): DayOfWeek = when (s.lowercase().take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}
