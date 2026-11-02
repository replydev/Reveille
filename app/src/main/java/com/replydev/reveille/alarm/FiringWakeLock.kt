package com.replydev.reveille.alarm

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the CPU awake from the moment an alarm broadcast arrives until [RingingService] holds its own wake lock (or the
 * receiver's fallback work is done). AlarmManager's own wake lock ends when the receiver returns, which can be before
 * the service is created. Not reference counted: repeated acquires extend it, one release ends it.
 */
object FiringWakeLock {
    private const val TIMEOUT_MS = 60_000L
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        try {
            val held = lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reveille:firing")
                .apply { setReferenceCounted(false) }
                .also { lock = it }
            held.acquire(TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e("Reveille", "Cannot acquire firing wake lock", e)
        }
    }

    @Synchronized
    fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
    }

    val isHeld: Boolean @Synchronized get() = lock?.isHeld == true
}
