package dev.local.taskwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.local.taskwidget.data.CompletionShade
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.updateAllWidgets
import kotlinx.coroutines.launch

/**
 * 透明中转 Activity:承接 widget 的"完成/刷新"点击。
 *
 * 为什么不用 Glance 的 actionRunCallback:那是后台广播执行,许多国产 ROM 在 app 不在前台时
 * 会拦截后台广播,导致 widget 点击"没反应"。改为启动 Activity(不受此限)来可靠执行动作。
 * 本 Activity 无界面(透明主题),做完即 finish。
 */
class WidgetActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appCtx = applicationContext
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_COMPLETE -> {
                val fileUri = intent.getStringExtra(EXTRA_FILE_URI)
                val rawLine = intent.getStringExtra(EXTRA_RAW_LINE)
                if (fileUri == null || rawLine == null) { finish(); return }
                val id = CompletionShade.idOf(fileUri, rawLine)
                CompletionShade.add(appCtx, id) // 乐观隐藏
                lifecycleScope.launch {
                    updateAllWidgets(appCtx)    // 立即刷新:该行瞬时消失
                    val ok = VaultRepository.completeTask(appCtx, fileUri, rawLine)
                    if (!ok) VaultRepository.noteFileChanged(appCtx, Uri.parse(fileUri))
                    CompletionShade.remove(appCtx, id)
                    updateAllWidgets(appCtx)    // 对账
                    finish()
                }
            }
            ACTION_REFRESH -> {
                lifecycleScope.launch {
                    VaultRepository.scan(appCtx)
                    updateAllWidgets(appCtx)
                    finish()
                }
            }
            else -> finish()
        }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_FILE_URI = "fileUri"
        const val EXTRA_RAW_LINE = "rawLine"
        const val ACTION_COMPLETE = "complete"
        const val ACTION_REFRESH = "refresh"

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
