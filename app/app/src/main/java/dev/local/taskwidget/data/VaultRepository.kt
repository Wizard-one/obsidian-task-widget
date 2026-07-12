package dev.local.taskwidget.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** widget / 配置页共用的任务条目(可 JSON 序列化,dueDate 用 ISO 字符串) */
data class TaskItem(
    val fileUri: String,
    val fileName: String,
    /** vault 内相对路径,如 "工作/项目.md" */
    val path: String,
    val rawLine: String,
    val text: String,
    val dueDate: String?,
    val priorityOrder: Int,
    val tags: List<String> = emptyList(),
) {
    val due: LocalDate? get() = dueDate?.let { LocalDate.parse(it) }
}

/**
 * 扫描 Obsidian vault(SAF 授权的文件夹)中的所有 .md 文件,
 * 解析待办任务,缓存到 filesDir,并支持把完成/编辑写回源文件。
 */
object VaultRepository {

    private const val PREFS = "settings"
    private const val KEY_VAULT_URI = "vault_uri"
    private const val KEY_VAULT_NAME = "vault_name"
    private const val KEY_LAST_SCAN = "last_scan"
    private const val KEY_EXCLUDE_PATHS = "exclude_paths"
    private const val CACHE_FILE = "tasks_cache_v2.json"

    // ---------- 设置 ----------

    fun getVaultUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VAULT_URI, null)?.let { Uri.parse(it) }

    fun setVaultUri(context: Context, uri: Uri) {
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VAULT_URI, uri.toString())
            .putString(KEY_VAULT_NAME, name)
            .apply()
        cacheFile(context).delete()
    }

    /** vault 根文件夹名(≈ Obsidian vault 名),用于拼 obsidian:// 链接 */
    fun getVaultName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VAULT_NAME, "") ?: ""

    fun getLastScanTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SCAN, 0L)

    /** 全局排除路径(逗号分隔,不区分大小写)。路径含其中任意一项的任务在所有视图/widget 中隐藏。 */
    fun getExcludePaths(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXCLUDE_PATHS, "")!!
            .split(',', ';', '，', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun getExcludePathsRaw(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EXCLUDE_PATHS, "") ?: ""

    fun setExcludePaths(context: Context, raw: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXCLUDE_PATHS, raw).apply()
    }

    // ---------- 缓存 ----------

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE)

    /** cache 结构: { files: { uri: { name, path, lastModified, tasks: [...] } } } */
    private fun loadCacheJson(context: Context): JSONObject =
        try {
            JSONObject(cacheFile(context).readText())
        } catch (_: Exception) {
            JSONObject().put("files", JSONObject())
        }

    private fun tasksToJson(tasks: List<ParsedTask>): JSONArray {
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(
                JSONObject()
                    .put("rawLine", t.rawLine)
                    .put("text", t.text)
                    .put("dueDate", t.dueDate?.toString() ?: JSONObject.NULL)
                    .put("priority", t.priority.order)
                    .put("tags", JSONArray(t.tags))
            )
        }
        return arr
    }

    private fun fileEntry(name: String, path: String, lastModified: Long, tasks: List<ParsedTask>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("path", path)
            .put("lastModified", lastModified)
            .put("tasks", tasksToJson(tasks))

    /**
     * 读取缓存中的全部待办任务,按 截止日期(过期最前)→ 优先级 排序。
     * 应用全局排除路径:路径含任一排除项的任务在所有视图/widget 中都不返回。
     */
    fun loadTasks(context: Context): List<TaskItem> {
        val files = loadCacheJson(context).optJSONObject("files") ?: return emptyList()
        val excludes = getExcludePaths(context)
        val tasks = mutableListOf<TaskItem>()
        for (uri in files.keys()) {
            val f = files.getJSONObject(uri)
            val name = f.optString("name")
            val path = f.optString("path", name)
            if (excludes.any { path.contains(it, ignoreCase = true) }) continue
            val arr = f.optJSONArray("tasks") ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val tags = mutableListOf<String>()
                t.optJSONArray("tags")?.let { ta ->
                    for (j in 0 until ta.length()) tags += ta.getString(j)
                }
                tasks += TaskItem(
                    fileUri = uri,
                    fileName = name,
                    path = path,
                    rawLine = t.getString("rawLine"),
                    text = t.getString("text"),
                    dueDate = if (t.isNull("dueDate")) null else t.getString("dueDate"),
                    priorityOrder = t.optInt("priority", Priority.NONE.order),
                    tags = tags,
                )
            }
        }
        return tasks.sortedWith(
            compareBy(
                { it.due ?: LocalDate.MAX },
                { it.priorityOrder },
                { it.text },
            )
        )
    }

    // ---------- 扫描 ----------

    /**
     * 全量遍历 vault,仅重新解析 lastModified 变化的文件,更新缓存。
     * 返回待办任务数;vault 未配置或不可访问时返回 null。
     */
    suspend fun scan(context: Context): Int? = withContext(Dispatchers.IO) {
        val vaultUri = getVaultUri(context) ?: return@withContext null
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return@withContext null
        if (!root.isDirectory) return@withContext null

        val oldFiles = loadCacheJson(context).optJSONObject("files") ?: JSONObject()
        val newFiles = JSONObject()
        var count = 0

        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to "")
        while (stack.isNotEmpty()) {
            val (dir, relPath) = stack.removeLast()
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val childPath = if (relPath.isEmpty()) name else "$relPath/$name"
                if (child.isDirectory) {
                    if (!name.startsWith(".")) stack.addLast(child to childPath)
                    continue
                }
                if (!name.endsWith(".md", ignoreCase = true)) continue

                val uriStr = child.uri.toString()
                val lastModified = child.lastModified()
                val cached = oldFiles.optJSONObject(uriStr)

                val entry = if (cached != null && cached.optLong("lastModified") == lastModified && lastModified > 0) {
                    cached.put("path", childPath)
                } else {
                    val content = readDocument(context, child.uri)
                    fileEntry(name, childPath, lastModified, content?.let { TaskParser.parseFile(it) } ?: emptyList())
                }
                val taskCount = entry.optJSONArray("tasks")?.length() ?: 0
                count += taskCount
                // 无任务的文件不进缓存,省空间
                if (taskCount > 0) newFiles.put(uriStr, entry)
            }
        }

        cacheFile(context).writeText(JSONObject().put("files", newFiles).toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SCAN, System.currentTimeMillis()).apply()
        count
    }

    private fun readDocument(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) {
            null
        }

    // ---------- 写回 ----------

    /**
     * 把任务标记为完成并写回源 markdown 文件。
     * 文件已被外部修改找不到原行时返回 false。
     */
    suspend fun completeTask(context: Context, fileUriStr: String, rawLine: String): Boolean =
        rewriteTaskLine(context, fileUriStr) { content ->
            TaskParser.completeInContent(content, rawLine)
        }

    /**
     * 用新的文本/截止日期/优先级重写任务行并写回源 markdown 文件。
     */
    suspend fun editTask(
        context: Context,
        fileUriStr: String,
        rawLine: String,
        newText: String,
        newDue: LocalDate?,
        newPriority: Priority,
    ): Boolean =
        rewriteTaskLine(context, fileUriStr) { content ->
            TaskParser.editInContent(content, rawLine, newText, newDue, newPriority)
        }

    /**
     * 读文件 → transform 得到新内容 → 写回 → 重解析该文件更新缓存。
     * transform 返回 null(找不到原行)或任何一步失败时返回 false。
     */
    private suspend fun rewriteTaskLine(
        context: Context,
        fileUriStr: String,
        transform: (String) -> String?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(fileUriStr)
            val docBefore = DocumentFile.fromSingleUri(context, uri)
            val modifiedBefore = docBefore?.lastModified() ?: 0L
            val content = readDocument(context, uri) ?: return@withContext false
            val updated = transform(content) ?: return@withContext false

            // 冲突防护:写之前复查文件是否被外部改动过(如 Obsidian 同时在编辑)
            val modifiedNow = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
            if (modifiedBefore > 0 && modifiedNow > 0 && modifiedNow != modifiedBefore) {
                return@withContext false // 交给调用方重新扫描后再试
            }

            // "wt" 确保截断旧内容
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(updated.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false

            // 更新缓存:重解析这一个文件
            val cache = loadCacheJson(context)
            val files = cache.optJSONObject("files") ?: JSONObject()
            val old = files.optJSONObject(fileUriStr)
            val doc = DocumentFile.fromSingleUri(context, uri)
            val name = old?.optString("name")?.ifEmpty { null } ?: doc?.name ?: ""
            val path = old?.optString("path")?.ifEmpty { null } ?: name
            val entry = fileEntry(name, path, doc?.lastModified() ?: 0L, TaskParser.parseFile(updated))
            if (entry.getJSONArray("tasks").length() > 0) {
                files.put(fileUriStr, entry)
            } else {
                files.remove(fileUriStr)
            }
            cacheFile(context).writeText(JSONObject().put("files", files).toString())
            true
        } catch (_: Exception) {
            false
        }
    }
}
