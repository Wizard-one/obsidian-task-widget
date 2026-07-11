package dev.local.taskwidget

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.data.QuickAdd
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.TaskWidget
import dev.local.taskwidget.work.RefreshWorker
import dev.local.taskwidget.work.ReminderScheduler
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedule(this)
        ReminderScheduler.rescheduleAll(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val scope = rememberCoroutineScope()
        var vaultUri by remember { mutableStateOf(VaultRepository.getVaultUri(this)) }
        var inboxName by remember { mutableStateOf(QuickAdd.getInboxName(this)) }
        var taskCount by remember { mutableStateOf(VaultRepository.loadTasks(this).size) }
        var lastScan by remember { mutableStateOf(VaultRepository.getLastScanTime(this)) }
        var scanning by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var digestOn by remember { mutableStateOf(ReminderScheduler.isDigestEnabled(this)) }

        val notifPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 用户选择后无需额外处理 */ }

        val inboxPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "inbox.md"
                QuickAdd.setInbox(this, uri, name)
                inboxName = name
            }
        }

        fun rescan() {
            scope.launch {
                scanning = true
                message = null
                val count = VaultRepository.scan(this@MainActivity)
                if (count == null) {
                    message = "扫描失败:无法访问所选文件夹,请重新选择"
                } else {
                    taskCount = VaultRepository.loadTasks(this@MainActivity).size
                }
                lastScan = VaultRepository.getLastScanTime(this@MainActivity)
                TaskWidget().updateAll(this@MainActivity)
                scanning = false
            }
        }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                VaultRepository.setVaultUri(this, uri)
                vaultUri = uri
                rescan()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("任务小组件", style = MaterialTheme.typography.headlineMedium)
            Text(
                "在主屏幕 widget 上直接查看、勾选完成 Obsidian vault 里的任务," +
                    "改动直接写回 markdown 文件(兼容 Obsidian Tasks 插件格式)。",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = { startActivity(Intent(this@MainActivity, TaskListActivity::class.java)) },
                enabled = vaultUri != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("浏览全部任务")
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Vault 文件夹", style = MaterialTheme.typography.titleMedium)
                    Text(
                        vaultUri?.let { friendlyFolderName(it) } ?: "尚未选择",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = { folderPicker.launch(null) }) {
                        Text(if (vaultUri == null) "选择 Vault 文件夹" else "更换文件夹")
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("状态", style = MaterialTheme.typography.titleMedium)
                    Text("待办任务:$taskCount", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "上次扫描:" + if (lastScan > 0)
                            DateFormat.format("MM-dd HH:mm", Date(lastScan)) else "从未",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (scanning) {
                        CircularProgressIndicator()
                    } else {
                        OutlinedButton(onClick = { rescan() }, enabled = vaultUri != null) {
                            Text("重新扫描")
                        }
                    }
                    message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("快速添加(收件箱)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "选一个 .md 文件作为收件箱;之后从 widget ➕、下拉磁贴、分享文本、" +
                            "选中文字都能快速把任务追加进去(支持自然语言日期,如“交房租 明天”)。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("当前:" + (inboxName ?: "尚未选择"), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { inboxPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) {
                            Text(if (inboxName == null) "选择收件箱文件" else "更换")
                        }
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@MainActivity, QuickAddActivity::class.java)) },
                            enabled = inboxName != null
                        ) {
                            Text("试试添加")
                        }
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("提醒", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("每日早晨摘要通知", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = digestOn,
                            onCheckedChange = { on ->
                                digestOn = on
                                ReminderScheduler.setDigest(this@MainActivity, on, ReminderScheduler.getDigestHour(this@MainActivity))
                                if (on && Build.VERSION.SDK_INT >= 33) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                    Text(
                        "每天 8:00 汇总“今天到期 + 已过期”任务数;午夜自动刷新 widget。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("使用说明", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. 选好 Vault 文件夹后,长按主屏幕空白处 → 小部件 → 添加“任务清单”,添加时可设置筛选(日期范围 / #标签 / 路径)。\n" +
                            "2. 点 widget 上的齿轮可随时改筛选;可添加多个 widget,各自不同筛选。\n" +
                            "3. 点任务文字可编辑内容、截止日期、优先级;勾选完成会写入 ✅ 日期。\n" +
                            "4. widget 每 30 分钟自动扫描一次,也可点右上角手动刷新。\n" +
                            "5. 支持语法:📅 截止日期、🔺⏫🔼🔽⏬ 优先级、#标签。\n" +
                            "6. 暂不支持 🔁 循环任务(勾选只标记完成,不生成下一次)。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    private fun friendlyFolderName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast(':')?.ifEmpty { uri.toString() }
            ?: uri.toString()
}
