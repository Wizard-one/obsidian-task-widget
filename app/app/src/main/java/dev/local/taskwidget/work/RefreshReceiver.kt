package dev.local.taskwidget.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.TaskWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新 / 时区变化 / 手动改时间 后:重扫 vault、刷新 widget、重排定时任务与提醒。
 */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VaultRepository.scan(context)
                TaskWidget().updateAll(context)
                RefreshWorker.schedule(context)
                ReminderScheduler.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
