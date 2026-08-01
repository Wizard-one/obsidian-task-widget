package dev.local.taskwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.local.taskwidget.data.CompletionShade
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.updateAllWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 透明中转 Activity:承接 widget 的"完成/刷新"点击。
 *
 * 为什么不用 Glance actionRunCallback:那是后台广播执行,许多国产 ROM 在 app 不在前台时
 * 拦截后台广播,导致 widget 点击"没反应"。改为启动 Activity(不受此限)。
 *
 * 关键:实际工作放在**独立的进程级协程**里,并立刻 finish()。否则透明无界面 Activity 可能
 * 被系统立即销毁,绑定在 Activity 生命周期上的协程会被取消,动作跑不完(表现为"点了没用")。
 * 每个动作弹 Toast 确认,便于确认点击是否命中、动作是否执行。
 */
class WidgetActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appCtx = applicationContext
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_COMPLETE -> {
                val fileUri = intent.getStringExtra(EXTRA_FILE_URI)
                val rawLine = intent.getStringExtra(EXTRA_RAW_LINE)
                if (fileUri != null && rawLine != null) {
                    val id = CompletionShade.idOf(fileUri, rawLine)
                    CompletionShade.add(appCtx, id) // 乐观隐藏
                    scope.launch {
                        updateAllWidgets(appCtx)    // 立即刷新:该行瞬时消失
                        val ok = VaultRepository.completeTask(appCtx, fileUri, rawLine)
                        if (!ok) VaultRepository.noteFileChanged(appCtx, Uri.parse(fileUri))
                        CompletionShade.remove(appCtx, id)
                        updateAllWidgets(appCtx)
                        toast(appCtx, if (ok) "已完成" else "已刷新")
                    }
                }
                finish()
            }
            ACTION_REFRESH -> {
                scope.launch {
                    VaultRepository.scan(appCtx)
                    updateAllWidgets(appCtx)
                    toast(appCtx, "已刷新")
                }
                finish()
            }
            else -> finish()
        }
    }

    companion object {
        // 进程级协程作用域:不随 Activity 销毁而取消,保证动作跑完
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val EXTRA_ACTION = "action"
        const val EXTRA_FILE_URI = "fileUri"
        const val EXTRA_RAW_LINE = "rawLine"
        const val ACTION_COMPLETE = "complete"
        const val ACTION_REFRESH = "refresh"

        private suspend fun toast(context: Context, msg: String) = withContext(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        fun completeIntent(context: Context, fileUri: String, rawLine: String): Intent =
            Intent(context, WidgetActionActivity::class.java)
                .putExtra(EXTRA_ACTION, ACTION_COMPLETE)
                .putExtra(EXTRA_FILE_URI, fileUri)
                .putExtra(EXTRA_RAW_LINE, rawLine)
                .setData(Uri.parse("taskwidget://complete/${fileUri.hashCode()}/${rawLine.hashCode()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        fun refreshIntent(context: Context): Intent =
            Intent(context, WidgetActionActivity::class.java)
                .putExtra(EXTRA_ACTION, ACTION_REFRESH)
                .setData(Uri.parse("taskwidget://refresh"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
