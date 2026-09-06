package dev.local.taskwidget

import android.net.Uri
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.local.taskwidget.data.ObsidianLink
import dev.local.taskwidget.data.Priority
import dev.local.taskwidget.data.TaskParser
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.ui.AppTheme
import dev.local.taskwidget.widget.updateAllWidgets
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 从 widget 点击任务进入的编辑页:改文本、截止/开始日期、优先级,保存写回 markdown。
 */
class EditTaskActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FILE_URI = "fileUri"
        const val EXTRA_RAW_LINE = "rawLine"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fileUri = intent?.getStringExtra(EXTRA_FILE_URI)
        val rawLine = intent?.getStringExtra(EXTRA_RAW_LINE)
        val parsed = rawLine?.let { TaskParser.parseLine(it) }
        if (fileUri == null || rawLine == null || parsed == null) {
            finish()
            return
        }
        // 查出该任务的 vault 相对路径,用于"在 Obsidian 中打开"
        val path = VaultRepository.loadTasks(this)
            .find { it.fileUri == fileUri && it.rawLine == rawLine }?.path ?: ""

        setContent {
            AppTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("编辑任务") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "返回")
                                }
                            },
                            actions = {
                                if (path.isNotBlank()) {
                                    IconButton(onClick = { ObsidianLink.open(this@EditTaskActivity, path) }) {
                                        Icon(painterResource(R.drawable.ic_obsidian), contentDescription = "在 Obsidian 中打开")
                                    }
                                }
                            }
                        )
                    }
                ) { padding ->
                    EditScreen(
                        padding, fileUri, rawLine, path, parsed.text,
                        parsed.dueDate, parsed.startDate, parsed.priority
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditScreen(
        contentPadding: PaddingValues,
        fileUri: String,
        rawLine: String,
        path: String,
        initialText: String,
        initialDue: LocalDate?,
        initialStart: LocalDate?,
        initialPriority: Priority,
    ) {
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf(initialText) }
        var due by remember { mutableStateOf(initialDue) }
        var start by remember { mutableStateOf(initialStart) }
        var priority by remember { mutableStateOf(initialPriority) }
        var pickingDate by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        var confirmingDelete by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("任务内容(可含 #标签)") },
                modifier = Modifier.fillMaxWidth()
            )

            if (path.isNotBlank()) {
                FilledTonalButton(
                    onClick = { ObsidianLink.open(this@EditTaskActivity, path) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(painterResource(R.drawable.ic_obsidian), contentDescription = null)
                    Text("  在 Obsidian 中打开")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("截止日期", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingDate = "due" }) {
                        Text(due?.toString() ?: "选择日期")
                    }
                    if (due != null) {
                        TextButton(onClick = { due = null }) { Text("清除") }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("开始日期", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingDate = "start" }) {
                        Text(start?.toString() ?: "选择日期")
                    }
                    if (start != null) {
                        TextButton(onClick = { start = null }) { Text("清除") }
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
                                text.trim(), due, start, priority
                            )
                            if (!ok) {
                                VaultRepository.noteFileChanged(this@EditTaskActivity, Uri.parse(fileUri))
                                Toast.makeText(
                                    this@EditTaskActivity,
                                    "该任务已变化,已刷新",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            updateAllWidgets(this@EditTaskActivity)
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

            OutlinedButton(
                onClick = { confirmingDelete = true },
                enabled = !saving,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("删除任务")
            }
        }

        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("删除任务") },
                text = { Text("将从 markdown 文件中删除这一行,无法撤销。确定?") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        if (saving) return@TextButton
                        saving = true
                        scope.launch {
                            val ok = VaultRepository.deleteTask(this@EditTaskActivity, fileUri, rawLine)
                            if (!ok) {
                                VaultRepository.noteFileChanged(this@EditTaskActivity, Uri.parse(fileUri))
                                Toast.makeText(this@EditTaskActivity, "该任务已变化,已刷新", Toast.LENGTH_SHORT).show()
                            }
                            updateAllWidgets(this@EditTaskActivity)
                            finish()
                        }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } }
            )
        }

        if (pickingDate != null) {
            val target = pickingDate!!
            val state = rememberDatePickerState(
                initialSelectedDateMillis = ((if (target == "due") due else start) ?: LocalDate.now())
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { pickingDate = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            if (target == "due") due = date else start = date
                        }
                        pickingDate = null
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { pickingDate = null }) { Text("取消") }
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
