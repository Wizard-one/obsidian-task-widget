package dev.local.taskwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.taskwidget.data.WidgetFilter
import dev.local.taskwidget.ui.AppTheme
import dev.local.taskwidget.widget.WidgetFilterStore
import kotlinx.coroutines.launch

/**
 * widget 筛选配置页:添加 widget 时由系统弹出,长按 widget → 重新配置也会进入。
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 用户直接按返回取消时,widget 添加流程要求返回 CANCELED
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            AppTheme {
                Scaffold(
                    topBar = { CenterAlignedTopAppBar(title = { Text("Widget 筛选设置") }) }
                ) { padding -> ConfigScreen(padding) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun ConfigScreen(contentPadding: androidx.compose.foundation.layout.PaddingValues) {
        val scope = rememberCoroutineScope()
        val initial = remember { WidgetFilterStore.load(this, appWidgetId) }

        var title by remember { mutableStateOf(initial.title) }
        var dateScope by remember { mutableStateOf(initial.dateScope) }
        var includeUndated by remember { mutableStateOf(initial.includeUndated) }
        var tagsInput by remember { mutableStateOf(initial.tags.joinToString(", ")) }
        var pathContains by remember { mutableStateOf(initial.pathContains) }
        var pathExcludes by remember { mutableStateOf(initial.excludePaths.joinToString(", ")) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Column {
                Text("日期范围", style = MaterialTheme.typography.titleMedium)
                ScopeOption("全部任务", WidgetFilter.SCOPE_ALL, dateScope) { dateScope = it }
                ScopeOption("今天 + 已过期", WidgetFilter.SCOPE_TODAY, dateScope) { dateScope = it }
                ScopeOption("7 天内 + 已过期", WidgetFilter.SCOPE_WEEK, dateScope) { dateScope = it }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示无日期任务", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = includeUndated, onCheckedChange = { includeUndated = it })
            }

            OutlinedTextField(
                value = tagsInput,
                onValueChange = { tagsInput = it },
                label = { Text("标签筛选(逗号分隔,可带 #,留空不筛)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = pathContains,
                onValueChange = { pathContains = it },
                label = { Text("路径包含(如 工作/ 或 Tasks.md,留空不筛)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = pathExcludes,
                onValueChange = { pathExcludes = it },
                label = { Text("排除路径(逗号分隔,如 Templates/, Archive/)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val filter = WidgetFilter(
                        title = title.trim().ifEmpty { "任务" },
                        dateScope = dateScope,
                        includeUndated = includeUndated,
                        tags = WidgetFilter.parseTagsInput(tagsInput),
                        pathContains = pathContains.trim(),
                        excludePaths = WidgetFilter.parsePathsInput(pathExcludes),
                    )
                    WidgetFilterStore.save(this@WidgetConfigActivity, appWidgetId, filter)
                    scope.launch {
                        dev.local.taskwidget.widget.TaskWidgetReceiver
                            .renderAll(this@WidgetConfigActivity)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        )
                        finish()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ScopeOption(label: String, value: Int, current: Int, onSelect: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = current == value, onClick = { onSelect(value) })
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
