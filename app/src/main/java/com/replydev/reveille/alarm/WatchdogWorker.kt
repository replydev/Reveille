package com.replydev.reveille.alarm

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.replydev.reveille.container
import java.util.concurrent.TimeUnit

/** Safety net: re-arms periodically in case the system dropped our alarm. */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.scheduler.rearm("watchdog")
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "watchdog"

        /**
         * Enqueues the 6-hour watchdog (kept if already enqueued). No-op before the first unlock: WorkManager's database
         * lives in credential-protected storage, and initializing it there would crash a direct-boot process.
         */
        fun schedule(context: Context) {
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<WatchdogWorker>(6, TimeUnit.HOURS).build(),
                )
            } catch (e: Exception) {
                Log.e("Reveille", "Could not schedule the watchdog", e)
            }
        }
    }
}
