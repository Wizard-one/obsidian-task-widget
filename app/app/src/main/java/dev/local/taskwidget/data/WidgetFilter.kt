package dev.local.taskwidget.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 单个 widget 实例的筛选配置(每个添加到主屏幕的 widget 各存一份)。
 */
data class WidgetFilter(
    /** widget 头部显示的标题 */
    val title: String = "任务",
    /** 日期范围,见 SCOPE_* */
    val dateScope: Int = SCOPE_ALL,
    /** 是否显示没有截止日期的任务 */
    val includeUndated: Boolean = true,
    /** 标签筛选(不含 #),任务含其中任意一个即通过;空 = 不筛 */
    val tags: List<String> = emptyList(),
    /** 文件路径包含(不区分大小写),空 = 不筛。如 "工作/" 或 "Tasks.md" */
    val pathContains: String = "",
    /** 排除路径(不区分大小写),任务路径含其中任意一项则隐藏;空 = 不排除。 */
    val excludePaths: List<String> = emptyList(),
) {

    fun apply(tasks: List<TaskItem>, today: LocalDate = LocalDate.now()): List<TaskItem> =
        tasks.filter { task ->
            val due = task.actualDue
            val dateOk = when {
                // "今天 + 已过期"只按真正的截止日判断；开始日不代表逾期。
                dateScope == SCOPE_TODAY -> task.happensOnOrBefore(today)
                // 无日期任务只在"全部"范围下出现(且受 includeUndated 控制)。
                due == null -> dateScope == SCOPE_ALL && includeUndated
                dateScope == SCOPE_WEEK -> !due.isAfter(today.plusDays(7))
                else -> true
            }
            val tagOk = tags.isEmpty() || tags.any { it in task.tags }
            val pathOk = pathContains.isBlank() ||
                task.path.contains(pathContains.trim(), ignoreCase = true)
            val notExcluded = excludePaths.none { task.path.contains(it, ignoreCase = true) }
            dateOk && tagOk && pathOk && notExcluded
        }

    fun toJson(): String = JSONObject()
        .put("title", title)
        .put("dateScope", dateScope)
        .put("includeUndated", includeUndated)
        .put("tags", JSONArray(tags))
        .put("pathContains", pathContains)
        .put("excludePaths", JSONArray(excludePaths))
        .toString()

    companion object {
        /** 显示全部任务 */
        const val SCOPE_ALL = 0

        /** 今天到期 + 已过期 */
        const val SCOPE_TODAY = 1

        /** 7 天内到期 + 已过期 */
        const val SCOPE_WEEK = 2

        fun fromJson(json: String?): WidgetFilter {
            if (json.isNullOrBlank()) return WidgetFilter()
            return try {
                val o = JSONObject(json)
                val tags = mutableListOf<String>()
                o.optJSONArray("tags")?.let { arr ->
                    for (i in 0 until arr.length()) tags += arr.getString(i)
                }
                val excludes = mutableListOf<String>()
                o.optJSONArray("excludePaths")?.let { arr ->
                    for (i in 0 until arr.length()) excludes += arr.getString(i)
                }
                WidgetFilter(
                    title = o.optString("title", "任务").ifBlank { "任务" },
                    dateScope = o.optInt("dateScope", SCOPE_ALL),
                    includeUndated = o.optBoolean("includeUndated", true),
                    tags = tags,
                    pathContains = o.optString("pathContains", ""),
                    excludePaths = excludes,
                )
            } catch (_: Exception) {
                WidgetFilter()
            }
        }

        /** 把用户输入的标签串(逗号/空格分隔,可带 #)规范化为标签列表 */
        fun parseTagsInput(input: String): List<String> =
            input.split(',', ' ', ';', '，', '；')
                .map { it.trim().removePrefix("#") }
                .filter { it.isNotEmpty() }

        /** 把用户输入的路径串(逗号/分号分隔)规范化为路径片段列表(保留斜杠/空格) */
        fun parsePathsInput(input: String): List<String> =
            input.split(',', ';', '，', '；')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}
