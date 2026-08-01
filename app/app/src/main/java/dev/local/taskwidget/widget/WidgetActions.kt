package dev.local.taskwidget.widget

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.data.CompletionShade
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

        // 1) 乐观隐藏:先把这条标记为隐藏并立刻刷新,行瞬间消失(不等文件 IO)
        val shadeId = CompletionShade.idOf(fileUri, rawLine)
        CompletionShade.add(context, shadeId)
        updateAllWidgets(context)

        // 2) 后台真正写回文件 + 更新缓存(慢操作移出反馈关键路径)
        val ok = VaultRepository.completeTask(context, fileUri, rawLine)
        if (!ok) {
            VaultRepository.noteFileChanged(context, Uri.parse(fileUri))
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "该任务已变化,已刷新", Toast.LENGTH_SHORT).show()
            }
        }

        // 3) 清除隐藏标记并二次刷新对账(此时缓存已不含该任务,行保持消失)
        CompletionShade.remove(context, shadeId)
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
