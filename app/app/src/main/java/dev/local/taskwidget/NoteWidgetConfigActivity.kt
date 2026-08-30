package dev.local.taskwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import dev.local.taskwidget.ui.AppTheme
import dev.local.taskwidget.widget.NoteWidgetConfig
import dev.local.taskwidget.widget.NoteWidgetConfigStore
import dev.local.taskwidget.widget.NoteWidgetReceiver
import kotlinx.coroutines.launch

/** 添加或重新配置笔记 widget 时选择目标文件夹与可选默认模板。 */
class NoteWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            AppTheme {
                Scaffold(
                    topBar = { CenterAlignedTopAppBar(title = { Text("笔记 Widget 设置") }) }
                ) { padding -> ConfigScreen(padding) }
            }
        }
    }

    @Composable
    private fun ConfigScreen(contentPadding: PaddingValues) {
        val initial = remember { NoteWidgetConfigStore.load(this, appWidgetId) }
        val scope = rememberCoroutineScope()
        var folderUri by remember { mutableStateOf(initial?.folderUri) }
        var folderName by remember { mutableStateOf(initial?.folderName) }
        var templateUri by remember { mutableStateOf(initial?.templateUri) }
        var templateName by remember { mutableStateOf(initial?.templateName) }
        var message by remember { mutableStateOf<String?>(null) }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            if (uri != null) {
                val granted = runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.isSuccess
                if (granted) {
                    folderUri = uri.toString()
                    folderName = runCatching { DocumentFile.fromTreeUri(this, uri)?.name }.getOrNull()
                        ?: friendlyName(uri, "笔记")
                    message = null
                } else {
                    message = "无法保存该文件夹的读写授权,请重新选择"
                }
            }
        }

        val templatePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                val name = runCatching { DocumentFile.fromSingleUri(this, uri)?.name }.getOrNull()
                    ?: friendlyName(uri, "template.md")
                if (!name.endsWith(".md", ignoreCase = true)) {
                    message = "模板必须是 .md 文件"
                } else {
                    val granted = runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }.isSuccess
                    if (granted) {
                        templateUri = uri.toString()
                        templateName = name
                        message = null
                    } else {
                        message = "无法保存模板的读取授权,请重新选择"
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "每个笔记 Widget 可独立展示一个文件夹当前层的 Markdown 文件。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("笔记文件夹", style = MaterialTheme.typography.titleMedium)
                Text(folderName ?: "尚未选择")
                Button(onClick = { folderPicker.launch(null) }) {
                    Text(if (folderUri == null) "选择文件夹" else "更换文件夹")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("默认模板(可选)", style = MaterialTheme.typography.titleMedium)
                Text(templateName ?: "不使用模板;新笔记正文为 # 标题")
                Text(
                    "支持 {{title}} 与 {{date}}(yyyy-MM-dd)。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            templatePicker.launch(
                                arrayOf("text/markdown", "text/plain", "application/octet-stream", "*/*")
                            )
                        }
                    ) {
                        Text(if (templateUri == null) "选择模板" else "更换模板")
                    }
                    if (templateUri != null) {
                        OutlinedButton(onClick = {
                            templateUri = null
                            templateName = null
                            message = null
                        }) {
                            Text("清除")
                        }
                    }
                }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    val targetFolder = folderUri ?: return@Button
                    val config = NoteWidgetConfig(
                        folderUri = targetFolder,
                        folderName = folderName?.ifBlank { "笔记" } ?: "笔记",
                        templateUri = templateUri,
                        templateName = templateName,
                    )
                    NoteWidgetConfigStore.save(this@NoteWidgetConfigActivity, appWidgetId, config)
                    scope.launch {
                        NoteWidgetReceiver.render(
                            this@NoteWidgetConfigActivity,
                            intArrayOf(appWidgetId),
                        )
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    }
                },
                enabled = folderUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }

    private fun friendlyName(uri: Uri, fallback: String): String =
        uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?.ifBlank { fallback } ?: fallback
}
