package dev.local.taskwidget.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/** 笔记 widget 列表中的单个 Markdown 文档。 */
data class NoteItem(
    val name: String,
    val uri: String,
)

sealed interface NoteListResult {
    data class Success(val items: List<NoteItem>) : NoteListResult
    data object Unavailable : NoteListResult
}

sealed interface CreateNoteResult {
    data class Created(val fileName: String, val uri: String) : CreateNoteResult
    data object InvalidName : CreateNoteResult
    data object FolderUnavailable : CreateNoteResult
    data object TemplateUnreadable : CreateNoteResult
    data object Conflict : CreateNoteResult
    data object WriteFailed : CreateNoteResult
}

/** 直接读写 SAF 文件夹中的 Markdown 笔记,不依赖任务扫描缓存。 */
object NoteRepository {

    private const val MAX_TITLE_LENGTH = 120
    private const val MAX_CONFLICT_SUFFIX = 9999
    private const val MARKDOWN_MIME = "text/markdown"
    private val WINDOWS_RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)
    private val INVALID_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    suspend fun listNotes(context: Context, folderUri: String): NoteListResult =
        withContext(Dispatchers.IO) { listNotesBlocking(context, folderUri) }

    internal fun normalizeTitle(input: String): String? {
        var title = input.trim()
        if (title.endsWith(".md", ignoreCase = true)) title = title.dropLast(3).trim()
        if (title.isBlank() || title == "." || title == "..") return null
        if (title.length > MAX_TITLE_LENGTH || title.endsWith('.') || title.endsWith(' ')) return null
        if (title.any { it.code < 32 || it in INVALID_CHARS }) return null
        if (WINDOWS_RESERVED.matches(title)) return null
        return title
    }

    internal fun renderContent(
        template: String?,
        title: String,
        date: LocalDate,
        body: String = "",
    ): String {
        val base = template?.replace("{{title}}", title)?.replace("{{date}}", date.toString())
            ?: "# $title"
        if (body.isEmpty() || base.isEmpty()) return if (base.isEmpty()) body else base
        return when {
            base.endsWith("\n\n") -> base + body
            base.endsWith("\n") -> base + "\n" + body
            else -> base + "\n\n" + body
        }
    }

    internal fun nextFileName(title: String, existingNames: Collection<String>): String? {
        val occupied = existingNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        for (suffix in 0..MAX_CONFLICT_SUFFIX) {
            val candidate = if (suffix == 0) "$title.md" else "$title ($suffix).md"
            if (candidate.lowercase(Locale.ROOT) !in occupied) return candidate
        }
        return null
    }

    internal fun isMarkdownChild(name: String, mimeType: String?): Boolean =
        mimeType != DocumentsContract.Document.MIME_TYPE_DIR && name.endsWith(".md", ignoreCase = true)

    suspend fun createNote(
        context: Context,
        folderUri: String,
        templateUri: String?,
        requestedName: String,
        body: String = "",
        today: LocalDate = LocalDate.now(),
    ): CreateNoteResult = withContext(Dispatchers.IO) {
        val title = normalizeTitle(requestedName) ?: return@withContext CreateNoteResult.InvalidName
        val template = if (templateUri != null) {
            readText(context, Uri.parse(templateUri)) ?: return@withContext CreateNoteResult.TemplateUnreadable
        } else null
        val listing = listNotesBlocking(context, folderUri)
        if (listing !is NoteListResult.Success) return@withContext CreateNoteResult.FolderUnavailable
        val fileName = nextFileName(title, listing.items.map { it.name })
            ?: return@withContext CreateNoteResult.Conflict
        val content = renderContent(template, title, today, body)

        val resolver = context.contentResolver
        val treeUri = try { Uri.parse(folderUri) } catch (_: Exception) {
            return@withContext CreateNoteResult.FolderUnavailable
        }
        val parentUri = try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        } catch (_: Exception) {
            return@withContext CreateNoteResult.FolderUnavailable
        }

        val created = try {
            DocumentsContract.createDocument(resolver, parentUri, MARKDOWN_MIME, fileName)
        } catch (_: Exception) {
            null
        } ?: return@withContext CreateNoteResult.WriteFailed

        try {
            resolver.openOutputStream(created, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Cannot open new note")
            CreateNoteResult.Created(queryDisplayName(context, created) ?: fileName, created.toString())
        } catch (_: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            CreateNoteResult.WriteFailed
        }
    }

    /**
     * 在当前全局 Vault 中按 document ID 查找笔记的真实相对路径。
     * 只比较 provider 给出的不透明 ID,不猜测 ID 的层级格式。
     */
    suspend fun findVaultRelativePath(context: Context, targetUri: Uri): String? =
        withContext(Dispatchers.IO) {
            val treeUri = VaultRepository.getVaultUri(context) ?: return@withContext null
            if (treeUri.authority != targetUri.authority) return@withContext null
            val targetId = try { DocumentsContract.getDocumentId(targetUri) } catch (_: Exception) {
                return@withContext null
            }
            val rootId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) {
                return@withContext null
            }
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val stack = ArrayDeque<Pair<String, String>>()
            stack.addLast(rootId to "")
            while (stack.isNotEmpty()) {
                val (dirId, parentPath) = stack.removeLast()
                val childrenUri = try {
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
                } catch (_: Exception) {
                    continue
                }
                val cursor = try {
                    context.contentResolver.query(childrenUri, projection, null, null, null)
                } catch (_: Exception) {
                    null
                } ?: continue
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                        if (childId == targetId) return@withContext path
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR && !name.startsWith('.')) {
                            stack.addLast(childId to path)
                        }
                    }
                }
            }
            null
        }

    private fun listNotesBlocking(context: Context, folderUri: String): NoteListResult {
        val treeUri = try { Uri.parse(folderUri) } catch (_: Exception) {
            return NoteListResult.Unavailable
        }
        val rootId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) {
            return NoteListResult.Unavailable
        }
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        } catch (_: Exception) {
            return NoteListResult.Unavailable
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val items = ArrayList<NoteItem>()
        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return NoteListResult.Unavailable
        try {
            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (!isMarkdownChild(name, mime)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    items += NoteItem(name, uri.toString())
                }
            }
        } catch (_: Exception) {
            return NoteListResult.Unavailable
        }
        return NoteListResult.Success(
            items.sortedWith(
                compareBy<NoteItem> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }
            )
        )
    }

    private fun readText(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}
