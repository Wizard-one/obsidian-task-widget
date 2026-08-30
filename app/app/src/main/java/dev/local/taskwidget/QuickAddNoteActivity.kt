package dev.local.taskwidget

import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.local.taskwidget.data.CreateNoteResult
import dev.local.taskwidget.data.NoteRepository
import dev.local.taskwidget.ui.AppTheme
import dev.local.taskwidget.widget.NoteWidgetConfig
import dev.local.taskwidget.widget.NoteWidgetConfigStore
import dev.local.taskwidget.widget.NoteWidgetReceiver
import kotlinx.coroutines.launch

/** 从笔记 widget 右上角快速创建 Markdown 笔记。 */
class QuickAddNoteActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val config = NoteWidgetConfigStore.load(this, appWidgetId)
        if (config == null) {
            Toast.makeText(this, "笔记 Widget 尚未配置", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            AppTheme { QuickAddNoteDialog(config) }
        }
    }

    @Composable
    private fun QuickAddNoteDialog(config: NoteWidgetConfig) {
        val scope = rememberCoroutineScope()
        val nameFocus = remember { FocusRequester() }
        val bodyFocus = remember { FocusRequester() }
        var name by remember { mutableStateOf("") }
        var body by remember { mutableStateOf(TextFieldValue()) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var pasteMessage by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

        fun submit() {
            if (busy || name.isBlank()) return
            busy = true
            error = null
            pasteMessage = null
            scope.launch {
                when (val result = NoteRepository.createNote(
                    context = this@QuickAddNoteActivity,
                    folderUri = config.folderUri,
                    templateUri = config.templateUri,
                    requestedName = name,
                    body = body.text,
                )) {
                    is CreateNoteResult.Created -> {
                        NoteWidgetReceiver.renderFolder(this@QuickAddNoteActivity, config.folderUri)
                        Toast.makeText(
                            this@QuickAddNoteActivity,
                            "已创建 ${result.fileName}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    }
                    CreateNoteResult.InvalidName -> {
                        error = "名称不能为空,且不能含 < > : \" / \\ | ? * 等字符"
                        busy = false
                    }
                    CreateNoteResult.FolderUnavailable -> {
                        error = "无法访问目标文件夹,请重新配置 Widget"
                        busy = false
                    }
                    CreateNoteResult.TemplateUnreadable -> {
                        error = "无法读取默认模板,请重新配置模板"
                        busy = false
                    }
                    CreateNoteResult.Conflict -> {
                        error = "同名笔记过多,请换一个名称"
                        busy = false
                    }
                    CreateNoteResult.WriteFailed -> {
                        error = "创建失败,请检查文件夹写入权限"
                        busy = false
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!busy) finish() },
            title = { Text("新建笔记") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = { Text("笔记名称") },
                        placeholder = { Text("如:会议记录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
                        isError = error != null,
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it; pasteMessage = null },
                        label = { Text("笔记内容") },
                        placeholder = { Text("记录完整的 Markdown 内容…") },
                        singleLine = false,
                        minLines = 6,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 260.dp)
                            .focusRequester(bodyFocus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    TextButton(
                        onClick = {
                            val clipboard = clipboardText()
                            if (clipboard == null) {
                                pasteMessage = "剪贴板中没有可粘贴的文本"
                            } else {
                                body = insertAtSelection(body, clipboard)
                                pasteMessage = null
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("粘贴剪贴板内容")
                    }
                    Text("保存到:${config.folderName}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "模板:${config.templateName ?: "无(正文以 # 标题开头)"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "模板作为笔记开头;输入或粘贴的内容会追加在模板后。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    pasteMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = { submit() }, enabled = name.isNotBlank() && !busy) {
                    Text(if (busy) "创建中…" else "创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { finish() }, enabled = !busy) { Text("取消") }
            },
        )
    }

    private fun clipboardText(): String? {
        val clipboard = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clipboard.itemCount == 0) return null
        return clipboard.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun insertAtSelection(value: TextFieldValue, pasted: String): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end)
        val end = maxOf(value.selection.start, value.selection.end)
        val text = value.text.replaceRange(start, end, pasted)
        return TextFieldValue(text, TextRange(start + pasted.length))
    }

    companion object {
        fun intent(context: Context, appWidgetId: Int): Intent =
            Intent(context, QuickAddNoteActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("taskwidget://notes/add/$appWidgetId"))
    }
}
