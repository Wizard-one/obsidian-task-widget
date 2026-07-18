package dev.local.taskwidget.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.updateAllWidgets
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
                // 先用缓存立即重绘 widget(开机后组件常处于"加载中"卡住,
                // 若等慢扫描完再刷、goAsync 又被系统回收,就一直不激活)
                updateAllWidgets(context)
                RefreshWorker.schedule(context)
                ReminderScheduler.rescheduleAll(context)
                // 再做整库扫描,拿到最新内容后二次刷新
                VaultRepository.scan(context)
                updateAllWidgets(context)
            } finally {
                pending.finish()
            }
        }
    }
}
