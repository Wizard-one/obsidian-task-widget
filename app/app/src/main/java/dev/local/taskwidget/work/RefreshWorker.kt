package dev.local.taskwidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.local.taskwidget.data.VaultRepository
import dev.local.taskwidget.widget.updateAllWidgets
import java.util.concurrent.TimeUnit

/** 定时后台扫描 vault,让 widget 反映 Obsidian 里的最新修改 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        VaultRepository.scan(applicationContext)
        updateAllWidgets(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "vault_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
