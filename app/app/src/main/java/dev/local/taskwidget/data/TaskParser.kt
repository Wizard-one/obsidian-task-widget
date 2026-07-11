package dev.local.taskwidget.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Obsidian Tasks 优先级,order 越小越靠前(与 Obsidian Tasks 的排序一致:
 * 🔺 > ⏫ > 🔼 > 无 > 🔽 > ⏬)。
 */
enum class Priority(val marker: String, val order: Int) {
    HIGHEST("🔺", 0),
    HIGH("⏫", 1),
    MEDIUM("🔼", 2),
    NONE("", 3),
    LOW("🔽", 4),
    LOWEST("⏬", 5);

    companion object {
        fun fromOrder(order: Int): Priority = entries.firstOrNull { it.order == order } ?: NONE
    }
}

data class ParsedTask(
    /** 原始行内容(不含行尾符),写回时用于精确定位 */
    val rawLine: String,
    /** 剥离元数据后的显示文本 */
    val text: String,
    val dueDate: LocalDate?,
    val priority: Priority,
    val done: Boolean,
    /** 文本中的 #标签(不含 #) */
    val tags: List<String> = emptyList(),
)

object TaskParser {

    private val TASK_LINE = Regex("""^\s*[-*+]\s+\[(.)]\s+(.*)$""")
    private val TASK_PREFIX = Regex("""^(\s*[-*+]\s+\[.])\s+(.*)$""")
    private val CHECKBOX_PREFIX = Regex("""^(\s*[-*+]\s+)\[ ]""")

    private val DUE = Regex("""📅\s*(\d{4}-\d{2}-\d{2})""")
    private val DONE_DATE = Regex("""✅\s*(\d{4}-\d{2}-\d{2})""")
    private val TAG = Regex("""#([\p{L}\p{N}_\-/]+)""")

    /**
     * 编辑重建行时需要原样保留的元数据(我们的编辑器不管这些,但不能弄丢):
     * ⏳ scheduled、🛫 start、➕ created、🔁 循环规则、🆔 id、⛔ 依赖。
     */
    private val PRESERVE_PATTERNS = listOf(
        Regex("""⏳\s*\d{4}-\d{2}-\d{2}"""),
        Regex("""🛫\s*\d{4}-\d{2}-\d{2}"""),
        Regex("""➕\s*\d{4}-\d{2}-\d{2}"""),
        Regex("""🔁[^📅⏳🛫➕✅❌🔺⏫🔼🔽⏬🆔⛔]*"""),
        Regex("""🆔\s*\S+"""),
        Regex("""⛔\s*\S+"""),
    )

    /** 显示时需要从文本中剥离的全部元数据 */
    private val STRIP_PATTERNS = PRESERVE_PATTERNS + listOf(
        DUE,
        DONE_DATE,
        Regex("""❌\s*\d{4}-\d{2}-\d{2}"""),
    )

    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * 解析一行 markdown。不是任务行、或状态不是待办/已完成(如取消 `- [-]`)时返回 null。
     */
    fun parseLine(line: String): ParsedTask? {
        val m = TASK_LINE.find(line) ?: return null
        val status = m.groupValues[1]
        val done = when (status) {
            " " -> false
            "x", "X" -> true
            else -> return null
        }
        val body = m.groupValues[2]

        val dueDate = DUE.find(body)?.groupValues?.get(1)?.let {
            try {
                LocalDate.parse(it, ISO_DATE)
            } catch (_: Exception) {
                null
            }
        }

        var priority = Priority.NONE
        for (p in Priority.entries) {
            if (p.marker.isNotEmpty() && body.contains(p.marker)) {
                priority = p
                break // entries 按 order 排列,取最高的一个
            }
        }

        var text = body
        for (pattern in STRIP_PATTERNS) {
            text = pattern.replace(text, " ")
        }
        for (p in Priority.entries) {
            if (p.marker.isNotEmpty()) text = text.replace(p.marker, " ")
        }
        text = text.replace(Regex("""\s+"""), " ").trim()

        val tags = TAG.findAll(text).map { it.groupValues[1] }.toList()

        return ParsedTask(
            rawLine = line,
            text = text,
            dueDate = dueDate,
            priority = priority,
            done = done,
            tags = tags,
        )
    }

    /** 解析整个文件内容,返回所有待办(未完成)任务 */
    fun parseFile(content: String): List<ParsedTask> =
        content.split("\n")
            .map { it.trimEnd('\r') }
            .mapNotNull { parseLine(it) }
            .filter { !it.done }

    /**
     * 把一行待办任务标记为完成:`[ ]` → `[x]`,末尾追加 ✅ 完成日期。
     * 输入不是待办任务行时返回 null。
     */
    fun completeLine(line: String, today: LocalDate = LocalDate.now()): String? {
        if (!CHECKBOX_PREFIX.containsMatchIn(line)) return null
        val checked = CHECKBOX_PREFIX.replace(line) { mr -> "${mr.groupValues[1]}[x]" }
        return "${checked.trimEnd()} ✅ ${today.format(ISO_DATE)}"
    }

    /**
     * 用新的文本/截止日期/优先级重建任务行。
     * 保留原有缩进、列表符、勾选状态,以及本编辑器不管理的元数据(⏳ 🛫 ➕ 🔁 🆔 ⛔)。
     * 不是任务行时返回 null。
     */
    fun editLine(rawLine: String, newText: String, newDue: LocalDate?, newPriority: Priority): String? {
        val m = TASK_PREFIX.find(rawLine) ?: return null
        val prefix = m.groupValues[1]
        val body = m.groupValues[2]

        // 按在原行中出现的位置排序,保持元数据原有顺序
        val preserved = PRESERVE_PATTERNS
            .flatMap { p -> p.findAll(body).map { it.range.first to it.value.trim() } }
            .sortedBy { it.first }
            .map { it.second }
            .filter { it.isNotEmpty() }

        val sb = StringBuilder(prefix).append(' ').append(newText.trim())
        if (newPriority.marker.isNotEmpty()) sb.append(' ').append(newPriority.marker)
        for (token in preserved) sb.append(' ').append(token)
        if (newDue != null) sb.append(" 📅 ").append(newDue.format(ISO_DATE))
        return sb.toString()
    }

    /**
     * 在文件内容中找到与 [rawLine] 完全一致的行,用 [transform] 的结果替换。
     * 保留原有的 CRLF/LF 行尾。找不到该行、或 transform 返回 null 时返回 null
     * (文件已被外部修改,需要重新扫描)。
     */
    fun replaceLineInContent(content: String, rawLine: String, transform: (String) -> String?): String? {
        val lines = content.split("\n").toMutableList()
        for (i in lines.indices) {
            val hasCr = lines[i].endsWith("\r")
            val bare = lines[i].trimEnd('\r')
            if (bare == rawLine) {
                val replaced = transform(bare) ?: return null
                lines[i] = if (hasCr) replaced + "\r" else replaced
                return lines.joinToString("\n")
            }
        }
        return null
    }

    /** 在文件内容中把 [rawLine] 标记为完成,见 [replaceLineInContent] */
    fun completeInContent(content: String, rawLine: String, today: LocalDate = LocalDate.now()): String? =
        replaceLineInContent(content, rawLine) { completeLine(it, today) }

    /** 在文件内容中重建 [rawLine],见 [editLine] 和 [replaceLineInContent] */
    fun editInContent(
        content: String,
        rawLine: String,
        newText: String,
        newDue: LocalDate?,
        newPriority: Priority,
    ): String? = replaceLineInContent(content, rawLine) { editLine(it, newText, newDue, newPriority) }
}
