package dev.local.taskwidget.work

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.local.taskwidget.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 提醒/刷新调度:
 *  - 每天 08:00 发"今日到期 + 已过期"摘要通知(对标 TaskForge 的 morning digest)
 *  - 每天 00:01 刷新 widget(跨天后"今天/过期"状态更新)
 * 使用精确闹钟(有权限时),开机/时区变化后由 [RefreshReceiver] 重排。
 */
object ReminderScheduler {

    const val CHANNEL_ID = "reminders"
    const val ACTION_DIGEST = "dev.local.taskwidget.DIGEST"
    const val ACTION_MIDNIGHT = "dev.local.taskwidget.MIDNIGHT"

    private const val RC_DIGEST = 1001
    private const val RC_MIDNIGHT = 1002

    private const val PREFS = "settings"
    private const val KEY_DIGEST_ENABLED = "digest_enabled"
    private const val KEY_DIGEST_HOUR = "digest_hour"

    fun isDigestEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DIGEST_ENABLED, true)

    fun getDigestHour(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DIGEST_HOUR, 8)

    fun setDigest(context: Context, enabled: Boolean, hour: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DIGEST_ENABLED, enabled).putInt(KEY_DIGEST_HOUR, hour).apply()
        rescheduleAll(context)
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }
    }

    fun rescheduleAll(context: Context) {
        ensureChannel(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return

        // 午夜刷新
        scheduleAt(context, am, nextTime(LocalTime.of(0, 1)), ACTION_MIDNIGHT, RC_MIDNIGHT)

        // 每日摘要
        if (isDigestEnabled(context)) {
            val hour = getDigestHour(context).coerceIn(0, 23)
            scheduleAt(context, am, nextTime(LocalTime.of(hour, 0)), ACTION_DIGEST, RC_DIGEST)
        } else {
            cancel(context, am, ACTION_DIGEST, RC_DIGEST)
        }
    }

    private fun nextTime(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var next = LocalDateTime.of(LocalDate.now(), time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun intentFor(context: Context, action: String): Intent =
        Intent(context, ReminderReceiver::class.java).setAction(action)

    private fun pending(context: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, rc, intentFor(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun scheduleAt(context: Context, am: AlarmManager, whenMs: Long, action: String, rc: Int) {
        val pi = pending(context, action, rc)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    private fun cancel(context: Context, am: AlarmManager, action: String, rc: Int) {
        am.cancel(pending(context, action, rc))
    }
}
