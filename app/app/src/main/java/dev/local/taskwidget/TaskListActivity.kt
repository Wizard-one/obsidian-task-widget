package dev.local.taskwidget

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.local.taskwidget.data.ObsidianLink
import dev.local.taskwidget.data.Priority
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.updateAllWidgets
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * App 内任务浏览页,三视图:列表(搜索/分组/排序)、看板(按到期分栏)、日历(月历+当日)。
 * 由 MainActivity 与 Search 磁贴进入。
 */
class TaskListActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FOCUS_SEARCH = "focus_search"
    }

    private enum class ViewMode(val label: String) { LIST("列表"), KANBAN("看板"), CALENDAR("日历") }
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
        var view by remember { mutableStateOf(ViewMode.LIST) }
        val searchFocus = remember { FocusRequester() }
        val focusSearch = intent?.getBooleanExtra(EXTRA_FOCUS_SEARCH, false) == true
        LaunchedEffect(Unit) { if (focusSearch) runCatching { searchFocus.requestFocus() } }

        fun reload() { tasks = VaultRepository.loadTasks(this) }

        // 每次回到本页(如从快速添加/编辑返回)都从缓存重载,新增/改动的任务立即出现
        DisposableEffect(Unit) {
            val obs = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) reload()
            }
            this@TaskListActivity.lifecycle.addObserver(obs)
            onDispose { this@TaskListActivity.lifecycle.removeObserver(obs) }
        }

        // 打开列表时后台重扫一次 vault(SAF 无法实时推送,以"进入即刷新"作为等效同步)
        LaunchedEffect(Unit) {
            VaultRepository.scan(this@TaskListActivity)
            reload()
        }
        fun refresh() {
            scope.launch {
                VaultRepository.scan(this@TaskListActivity); reload(); updateAllWidgets(this@TaskListActivity)
            }
        }
        fun complete(task: TaskItem) {
            scope.launch {
                VaultRepository.completeTask(this@TaskListActivity, task.fileUri, task.rawLine)
                reload(); updateAllWidgets(this@TaskListActivity)
            }
        }
        fun delete(task: TaskItem) {
            scope.launch {
                VaultRepository.deleteTask(this@TaskListActivity, task.fileUri, task.rawLine)
                reload(); updateAllWidgets(this@TaskListActivity)
            }
        }
        fun edit(task: TaskItem) {
            startActivity(
                Intent(this, EditTaskActivity::class.java)
                    .putExtra(EditTaskActivity.EXTRA_FILE_URI, task.fileUri)
                    .putExtra(EditTaskActivity.EXTRA_RAW_LINE, task.rawLine)
            )
        }
        var actionTask by remember { mutableStateOf<TaskItem?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("任务 (${tasks.size})") },
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
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    ViewMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = view == m,
                            onClick = { view = m },
                            shape = SegmentedButtonDefaults.itemShape(i, ViewMode.entries.size)
                        ) { Text(m.label) }
                    }
                }
                if (view != ViewMode.CALENDAR) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("排序", style = MaterialTheme.typography.labelMedium)
                        SortMode.entries.forEach { m ->
                            FilterChip(selected = sort == m, onClick = { sort = m }, label = { Text(m.label) })
                        }
                    }
                }
                val onLong: (TaskItem) -> Unit = { actionTask = it }
                when (view) {
                    ViewMode.LIST -> ListView(tasks, query, { query = it }, sort, searchFocus, ::complete, ::edit, onLong)
                    ViewMode.KANBAN -> KanbanView(tasks, sort, ::complete, ::edit, onLong)
                    ViewMode.CALENDAR -> CalendarView(tasks, ::complete, ::edit, onLong)
                }
            }
        }

        actionTask?.let { t ->
            AlertDialog(
                onDismissRequest = { actionTask = null },
                title = { Text(t.text, maxLines = 2) },
                text = { Text("选择对该任务的操作") },
                confirmButton = {
                    TextButton(onClick = { delete(t); actionTask = null }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row {
                        if (t.path.isNotBlank()) {
                            TextButton(onClick = { ObsidianLink.open(this@TaskListActivity, t.path); actionTask = null }) {
                                Text("在 Obsidian 打开")
                            }
                        }
                        TextButton(onClick = { actionTask = null }) { Text("取消") }
                    }
                }
            )
        }
    }

    // ---------------- 列表视图 ----------------

    @Composable
    private fun ListView(
        tasks: List<TaskItem>,
        query: String,
        onQuery: (String) -> Unit,
        sort: SortMode,
        searchFocus: FocusRequester,
        onComplete: (TaskItem) -> Unit,
        onEdit: (TaskItem) -> Unit,
        onLong: (TaskItem) -> Unit,
    ) {
        val filtered = tasks.filter {
            query.isBlank() || it.text.contains(query, true) ||
                it.tags.any { t -> t.contains(query, true) } || it.path.contains(query, true)
        }
        val sorted = when (sort) {
            SortMode.DUE -> filtered.sortedWith(compareBy({ it.due ?: LocalDate.MAX }, { it.priorityOrder }))
            SortMode.PRIORITY -> filtered.sortedWith(compareBy({ it.priorityOrder }, { it.due ?: LocalDate.MAX }))
        }
        val grouped = groupByDue(sorted)

        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                placeholder = { Text("搜索任务 / #标签 / 路径") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).focusRequester(searchFocus)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                grouped.forEach { (header, items) ->
                    item(key = "h_$header") { GroupHeader(header, items.size) }
                    items(items, key = { it.fileUri + it.rawLine }) { task ->
                        TaskRow(task, { onComplete(task) }, { onEdit(task) }, { onLong(task) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // ---------------- 看板视图 ----------------

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun KanbanView(tasks: List<TaskItem>, sort: SortMode, onComplete: (TaskItem) -> Unit, onEdit: (TaskItem) -> Unit, onLong: (TaskItem) -> Unit) {
        val sorted = when (sort) {
            SortMode.DUE -> tasks.sortedWith(compareBy({ it.due ?: LocalDate.MAX }, { it.priorityOrder }))
            SortMode.PRIORITY -> tasks.sortedWith(compareBy({ it.priorityOrder }, { it.due ?: LocalDate.MAX }))
        }
        val columns = groupByDue(sorted)
        Row(
            modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            columns.forEach { (header, items) ->
                Column(modifier = Modifier.width(260.dp).fillMaxSize()) {
                    Text(
                        "$header · ${items.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp)
                    )
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        items.forEach { task ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .combinedClickable(onClick = { onEdit(task) }, onLongClick = { onLong(task) })) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = false, onCheckedChange = { onComplete(task) })
                                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                        Text(priorityPrefix(task.priorityOrder) + task.text, fontSize = 14.sp)
                                        TaskDateText(task)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 日历视图 ----------------

    @Composable
    private fun CalendarView(tasks: List<TaskItem>, onComplete: (TaskItem) -> Unit, onEdit: (TaskItem) -> Unit, onLong: (TaskItem) -> Unit) {
        var month by remember { mutableStateOf(YearMonth.now()) }
        var selected by remember { mutableStateOf(LocalDate.now()) }
        val today = LocalDate.now()
        val byDate = tasks.filter { it.actualDue != null }.groupBy { it.actualDue!! }

        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("‹", fontSize = 22.sp, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(horizontal = 10.dp))
                    Text("今", fontSize = 16.sp, modifier = Modifier.clickable { month = YearMonth.now(); selected = today }.padding(horizontal = 8.dp))
                    Text("›", fontSize = 22.sp, modifier = Modifier.clickable { month = month.plusMonths(1) }.padding(horizontal = 10.dp))
                }
            }
            // 星期表头
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            // 网格
            val first = month.atDay(1)
            val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
            for (week in 0 until 6) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (dow in 0 until 7) {
                        val d = gridStart.plusDays((week * 7 + dow).toLong())
                        val count = byDate[d]?.size ?: 0
                        val bg = when {
                            d == selected -> MaterialTheme.colorScheme.primaryContainer
                            d == today -> MaterialTheme.colorScheme.secondaryContainer
                            else -> Color.Transparent
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(1.dp).clickable { selected = d }
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                d.dayOfMonth.toString(),
                                fontSize = 13.sp,
                                fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (d.month == month.month) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.background(bg).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Text(if (count > 0) "•" else " ", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text("${selected.monthValue}月${selected.dayOfMonth}日", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            val dayTasks = (byDate[selected] ?: emptyList()).sortedBy { it.priorityOrder }
            if (dayTasks.isEmpty()) {
                Text("当天无任务", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(dayTasks, key = { it.fileUri + it.rawLine }) { task ->
                        TaskRow(task, { onComplete(task) }, { onEdit(task) }, { onLong(task) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // ---------------- 共享小组件 ----------------

    @Composable
    private fun GroupHeader(title: String, count: Int) {
        Text(
            "$title · $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TaskRow(task: TaskItem, onComplete: () -> Unit, onEdit: () -> Unit, onLong: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(onClick = { onEdit() }, onLongClick = { onLong() })
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = false, onCheckedChange = { onComplete() })
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(priorityPrefix(task.priorityOrder) + task.text, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskDateText(task)
                    if (task.tags.isNotEmpty()) {
                        Text(task.tags.joinToString(" ") { "#$it" }, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    private fun DueText(due: LocalDate) {
        Text(
            dueLabel(due), fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = if (due.isBefore(LocalDate.now())) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    @Composable
    private fun TaskDateText(task: TaskItem) {
        task.actualDue?.let { DueText(it) }
            ?: task.start?.let { start ->
                Text(
                    "🛫 ${start.monthValue}/${start.dayOfMonth}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
    }

    private fun groupByDue(tasks: List<TaskItem>): List<Pair<String, List<TaskItem>>> {
        val today = LocalDate.now()
        val order = listOf("已过期", "今天", "明天", "本周", "以后", "已开始", "无日期")
        val buckets = tasks.groupBy { t ->
            val d = t.due
            when {
                d == null -> "无日期"
                t.actualDue?.isBefore(today) == true -> "已过期"
                t.actualDue == null && t.start?.isBefore(today) == true -> "已开始"
                d.isBefore(today) -> "以后"
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
