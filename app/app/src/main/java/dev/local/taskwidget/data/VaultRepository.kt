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
            // 有效日期:优先截止日,其次计划日(⏳)、开始日(🛫)。
            // 很多循环任务只有 ⏳/🛫 没有 📅,不取有效日期就进不了"今天/已过期"。
            val effectiveDue = t.dueDate ?: t.scheduledDate ?: t.startDate
            arr.put(
                JSONObject()
                    .put("rawLine", t.rawLine)
                    .put("text", t.text)
                    .put("dueDate", effectiveDue?.toString() ?: JSONObject.NULL)
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
        val cache = loadCacheJson(context)
        val oldFiles = cache.optJSONObject("files") ?: JSONObject()
        // mtimes 记录 vault 里所有 .md 文件的修改时间(含无任务的),用于跳过未改动文件的读取
        val oldMtimes = cache.optJSONObject("mtimes") ?: JSONObject()

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
        cacheFile(context).writeText(JSONObject().put("files", files).put("mtimes", mtimes).toString())
    }

    /**
     * 增量:只重解析单个文件并更新缓存(用于快速添加后,避免整库慢扫描)。
     */
    suspend fun noteFileChanged(context: Context, uri: Uri): Unit = withContext(Dispatchers.IO) {
        try {
            val content = readDocument(context, uri) ?: return@withContext
            val cache = loadCacheJson(context)
            val files = cache.optJSONObject("files") ?: JSONObject()
            val mtimes = cache.optJSONObject("mtimes") ?: JSONObject()
            val key = uri.toString()
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
            val content = readDocument(context, uri) ?: return@withContext false
            // 找不到原行(文件已被外部改动)→ transform 返回 null,交调用方处理,即为冲突防护
            val updated = transform(content) ?: return@withContext false

            // "wt" 确保截断旧内容
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(updated.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false

            // 增量更新缓存:只重解析这一个文件(不触发整库扫描)
            val cache = loadCacheJson(context)
            val files = cache.optJSONObject("files") ?: JSONObject()
            val mtimes = cache.optJSONObject("mtimes") ?: JSONObject()
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
            writeCache(context, files, mtimes)
            true
        } catch (_: Exception) {
            false
        }
    }
}
