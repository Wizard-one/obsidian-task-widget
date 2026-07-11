package dev.local.taskwidget.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import dev.local.taskwidget.MainActivity
import dev.local.taskwidget.R
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.TaskWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 收到午夜/摘要闹钟:刷新数据与 widget;摘要时发通知。然后重排下一次。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VaultRepository.scan(context)
                TaskWidget().updateAll(context)
                if (action == ReminderScheduler.ACTION_DIGEST) {
                    postDigest(context)
                }
            } finally {
                ReminderScheduler.rescheduleAll(context)
                pending.finish()
            }
        }
    }

    private fun postDigest(context: Context) {
        val today = LocalDate.now()
        val tasks = VaultRepository.loadTasks(context)
        val dueToday = tasks.count { it.due == today }
        val overdue = tasks.count { it.due?.isBefore(today) == true }
        if (dueToday == 0 && overdue == 0) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ReminderScheduler.ensureChannel(context)
        val title = "今日任务"
        val text = buildString {
            if (dueToday > 0) append("$dueToday 个今天到期")
            if (overdue > 0) {
                if (isNotEmpty()) append(" · ")
                append("$overdue 个已过期")
            }
        }
        val tapIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(2001, notif)
    }
}
