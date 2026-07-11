package dev.local.taskwidget

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.data.Priority
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.TaskWidget
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * App 内任务浏览页:全库任务、搜索、按截止日期分组、按优先级/日期排序、勾选完成、点按编辑。
 * 由 MainActivity 与 Search 磁贴进入。
 */
class TaskListActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FOCUS_SEARCH = "focus_search"
    }

    private enum class SortMode(val label: String) { DUE("按日期"), PRIORITY("按优先级") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { TaskListScreen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TaskListScreen() {
        val scope = rememberCoroutineScope()
        var tasks by remember { mutableStateOf(VaultRepository.loadTasks(this)) }
        var query by remember { mutableStateOf("") }
        var sort by remember { mutableStateOf(SortMode.DUE) }
        val searchFocus = remember { FocusRequester() }
        val focusSearch = intent?.getBooleanExtra(EXTRA_FOCUS_SEARCH, false) == true
        LaunchedEffect(Unit) { if (focusSearch) runCatching { searchFocus.requestFocus() } }

        fun reload() { tasks = VaultRepository.loadTasks(this) }

        fun refresh() {
            scope.launch {
                VaultRepository.scan(this@TaskListActivity)
                reload()
                TaskWidget().updateAll(this@TaskListActivity)
            }
        }

        fun complete(task: TaskItem) {
            scope.launch {
                VaultRepository.completeTask(this@TaskListActivity, task.fileUri, task.rawLine)
                reload()
                TaskWidget().updateAll(this@TaskListActivity)
            }
        }

        val filtered = tasks.filter {
            query.isBlank() ||
                it.text.contains(query, true) ||
                it.tags.any { t -> t.contains(query, true) } ||
                it.path.contains(query, true)
        }
        val sorted = when (sort) {
            SortMode.DUE -> filtered.sortedWith(compareBy({ it.due ?: LocalDate.MAX }, { it.priorityOrder }))
            SortMode.PRIORITY -> filtered.sortedWith(compareBy({ it.priorityOrder }, { it.due ?: LocalDate.MAX }))
        }
        val grouped = groupByDue(sorted)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("全部任务 (${filtered.size})") },
                    actions = {
                        IconButton(onClick = { refresh() }) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新")
                        }
                        IconButton(onClick = { startActivity(Intent(this@TaskListActivity, QuickAddActivity::class.java)) }) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = "添加")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索任务 / #标签 / 路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .focusRequester(searchFocus)
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortMode.entries.forEach { m ->
                        FilterChip(selected = sort == m, onClick = { sort = m }, label = { Text(m.label) })
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    grouped.forEach { (header, items) ->
                        item(key = "h_$header") { GroupHeader(header, items.size) }
                        items(items, key = { it.fileUri + it.rawLine }) { task ->
                            TaskRow(task, onComplete = { complete(task) }, onEdit = {
                                startActivity(
                                    Intent(this@TaskListActivity, EditTaskActivity::class.java)
                                        .putExtra(EditTaskActivity.EXTRA_FILE_URI, task.fileUri)
                                        .putExtra(EditTaskActivity.EXTRA_RAW_LINE, task.rawLine)
                                )
                            })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun GroupHeader(title: String, count: Int) {
        Text(
            "$title · $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
        )
    }

    @Composable
    private fun TaskRow(task: TaskItem, onComplete: () -> Unit, onEdit: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onEdit() }.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = false, onCheckedChange = { onComplete() })
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(priorityPrefix(task.priorityOrder) + task.text, fontSize = 15.sp)
                val due = task.due
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (due != null) {
                        Text(
                            dueLabel(due),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (due.isBefore(LocalDate.now())) Color(0xFFD32F2F)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (task.tags.isNotEmpty()) {
                        Text(
                            task.tags.joinToString(" ") { "#$it" },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    private fun groupByDue(tasks: List<TaskItem>): List<Pair<String, List<TaskItem>>> {
        val today = LocalDate.now()
        val order = listOf("已过期", "今天", "明天", "本周", "以后", "无日期")
        val buckets = tasks.groupBy { t ->
            val d = t.due
            when {
                d == null -> "无日期"
                d.isBefore(today) -> "已过期"
                d == today -> "今天"
                d == today.plusDays(1) -> "明天"
                !d.isAfter(today.plusDays(7)) -> "本周"
                else -> "以后"
            }
        }
        return order.mapNotNull { key -> buckets[key]?.let { key to it } }
    }

    private fun priorityPrefix(order: Int): String = when (order) {
        Priority.HIGHEST.order -> "🔺 "
        Priority.HIGH.order -> "⏫ "
        Priority.MEDIUM.order -> "🔼 "
        else -> ""
    }

    private fun dueLabel(due: LocalDate): String {
        val today = LocalDate.now()
        return when {
            due.isBefore(today) -> "已过期 · ${due.monthValue}/${due.dayOfMonth}"
            due == today -> "今天"
            due == today.plusDays(1) -> "明天"
            else -> "${due.monthValue}/${due.dayOfMonth}"
        }
    }
}
