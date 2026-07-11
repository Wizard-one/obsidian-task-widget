package dev.local.taskwidget.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 快速添加任务:把一行 markdown 任务追加到用户指定的 inbox 文件。
 * 输入文本会经过 [NaturalDate] 解析出截止日期。
 */
object QuickAdd {

    private const val PREFS = "settings"
    private const val KEY_INBOX_URI = "inbox_uri"
    private const val KEY_INBOX_NAME = "inbox_name"
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun getInboxUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INBOX_URI, null)?.let { Uri.parse(it) }

    fun getInboxName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INBOX_NAME, null)

    fun setInbox(context: Context, uri: Uri, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INBOX_URI, uri.toString())
            .putString(KEY_INBOX_NAME, name)
            .apply()
    }

    /** 把 [rawInput] 组装成任务行(解析自然语言日期),返回要写入的 markdown 行 */
    fun buildTaskLine(rawInput: String, today: LocalDate = LocalDate.now()): String {
        val (text, due) = NaturalDate.extractDue(rawInput.trim(), today)
        val body = text.ifBlank { rawInput.trim() }
        return if (due != null) "- [ ] $body 📅 ${due.format(ISO)}" else "- [ ] $body"
    }

    /**
     * 追加任务到 inbox 文件。成功返回写入的行,inbox 未配置或写入失败返回 null。
     */
    suspend fun append(context: Context, rawInput: String): String? = withContext(Dispatchers.IO) {
        if (rawInput.isBlank()) return@withContext null
        val uri = getInboxUri(context) ?: return@withContext null
        val line = buildTaskLine(rawInput)
        try {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return@withContext null
            if (!doc.exists()) return@withContext null

            val existing = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val sep = if (existing.contains("\r\n")) "\r\n" else "\n"
            val prefix = when {
                existing.isEmpty() -> ""
                existing.endsWith("\n") -> ""
                else -> sep
            }
            val updated = existing + prefix + line + sep

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(updated.toByteArray(Charsets.UTF_8))
            } ?: return@withContext null

            // 追加后刷新缓存,让 widget 立即反映
            VaultRepository.scan(context)
            line
        } catch (_: Exception) {
            null
        }
    }
}
