package dev.local.taskwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import dev.local.taskwidget.data.CompletionShade
import dev.local.taskwidget.data.TaskItem
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.work.BadgeUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 刷新所有类型的 widget(任务清单 + 4 种日历类)并更新桌面角标 */
suspend fun updateAllWidgets(context: Context) {
    // 路径一:以系统真实 appWidgetId 为准,逐个映射到 glanceId 再 update。
    // 比 updateAll 更可靠:updateAll 依赖 Glance 内部枚举 glanceId,HyperOS/MIUI 重启后
    // 这份映射常失效(枚举空/过期),导致"点了没反应";而系统 appWidgetId 始终有效。
    updateByRealIds(context, TaskWidgetReceiver::class.java, TaskWidget())
    updateByRealIds(context, UpNextWidgetReceiver::class.java, UpNextWidget())
    updateByRealIds(context, DailyAgendaWidgetReceiver::class.java, DailyAgendaWidget())
    updateByRealIds(context, MonthMiniWidgetReceiver::class.java, MonthMiniWidget())
    updateByRealIds(context, MonthAgendaWidgetReceiver::class.java, MonthAgendaWidget())
    // 路径二:直接发系统 APPWIDGET_UPDATE 广播给各 receiver(强制走 onUpdate → Glance 重组),兜底。
    broadcastUpdate(context, TaskWidgetReceiver::class.java)
    broadcastUpdate(context, UpNextWidgetReceiver::class.java)
    broadcastUpdate(context, DailyAgendaWidgetReceiver::class.java)
    broadcastUpdate(context, MonthMiniWidgetReceiver::class.java)
    broadcastUpdate(context, MonthAgendaWidgetReceiver::class.java)
    BadgeUpdater.update(context)
}

/** 用系统真实 appWidgetId 反查 glanceId 并强制 update,逐个 try/catch 保证互不影响 */
private suspend fun updateByRealIds(context: Context, receiver: Class<*>, widget: GlanceAppWidget) {
    try {
        val mgr = GlanceAppWidgetManager(context)
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, receiver))
        for (id in ids) {
            val glanceId = try { mgr.getGlanceIdBy(id) } catch (_: Exception) { null } ?: continue
            try { widget.update(context, glanceId) } catch (_: Exception) {}
        }
    } catch (_: Exception) {
        // 忽略:兜底路径,失败不影响主流程
    }
}

private fun broadcastUpdate(context: Context, receiver: Class<*>) {
    try {
        val component = ComponentName(context, receiver)
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(component)
        if (ids.isNotEmpty()) {
            context.sendBroadcast(
                Intent(context, receiver)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    } catch (_: Exception) {
        // 忽略:兜底路径,失败不影响主流程
    }
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
