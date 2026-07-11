package dev.local.taskwidget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** 下拉通知栏的"搜索任务"磁贴,打开任务列表并聚焦搜索框 */
class SearchTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, TaskListActivity::class.java)
            .putExtra(TaskListActivity.EXTRA_FOCUS_SEARCH, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
