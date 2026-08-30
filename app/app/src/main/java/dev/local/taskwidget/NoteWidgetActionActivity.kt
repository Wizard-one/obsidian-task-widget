package dev.local.taskwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.local.taskwidget.data.NoteRepository
import dev.local.taskwidget.data.ObsidianLink
import dev.local.taskwidget.widget.NoteWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 透明中转 Activity:处理笔记 widget 的打开与刷新动作。 */
class NoteWidgetActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_OPEN -> {
                val uri = intent.getStringExtra(EXTRA_NOTE_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (uri != null) {
                    scope.launch {
                        val path = NoteRepository.findVaultRelativePath(appContext, uri)
                        val opened = withContext(Dispatchers.Main) {
                            ObsidianLink.openNoteOrFile(appContext, path, uri)
                        }
                        if (!opened) toast(appContext, "未找到可打开 Markdown 的应用")
                    }
                }
                finish()
            }
            ACTION_REFRESH -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                scope.launch {
                    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                        NoteWidgetReceiver.renderAll(appContext)
                    } else {
                        NoteWidgetReceiver.render(appContext, intArrayOf(appWidgetId))
                    }
                    toast(appContext, "已刷新")
                }
                finish()
            }
            else -> finish()
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val EXTRA_ACTION = "noteAction"
        const val EXTRA_NOTE_URI = "noteUri"
        const val ACTION_OPEN = "openNote"
        const val ACTION_REFRESH = "refreshNotes"

        fun refreshIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, NoteWidgetActionActivity::class.java)
                .putExtra(EXTRA_ACTION, ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("taskwidget://notes/refresh/$appWidgetId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        private suspend fun toast(context: Context, message: String) = withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
