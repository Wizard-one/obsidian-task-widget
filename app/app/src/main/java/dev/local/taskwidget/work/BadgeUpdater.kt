package dev.local.taskwidget.work

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.local.taskwidget.data.VaultRepository
import java.time.LocalDate

/**
 * 桌面角标:未完成(今天到期 + 已过期)任务数。
 * Android 无统一角标 API,这里发几家主流启动器都识别的广播(Nova/Apex 系 + Sony/三星常见格式)。
 * 不被支持的启动器会忽略,无副作用。
 */
object BadgeUpdater {

    fun update(context: Context) {
        val today = LocalDate.now()
        val count = VaultRepository.loadTasks(context).count { it.actualDue?.let { due -> !due.isAfter(today) } == true }
        val launcherClass = launcherEntryClass(context) ?: return

        // Nova / Apex / 大部分基于 anddoes 的启动器
        runCatching {
            val i = Intent("com.anddoes.launcher.COUNTER_CHANGED")
                .putExtra("package", context.packageName)
                .putExtra("count", count)
                .putExtra("class", launcherClass)
            context.sendBroadcast(i)
        }
        // Sony
        runCatching {
            val i = Intent("com.sonyericsson.home.action.UPDATE_BADGE")
                .putExtra("com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME", launcherClass)
                .putExtra("com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE", count > 0)
                .putExtra("com.sonyericsson.home.intent.extra.badge.MESSAGE", count.toString())
                .putExtra("com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME", context.packageName)
            context.sendBroadcast(i)
        }
        // 三星
        runCatching {
            val i = Intent("android.intent.action.BADGE_COUNT_UPDATE")
                .putExtra("badge_count", count)
                .putExtra("badge_count_package_name", context.packageName)
                .putExtra("badge_count_class_name", launcherClass)
            context.sendBroadcast(i)
        }
    }

    private fun launcherEntryClass(context: Context): String? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return (launch.component ?: ComponentName(context, "dev.local.taskwidget.MainActivity")).className
    }
}
