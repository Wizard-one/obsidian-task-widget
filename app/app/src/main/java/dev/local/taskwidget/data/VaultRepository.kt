package dev.local.taskwidget.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** widget / 配置页共用的任务条目(日期用 ISO 字符串,可 JSON 序列化) */
data class TaskItem(
    val fileUri: String,
    val fileName: String,
    /** vault 内相对路径,如 "工作/项目.md" */
    val path: String,
    val rawLine: String,
    val text: String,
    /** 兼容现有列表/日历的有效日期:截止日→计划日→开始日 */
    val dueDate: String?,
    val priorityOrder: Int,
    val tags: List<String> = emptyList(),
    /** 各原始日期字段,供 happens 筛选使用 */
    val actualDueDate: String? = null,
    val scheduledDate: String? = null,
    val startDate: String? = null,
) {
    private fun parseDate(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val due: LocalDate? get() = parseDate(dueDate)
    val actualDue: LocalDate? get() = parseDate(actualDueDate)
    val scheduled: LocalDate? get() = parseDate(scheduledDate)
    val start: LocalDate? get() = parseDate(startDate)

    /** “happens on or before”：开始日或截止日任一不晚于目标日期。 */
    fun happensOnOrBefore(date: LocalDate): Boolean =
        listOfNotNull(start, actualDue).any { !it.isAfter(date) }
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
    // 拆成两个文件:任务表(只含有任务的文件,widget 读它,较小)与 mtimes(含全部 .md
    // 修改时间,仅扫描用,可能很大)。此前合在一个文件里,widget 每次都要解析上千条 mtimes。
    private const val FILES_CACHE = "tasks_v3.json"
    private const val MTIMES_CACHE = "mtimes_v3.json"

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
        deleteCache(context)
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

    private fun filesFile(context: Context): File = File(context.filesDir, FILES_CACHE)
    private fun mtimesFile(context: Context): File = File(context.filesDir, MTIMES_CACHE)

    /** 任务表:{ uri: { name, path, lastModified, tasks: [...] } } */
    private fun loadFiles(context: Context): JSONObject =
        try {
            JSONObject(filesFile(context).readText())
        } catch (_: Exception) {
            JSONObject()
        }

    /** mtimes:{ uri: lastModified },仅扫描用 */
    private fun loadMtimes(context: Context): JSONObject =
        try {
            JSONObject(mtimesFile(context).readText())
        } catch (_: Exception) {
            JSONObject()
        }

    private fun deleteCache(context: Context) {
        filesFile(context).delete()
        mtimesFile(context).delete()
    }

    private fun tasksToJson(tasks: List<ParsedTask>): JSONArray {
        val arr = JSONArray()
        for (t in tasks) {
            // 有效日期:优先截止日,其次计划日(⏳)、开始日(🛫)。
            // 很多循环任务只有 ⏳/🛫 没有 📅,不取有效日期就进不了"今天/已过期"。
            val effectiveDue = t.dueDate ?: t.scheduledDate ?: t.startDate
            arr.put(
                JSONObject()
                    .put("rawLine", t.rawLine)
                    .put("text", t.text)
                    .put("dueDate", effectiveDue?.toString() ?: JSONObject.NULL)
                    .put("actualDueDate", t.dueDate?.toString() ?: JSONObject.NULL)
                    .put("scheduledDate", t.scheduledDate?.toString() ?: JSONObject.NULL)
                    .put("startDate", t.startDate?.toString() ?: JSONObject.NULL)
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
        val files = loadFiles(context)
        val excludes = getExcludePaths(context)
        val hidden = CompletionShade.hidden(context) // 乐观隐藏:刚点完成、写回未落地的任务先不显示
        val tasks = mutableListOf<TaskItem>()
        for (uri in files.keys()) {
            val f = files.getJSONObject(uri)
            val name = f.optString("name")
            val path = f.optString("path", name)
            if (excludes.any { path.contains(it, ignoreCase = true) }) continue
            val arr = f.optJSONArray("tasks") ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val rawLine = t.getString("rawLine")
                if (CompletionShade.idOf(uri, rawLine) in hidden) continue
                val tags = mutableListOf<String>()
                t.optJSONArray("tags")?.let { ta ->
                    for (j in 0 until ta.length()) tags += ta.getString(j)
                }
                // 兼容 v1.9.14 及以前的 v3 缓存:旧任务没有拆分日期字段,
                // 从 rawLine 即时补解析,避免升级后强制重扫整个 vault。
                val parsed = if (!t.has("actualDueDate") || !t.has("startDate")) {
                    TaskParser.parseLine(rawLine)
                } else null
                tasks += TaskItem(
                    fileUri = uri,
                    fileName = name,
                    path = path,
                    rawLine = rawLine,
                    text = t.getString("text"),
                    dueDate = if (t.isNull("dueDate")) null else t.getString("dueDate"),
                    priorityOrder = t.optInt("priority", Priority.NONE.order),
                    tags = tags,
                    actualDueDate = if (t.has("actualDueDate")) {
                        if (t.isNull("actualDueDate")) null else t.optString("actualDueDate")
                    } else parsed?.dueDate?.toString(),
                    scheduledDate = if (t.has("scheduledDate")) {
                        if (t.isNull("scheduledDate")) null else t.optString("scheduledDate")
                    } else parsed?.scheduledDate?.toString(),
                    startDate = if (t.has("startDate")) {
                        if (t.isNull("startDate")) null else t.optString("startDate")
                    } else parsed?.startDate?.toString(),
                )
            }
        }
        // 去重:同一文件可能被扫描(树 URI 键)和快速添加(单文档 URI 键)分别缓存,
        // 按 (路径 + 原始行) 去重,避免同一任务重复出现
        return tasks
            .distinctBy { it.path + " " + it.rawLine }
            .sortedWith(
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
     *
     * 用 DocumentsContract 对每个目录做一次批量查询(一次 cursor 拿到子项的
     * id/名称/类型/修改时间),而不是 DocumentFile 逐文件查询——后者每个文件的
     * name/lastModified/isDirectory 都是一次独立的 SAF IPC,几百个文件会慢到卡死。
     */
    private class FileRec(
        val uriStr: String,
        val docUri: Uri,
        val name: String,
        val path: String,
        val lastModified: Long,
    )

    suspend fun scan(context: Context): Int? = withContext(Dispatchers.IO) {
        val treeUri = getVaultUri(context) ?: return@withContext null
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return@withContext null
        }
        val resolver = context.contentResolver
        val oldFiles = loadFiles(context)
        // mtimes 记录 vault 里所有 .md 文件的修改时间(含无任务的),用于跳过未改动文件的读取
        val oldMtimes = loadMtimes(context)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        // 1) 遍历目录,只收集文件清单(每目录一次 cursor),不读取内容
        val allFiles = ArrayList<FileRec>()
        val stack = ArrayDeque<Pair<String, String>>() // documentId, 相对路径
        stack.addLast(rootDocId to "")
        while (stack.isNotEmpty()) {
            val (dirId, relPath) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val cursor = try {
                resolver.query(childrenUri, projection, null, null, null)
            } catch (_: Exception) {
                null
            } ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val lastModified = if (c.isNull(3)) 0L else c.getLong(3)
                    val childPath = if (relPath.isEmpty()) name else "$relPath/$name"

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.addLast(docId to childPath)
                        continue
                    }
                    if (!name.endsWith(".md", ignoreCase = true)) continue

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    allFiles += FileRec(docUri.toString(), docUri, name, childPath, lastModified)
                }
            }
        }

        // 2) 未改动的文件直接沿用缓存(不读取);其余排入待读列表
        val newFiles = JSONObject()
        val newMtimes = JSONObject()
        val toRead = ArrayList<FileRec>()
        var count = 0
        for (r in allFiles) {
            newMtimes.put(r.uriStr, r.lastModified)
            val unchanged = r.lastModified > 0 && oldMtimes.optLong(r.uriStr, -1L) == r.lastModified
            if (unchanged) {
                oldFiles.optJSONObject(r.uriStr)?.let { entry ->
                    entry.put("path", r.path)
                    newFiles.put(r.uriStr, entry)
                    count += entry.optJSONArray("tasks")?.length() ?: 0
                }
            } else {
                toRead += r
            }
        }

        // 3) 并发读取新增/改动的文件(限流 12,避免 SAF 逐个串行读几千个文件几十秒)
        val sem = Semaphore(12)
        val parsed = coroutineScope {
            toRead.map { r ->
                async {
                    sem.withPermit {
                        val content = readDocument(context, r.docUri)
                        r to (content?.let { TaskParser.parseFile(it) } ?: emptyList())
                    }
                }
            }.awaitAll()
        }
        for ((r, tasks) in parsed) {
            if (tasks.isNotEmpty()) {
                newFiles.put(r.uriStr, fileEntry(r.name, r.path, r.lastModified, tasks))
                count += tasks.size
            }
        }

        writeCache(context, newFiles, newMtimes)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SCAN, System.currentTimeMillis()).apply()
        count
    }

    private fun writeCache(context: Context, files: JSONObject, mtimes: JSONObject) {
        filesFile(context).writeText(files.toString())
        mtimesFile(context).writeText(mtimes.toString())
    }

    /**
     * 增量:只重解析单个文件并更新缓存(用于快速添加后,避免整库慢扫描)。
     */
    suspend fun noteFileChanged(context: Context, uri: Uri): Unit = withContext(Dispatchers.IO) {
        try {
            val content = readDocument(context, uri) ?: return@withContext
            val files = loadFiles(context)
            val mtimes = loadMtimes(context)
            // 用扫描相同的树 URI 作键,避免快速添加(单文档 URI)与扫描(树 URI)存两份
            val key = treeDocumentKey(context, uri)
            val old = files.optJSONObject(key)
            val doc = DocumentFile.fromSingleUri(context, uri)
            val lastModified = doc?.lastModified() ?: 0L
            val name = old?.optString("name")?.ifEmpty { null } ?: doc?.name ?: ""
            val path = old?.optString("path")?.ifEmpty { null } ?: name
            val entry = fileEntry(name, path, lastModified, TaskParser.parseFile(content))
            if (entry.getJSONArray("tasks").length() > 0) files.put(key, entry) else files.remove(key)
            mtimes.put(key, lastModified)
            writeCache(context, files, mtimes)
        } catch (_: Exception) {
            // 忽略;下次整库扫描会补上
        }
    }

    /**
     * 把任意文档 URI 归一化为"树内文档 URI"(与 scan 用的键一致)。
     * 拿不到 vault 树或文档 id 时,退回原 URI 字符串。
     */
    private fun treeDocumentKey(context: Context, uri: Uri): String {
        val treeUri = getVaultUri(context) ?: return uri.toString()
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
        } catch (_: Exception) {
            uri.toString()
        }
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
        rewriteTaskLine(context, fileUriStr, rawLine) { content ->
            TaskParser.completeInContent(content, rawLine)
        }

    /**
     * 从源 markdown 文件中删除该任务行并写回。找不到原行时返回 false。
     */
    suspend fun deleteTask(context: Context, fileUriStr: String, rawLine: String): Boolean =
        rewriteTaskLine(context, fileUriStr, rawLine) { content ->
            TaskParser.removeLineInContent(content, rawLine)
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
        rewriteTaskLine(context, fileUriStr, rawLine) { content ->
            TaskParser.editInContent(content, rawLine, newText, newDue, newPriority)
        }

    /**
     * 读文件 → transform 得到新内容 → 写回 → 重解析该文件更新缓存。
     * transform 返回 null(找不到原行)或任何一步失败时返回 false。
     */
    private suspend fun rewriteTaskLine(
        context: Context,
        fileUriStr: String,
        oldRawLine: String,
        transform: (String) -> String?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(fileUriStr)
            val content = readDocument(context, uri) ?: return@withContext false
            // 找不到原行(文件已被外部改动)→ transform 返回 null,交调用方处理,即为冲突防护
            val updated = transform(content) ?: return@withContext false

            // "wt" 确保截断旧内容
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(updated.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false

            // 增量更新缓存:只重解析这一个文件(不触发整库扫描)
            val files = loadFiles(context)
            val mtimes = loadMtimes(context)
            val old = files.optJSONObject(fileUriStr)
            val doc = DocumentFile.fromSingleUri(context, uri)
            val lastModified = doc?.lastModified() ?: 0L
            val name = old?.optString("name")?.ifEmpty { null } ?: doc?.name ?: ""
            val path = old?.optString("path")?.ifEmpty { null } ?: name
            val entry = fileEntry(name, path, lastModified, TaskParser.parseFile(updated))
            if (entry.getJSONArray("tasks").length() > 0) {
                files.put(fileUriStr, entry)
            } else {
                files.remove(fileUriStr)
            }
            mtimes.put(fileUriStr, lastModified)
            // 兜底:把这条旧行从所有同路径的缓存条目里清除。
            // 同一文件可能被扫描(树 URI 键)和快速添加(单文档 URI 键)存了两份,
            // 只更新一份会让另一份的旧行被 loadTasks 去重时捞回来,表现为"已完成但 widget 还在"。
            purgeLineFromAllEntries(files, path, oldRawLine, keepKey = fileUriStr)
            writeCache(context, files, mtimes)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 从 [files] 里所有 path==[path] 的条目中移除 rawLine==[rawLine] 的任务([keepKey] 除外,它已重解析) */
    private fun purgeLineFromAllEntries(files: JSONObject, path: String, rawLine: String, keepKey: String) {
        for (k in files.keys().asSequence().toList()) {
            if (k == keepKey) continue
            val fe = files.optJSONObject(k) ?: continue
            if (fe.optString("path") != path) continue
            val arr = fe.optJSONArray("tasks") ?: continue
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                if (t.optString("rawLine") != rawLine) kept.put(t)
            }
            if (kept.length() > 0) fe.put("tasks", kept) else files.remove(k)
        }
    }
}
