package dev.local.taskwidget.widget

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.work.BadgeUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 刷新所有类型的 widget(任务清单 + 4 种日历类)并更新桌面角标 */
suspend fun updateAllWidgets(context: Context) {
    TaskWidget().updateAll(context)
    UpNextWidget().updateAll(context)
    DailyAgendaWidget().updateAll(context)
    MonthMiniWidget().updateAll(context)
    MonthAgendaWidget().updateAll(context)
    BadgeUpdater.update(context)
}

/** 勾选复选框:写回 markdown 文件并刷新 widget */
class CompleteTaskAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val fileUri = parameters[KEY_FILE_URI] ?: return
        val rawLine = parameters[KEY_RAW_LINE] ?: return

        val ok = VaultRepository.completeTask(context, fileUri, rawLine)
        if (!ok) {
            // 找不到原行:只增量重解析这一个文件(不做整库慢扫描),让 widget 立即回到真实状态
            VaultRepository.noteFileChanged(context, Uri.parse(fileUri))
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "该任务已变化,已刷新", Toast.LENGTH_SHORT).show()
            }
        }
        updateAllWidgets(context)
    }

    companion object {
        val KEY_FILE_URI = ActionParameters.Key<String>("fileUri")
        val KEY_RAW_LINE = ActionParameters.Key<String>("rawLine")

        fun params(task: TaskItem) = actionParametersOf(
            KEY_FILE_URI to task.fileUri,
            KEY_RAW_LINE to task.rawLine,
        )
    }
}

/** 手动刷新:重新扫描 vault 并刷新所有 widget */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        VaultRepository.scan(context)
        updateAllWidgets(context)
    }
}
