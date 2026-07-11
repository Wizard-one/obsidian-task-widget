package dev.local.taskwidget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.data.Priority
import dev.local.taskwidget.data.TaskParser
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.TaskWidget
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 从 widget 点击任务进入的编辑页:改文本、截止日期、优先级,保存写回 markdown。
 */
class EditTaskActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FILE_URI = "fileUri"
        const val EXTRA_RAW_LINE = "rawLine"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fileUri = intent?.getStringExtra(EXTRA_FILE_URI)
        val rawLine = intent?.getStringExtra(EXTRA_RAW_LINE)
        val parsed = rawLine?.let { TaskParser.parseLine(it) }
        if (fileUri == null || rawLine == null || parsed == null) {
            finish()
            return
        }

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditScreen(fileUri, rawLine, parsed.text, parsed.dueDate, parsed.priority)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditScreen(
        fileUri: String,
        rawLine: String,
        initialText: String,
        initialDue: LocalDate?,
        initialPriority: Priority,
    ) {
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf(initialText) }
        var due by remember { mutableStateOf(initialDue) }
        var priority by remember { mutableStateOf(initialPriority) }
        var showDatePicker by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("编辑任务", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("任务内容(可含 #标签)") },
                modifier = Modifier.fillMaxWidth()
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("截止日期", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        Text(due?.toString() ?: "选择日期")
                    }
                    if (due != null) {
                        TextButton(onClick = { due = null }) { Text("清除") }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("优先级", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriorityChip("🔺 最高", Priority.HIGHEST, priority) { priority = it }
                    PriorityChip("⏫ 高", Priority.HIGH, priority) { priority = it }
                    PriorityChip("🔼 中", Priority.MEDIUM, priority) { priority = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriorityChip("无", Priority.NONE, priority) { priority = it }
                    PriorityChip("🔽 低", Priority.LOW, priority) { priority = it }
                    PriorityChip("⏬ 最低", Priority.LOWEST, priority) { priority = it }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            val ok = VaultRepository.editTask(
                                this@EditTaskActivity, fileUri, rawLine,
                                text.trim(), due, priority
                            )
                            if (!ok) {
                                Toast.makeText(
                                    this@EditTaskActivity,
                                    "保存失败:文件可能已被修改,正在重新扫描",
                                    Toast.LENGTH_SHORT
                                ).show()
                                VaultRepository.scan(this@EditTaskActivity)
                            }
                            TaskWidget().updateAll(this@EditTaskActivity)
                            finish()
                        }
                    },
                    enabled = text.isNotBlank() && !saving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (saving) "保存中…" else "保存")
                }
                OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
            }
        }

        if (showDatePicker) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = (due ?: LocalDate.now())
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        showDatePicker = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                }
            ) {
                DatePicker(state = state)
            }
        }
    }

    @Composable
    private fun PriorityChip(label: String, value: Priority, current: Priority, onSelect: (Priority) -> Unit) {
        FilterChip(
            selected = current == value,
            onClick = { onSelect(value) },
            label = { Text(label) }
        )
    }
}
