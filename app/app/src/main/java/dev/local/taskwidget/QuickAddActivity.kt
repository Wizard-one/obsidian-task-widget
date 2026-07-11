package dev.local.taskwidget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.data.QuickAdd
import dev.local.taskwidget.widget.TaskWidget
import kotlinx.coroutines.launch

/**
 * 轻量"快速添加"对话框 Activity。入口:桌面 widget ➕、快捷设置磁贴、分享文本、选词。
 * 以对话框主题呈现,加完即关闭。
 */
class QuickAddActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = extractSharedText(intent)

        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                android.os.Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                QuickAddDialog(initial = shared)
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String {
        intent ?: return ""
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
            else -> ""
        }
    }

    @androidx.compose.runtime.Composable
    private fun QuickAddDialog(initial: String) {
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf(initial) }
        var busy by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        val configured = QuickAdd.getInboxUri(this) != null

        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        fun submit() {
            if (busy || text.isBlank()) return
            busy = true
            scope.launch {
                val line = QuickAdd.append(this@QuickAddActivity, text)
                if (line == null) {
                    Toast.makeText(
                        this@QuickAddActivity,
                        if (!configured) "请先在 App 里设置收件箱文件" else "添加失败",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@QuickAddActivity, "已添加", Toast.LENGTH_SHORT).show()
                    TaskWidget().updateAll(this@QuickAddActivity)
                }
                finish()
            }
        }

        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text("快速添加任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("如:交房租 tomorrow ⏫") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() })
                    )
                    if (!configured) {
                        Text(
                            "尚未设置收件箱,请先在 App 主页选择一个 .md 文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "写入:" + (QuickAdd.getInboxName(this@QuickAddActivity) ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { submit() }, enabled = text.isNotBlank() && !busy) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { finish() }) { Text("取消") }
            }
        )
    }
}
