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

        /** Dataview 值(high/medium/low)或数字(1..5)→ 优先级 */
        fun fromDataview(value: String): Priority = when (value.trim().lowercase()) {
            "highest", "5" -> HIGHEST
            "high", "4" -> HIGH
            "medium", "3" -> MEDIUM
            "low", "2" -> LOW
            "lowest", "1" -> LOWEST
            else -> NONE
        }
    }
}

/** 任务勾选状态。方括号里的字符:空=待办,x/X=完成,-=取消,其它=进行中/自定义 */
enum class TaskStatus { TODO, DONE, CANCELLED, IN_PROGRESS }

data class ParsedTask(
    /** 原始行内容(不含行尾符),写回时用于精确定位 */
    val rawLine: String,
    /** 剥离元数据后的显示文本 */
    val text: String,
    val status: TaskStatus,
    val priority: Priority,
    val dueDate: LocalDate? = null,
    val scheduledDate: LocalDate? = null,
    val startDate: LocalDate? = null,
    val createdDate: LocalDate? = null,
    val doneDate: LocalDate? = null,
    val cancelledDate: LocalDate? = null,
    /** 循环规则原文(🔁 / [repeat:: ...] 后面的部分),null 表示非循环 */
    val recurrence: String? = null,
    val id: String? = null,
    val dependsOn: List<String> = emptyList(),
    /** 文本中的 #标签(不含 #) */
    val tags: List<String> = emptyList(),
    /** 缩进层级(每 2 空格或 1 tab 记 1 级),用于识别子任务 */
    val indent: Int = 0,
) {
    val done: Boolean get() = status == TaskStatus.DONE
    val isRecurring: Boolean get() = recurrence != null
}

object TaskParser {

    private val TASK_LINE = Regex("""^(\s*)[-*+]\s+\[(.)]\s+(.*)$""")
    private val TASK_PREFIX = Regex("""^(\s*[-*+]\s+\[.])\s+(.*)$""")
    private val CHECKBOX_PREFIX = Regex("""^(\s*[-*+]\s+)\[ ]""")

    // ---- emoji 字段 ----
    private val DATE = """(\d{4}-\d{2}-\d{2})"""
    private val DUE = Regex("""📅\s*$DATE""")
    private val SCHEDULED = Regex("""[⏳⌛]\s*$DATE""")
    private val START = Regex("""🛫\s*$DATE""")
    private val CREATED = Regex("""➕\s*$DATE""")
    private val DONE_DATE = Regex("""✅\s*$DATE""")
    private val CANCELLED = Regex("""❌\s*$DATE""")
    private val RECUR = Regex("""🔁\s*([^📅⏳⌛🛫➕✅❌🔺⏫🔼🔽⏬🆔⛔#]+)""")
    private val ID = Regex("""🆔\s*(\S+)""")
    private val DEPENDS = Regex("""⛔\s*([^📅⏳⌛🛫➕✅❌🔺⏫🔼🔽⏬🆔#]+)""")
    private val TAG = Regex("""#([\p{L}\p{N}_\-/]+)""")

    // ---- Dataview 内联字段 [key:: value] 或 (key:: value) ----
    private val DV = Regex("""[\[(]\s*([a-zA-Z][\w-]*)\s*::\s*([^\])]*?)\s*[\])]""")

    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * 解析一行 markdown。不是任务行时返回 null(取消/进行中也会解析,由调用方决定是否显示)。
     */
    fun parseLine(line: String): ParsedTask? {
        val m = TASK_LINE.find(line) ?: return null
        val indentStr = m.groupValues[1]
        val statusChar = m.groupValues[2]
        val body = m.groupValues[3]

        val status = when (statusChar) {
            " " -> TaskStatus.TODO
            "x", "X" -> TaskStatus.DONE
            "-" -> TaskStatus.CANCELLED
            else -> TaskStatus.IN_PROGRESS
        }
        val indent = indentStr.replace("\t", "  ").length / 2

        // 先解析 Dataview 字段(它们会连同 emoji 字段一起从显示文本里剥掉)
        val dv = HashMap<String, String>()
        for (mm in DV.findAll(body)) dv[mm.groupValues[1].lowercase()] = mm.groupValues[2].trim()

        fun dvDate(vararg keys: String): LocalDate? {
            for (k in keys) dv[k]?.let { return parseDate(it) }
            return null
        }

        val dueDate = DUE.find(body)?.let { parseDate(it.groupValues[1]) } ?: dvDate("due", "due-date")
        val scheduledDate = SCHEDULED.find(body)?.let { parseDate(it.groupValues[1]) } ?: dvDate("scheduled")
        val startDate = START.find(body)?.let { parseDate(it.groupValues[1]) } ?: dvDate("start")
        val createdDate = CREATED.find(body)?.let { parseDate(it.groupValues[1]) } ?: dvDate("created")
        val doneDate = DONE_DATE.find(body)?.let { parseDate(it.groupValues[1]) } ?: dvDate("completion", "done")
        val cancelledDate = CANCELLED.find(body)?.let { parseDate(it.groupValues[1]) } ?: dvDate("cancelled")

        val recurrence = (RECUR.find(body)?.groupValues?.get(1)?.trim()
            ?: dv["repeat"] ?: dv["recurrence"])?.ifBlank { null }
        val id = ID.find(body)?.groupValues?.get(1) ?: dv["id"]
        val dependsOn = (DEPENDS.find(body)?.groupValues?.get(1)?.trim() ?: dv["dependson"] ?: dv["depends"])
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        var priority = Priority.NONE
        for (p in Priority.entries) {
            if (p.marker.isNotEmpty() && body.contains(p.marker)) {
                priority = p
                break // entries 按 order 排列,取最高的一个
            }
        }
        if (priority == Priority.NONE) dv["priority"]?.let { priority = Priority.fromDataview(it) }

        val text = cleanText(body)
        val tags = TAG.findAll(text).map { it.groupValues[1] }.toList()

        return ParsedTask(
            rawLine = line,
            text = text,
            status = status,
            priority = priority,
            dueDate = dueDate,
            scheduledDate = scheduledDate,
            startDate = startDate,
            createdDate = createdDate,
            doneDate = doneDate,
            cancelledDate = cancelledDate,
            recurrence = recurrence,
            id = id,
            dependsOn = dependsOn,
            tags = tags,
            indent = indent,
        )
    }

    /** 从任务正文剥离所有元数据,得到干净的显示文本 */
    private fun cleanText(body: String): String {
        var text = body
        text = DV.replace(text, " ")
        for (pattern in listOf(DUE, SCHEDULED, START, CREATED, DONE_DATE, CANCELLED, RECUR, ID, DEPENDS)) {
            text = pattern.replace(text, " ")
        }
        for (p in Priority.entries) {
            if (p.marker.isNotEmpty()) text = text.replace(p.marker, " ")
        }
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim(), ISO_DATE)
    } catch (_: Exception) {
        null
    }

    /** 解析整个文件内容,返回所有待办(未完成、未取消)任务 */
    fun parseFile(content: String): List<ParsedTask> =
        content.split("\n")
            .map { it.trimEnd('\r') }
            .mapNotNull { parseLine(it) }
            .filter { it.status == TaskStatus.TODO || it.status == TaskStatus.IN_PROGRESS }

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
     * 对循环任务生成"下一次实例"的待办行(日期已按规则前移,不含 ✅)。
     * 非循环任务、或无参考日期时返回 null。目前只处理 emoji 格式的日期字段。
     */
    fun nextRecurrenceLine(line: String, today: LocalDate = LocalDate.now()): String? {
        val task = parseLine(line) ?: return null
        val rule = Recurrence.parse(task.recurrence) ?: return null
        val next = rule.nextDates(task.dueDate, task.scheduledDate, task.startDate, today) ?: return null

        var result = line
        result = shiftEmojiDate(result, DUE, "📅", task.dueDate, next.due)
        result = shiftEmojiDate(result, SCHEDULED, "⏳", task.scheduledDate, next.scheduled)
        result = shiftEmojiDate(result, START, "🛫", task.startDate, next.start)
        // 稳妥起见去掉可能存在的完成日期(循环源行本就是 [ ]);保留前导缩进
        result = DONE_DATE.replace(result, "").trimEnd()
        return result
    }

    private fun shiftEmojiDate(
        line: String, pattern: Regex, marker: String, old: LocalDate?, new: LocalDate?,
    ): String {
        if (old == null || new == null) return line
        return pattern.replace(line) { "$marker ${new.format(ISO_DATE)}" }
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

        // 保留的元数据(按在原行出现顺序),不含 due 和优先级(这两个由参数重设)
        val preservePatterns = listOf(SCHEDULED, START, CREATED, RECUR, ID, DEPENDS)
        val preserved = preservePatterns
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
     * 保留原有的 CRLF/LF 行尾。找不到该行、或 transform 返回 null 时返回 null。
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

    /**
     * 在文件内容中把 [rawLine] 标记为完成。
     * 若是循环任务,会在完成行**上方**插入下一次实例(与 Obsidian Tasks 行为一致)。
     */
    fun completeInContent(content: String, rawLine: String, today: LocalDate = LocalDate.now()): String? {
        val nextLine = nextRecurrenceLine(rawLine, today)
        val sep = if (content.contains("\r\n")) "\r\n" else "\n"
        return replaceLineInContent(content, rawLine) { bare ->
            val completed = completeLine(bare, today) ?: return@replaceLineInContent null
            if (nextLine != null) "$nextLine$sep$completed" else completed
        }
    }

    /** 在文件内容中重建 [rawLine],见 [editLine] 和 [replaceLineInContent] */
    fun editInContent(
        content: String,
        rawLine: String,
        newText: String,
        newDue: LocalDate?,
        newPriority: Priority,
    ): String? = replaceLineInContent(content, rawLine) { editLine(it, newText, newDue, newPriority) }

    /**
     * 从文件内容中删除与 [rawLine] 完全一致的整行(保留其余行与行尾风格)。
     * 找不到该行时返回 null。
     */
    fun removeLineInContent(content: String, rawLine: String): String? {
        val lines = content.split("\n")
        var removed = false
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            val bare = line.trimEnd('\r')
            if (!removed && bare == rawLine) {
                removed = true
                continue
            }
            out.add(line)
        }
        return if (removed) out.joinToString("\n") else null
    }
}
