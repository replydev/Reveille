package com.replydev.reveille.update

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.replydev.reveille.container
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** What the worker does, without WorkManager, so it can be tested directly. */
class UpdateWork(
    private val store: UpdateStore,
    private val checker: UpdateChecker,
    private val installedVersionCode: Int,
    private val notify: (Release) -> Unit,
) {
    suspend fun daily(): CheckOutcome = checker.check(manual = false)

    /** "Remind me" after an alarm: re-posts the stored release (no network), unless it was skipped or installed. */
    suspend fun remind() {
        val prefs = store.current()
        store.setRemindAfter(null)
        val release = prefs.available(installedVersionCode) ?: return
        if (release.versionCode != prefs.skippedVersionCode) notify(release)
    }
}

/** The daily update check (network required) and the one-off "Remind me" job. Never runs before the first unlock. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updates = applicationContext.container.updates
        if (!updates.selfUpdate) return Result.success()
        try {
            if (inputData.getBoolean(KEY_REMIND, false)) updates.work.remind() else updates.work.daily()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Success anyway: tomorrow's run tries again, and an update check is never worth a retry pile-up.
            Log.e("Reveille", "Update check failed", e)
        }
        return Result.success()
    }

    companion object {
        const val PERIODIC = "update_check"
        const val REMIND = "update_remind"
        private const val KEY_REMIND = "remind"

        /** Enqueues the daily check while [autoCheck] is on (kept if already enqueued), cancels it otherwise. */
        fun sync(context: Context, autoCheck: Boolean) = withWorkManager(context) { workManager ->
            if (!autoCheck) {
                workManager.cancelUniqueWork(PERIODIC)
                return@withWorkManager
            }
            workManager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /** A one-off reminder at [at], replacing any earlier one. */
        fun scheduleRemind(context: Context, at: Instant, now: Instant = Instant.now()) = withWorkManager(context) {
            val delay = Duration.between(now, at).coerceAtLeast(Duration.ZERO)
            it.enqueueUniqueWork(
                REMIND,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UpdateWorker>()
                    .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_REMIND to true))
                    .build(),
            )
        }

        fun cancelRemind(context: Context) = withWorkManager(context) { it.cancelUniqueWork(REMIND) }

        /**
         * WorkManager's database lives in credential-encrypted storage: touching it before the first unlock would
         * crash a direct-boot process, so everything here is a no-op until then.
         */
        private inline fun withWorkManager(context: Context, block: (WorkManager) -> Unit) {
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
            try {
                block(WorkManager.getInstance(context))
            } catch (e: Exception) {
                Log.e("Reveille", "Could not schedule the update check", e)
            }
        }
    }
}
