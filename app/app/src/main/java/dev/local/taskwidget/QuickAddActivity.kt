package dev.local.taskwidget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.local.taskwidget.data.Priority
import dev.local.taskwidget.data.QuickAdd
import dev.local.taskwidget.ui.AppTheme
import dev.local.taskwidget.widget.updateAllWidgets
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 轻量"快速添加"对话框 Activity。入口:桌面 widget ➕、快捷设置磁贴、分享文本、选词。
 * 支持截止日期 / 开始日期 / 优先级选择与自然语言日期解析。
 */
class QuickAddActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = extractSharedText(intent)
        setContent {
            AppTheme { QuickAddDialog(initial = shared) }
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun QuickAddDialog(initial: String) {
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf(initial) }
        var due by remember { mutableStateOf<LocalDate?>(null) }
        var start by remember { mutableStateOf<LocalDate?>(null) }
        var priority by remember { mutableStateOf(Priority.NONE) }
        var busy by remember { mutableStateOf(false) }
        var picking by remember { mutableStateOf<String?>(null) } // "due" / "start" / null
        val focus = remember { FocusRequester() }
        val configured = QuickAdd.getInboxUri(this) != null

        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        fun submit() {
            if (busy || text.isBlank()) return
            busy = true
            scope.launch {
                val line = QuickAdd.append(this@QuickAddActivity, text, due, start, priority)
                if (line == null) {
                    Toast.makeText(
                        this@QuickAddActivity,
                        if (!configured) "请先在 App 里设置收件箱文件" else "添加失败",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@QuickAddActivity, "已添加", Toast.LENGTH_SHORT).show()
                    updateAllWidgets(this@QuickAddActivity)
                }
                finish()
            }
        }

        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text("快速添加任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("如:交房租 tomorrow") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() })
                    )

                    // 日期选择
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { picking = "due" },
                            label = { Text(due?.let { "📅 ${it.monthValue}/${it.dayOfMonth}" } ?: "📅 截止") }
                        )
                        AssistChip(
                            onClick = { picking = "start" },
                            label = { Text(start?.let { "🛫 ${it.monthValue}/${it.dayOfMonth}" } ?: "🛫 开始") }
                        )
                        if (due != null || start != null) {
                            TextButton(onClick = { due = null; start = null }) { Text("清除") }
                        }
                    }

                    // 优先级
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        priorityChoice("无", Priority.NONE, priority) { priority = it }
                        priorityChoice("🔺", Priority.HIGHEST, priority) { priority = it }
                        priorityChoice("⏫", Priority.HIGH, priority) { priority = it }
                        priorityChoice("🔼", Priority.MEDIUM, priority) { priority = it }
                        priorityChoice("🔽", Priority.LOW, priority) { priority = it }
                        priorityChoice("⏬", Priority.LOWEST, priority) { priority = it }
                    }

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

        if (picking != null) {
            val target = picking!!
            val initialMillis = ((if (target == "due") due else start) ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            if (target == "due") due = d else start = d
                        }
                        picking = null
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } }
            ) { DatePicker(state = state) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun priorityChoice(label: String, value: Priority, current: Priority, onSelect: (Priority) -> Unit) {
        FilterChip(selected = current == value, onClick = { onSelect(value) }, label = { Text(label) })
    }
}
