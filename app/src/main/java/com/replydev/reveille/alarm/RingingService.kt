package com.replydev.reveille.alarm

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

data class Ringing(val armed: Armed, val backup: Boolean) {
    /** When this delivery started ringing (Paper shows how long it has rung). Not part of equality. */
    val since: Instant = Instant.now()

    fun title(context: Context): String = if (backup) context.getString(R.string.ringing_backup_title, armed.label) else armed.label
}

/**
 * Rings one alarm at a time. Started by [AlarmReceiver] (or the ring guard) with [ACTION_RING]; it goes foreground
 * immediately, then claims the occurrence, rings, and re-arms. While ringing it keeps the ring guard armed, so if this
 * process dies the guard rings the occurrence again. Snooze/Dismiss stop the ringing only once persisted.
 */
class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deliveries = Mutex()
    private var pendingDeliveries = 0
    private var actionInProgress = false
    private var destroyed = false
    private var lastStartId = 0
    private var player: MediaPlayer? = null
    private var outputJob: Job? = null
    private var rampJob: Job? = null
    private var timeoutJob: Job? = null
    private var guardJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val vibrator: Vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    private val firing: AlarmFiring get() = container.firing

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_RING -> onRing(intent)
            ACTION_SNOOZE -> onAction(Action.SNOOZE)
            ACTION_DISMISS -> onAction(Action.DISMISS)
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun onRing(intent: Intent) {
        // Fail loud: if the extra is somehow unreadable, ring anyway with a generic label.
        val armed = intent.getStringExtra(EXTRA_ARMED)?.let(Armed::decode) ?: Armed("unknown", Instant.now(), getString(R.string.oneoff_default_label))
        val backup = intent.getBooleanExtra(EXTRA_BACKUP, false)
        val ringing = Ringing(armed, backup)
        val guard = intent.getBooleanExtra(EXTRA_GUARD, false)
        // startForeground first: the system requires it within seconds of startForegroundService. Until this delivery
        // is known to ring, keep the current alarm's notification, or a minimal one without a full-screen intent so a
        // duplicate never flashes the ringing screen.
        if (!enterForeground(current.value)) {
            onForegroundFailed(armed)
            return
        }
        runCatching { acquireWakeLock() }.onFailure { Log.e(TAG, "Cannot acquire wake lock", it) }
        FiringWakeLock.release() // ours is held now
        pendingDeliveries++
        scope.launch {
            try {
                deliveries.withLock { deliver(ringing, guard, backup) }
            } finally {
                pendingDeliveries--
                stopIfIdle()
            }
        }
    }

    private suspend fun deliver(ringing: Ringing, guard: Boolean, backup: Boolean) {
        val armed = ringing.armed
        if (current.value?.armed?.isSameOccurrence(armed) == true) return // backup/guard of what already rings
        if (firing.decideDelivery(armed, guard, backup) == Delivery.DUPLICATE) {
            Log.i(TAG, "Duplicate delivery of ${armed.key} (guard=$guard, backup=$backup); not ringing")
            firing.onDuplicate()
            return
        }
        startRinging(ringing)
        firing.onRingStarted(armed)
        if (current.value === ringing) startGuardRefresh(ringing)
    }

    private fun startRinging(ringing: Ringing) {
        val previous = current.value
        if (previous != null) {
            Log.w(TAG, "${ringing.armed.key} replaces ringing ${previous.armed.key}")
            stopOutput()
            timeoutJob?.cancel()
            guardJob?.cancel()
        }
        current.value = ringing
        enterForeground(ringing) // now the full notification, with the full-screen intent
        outputJob = scope.launch {
            val settings = loadSettings()
            if (current.value !== ringing) return@launch
            val sound = startSound(ringing, settings)
            if (current.value !== ringing) return@launch
            // Without sound, vibration is all this service has left: use it regardless of the setting.
            if (settings.vibrate || !sound) runCatching { startVibration() }.onFailure { Log.e(TAG, "Cannot vibrate", it) }
            scheduleAutoSnooze(ringing, RingDecisions.ringMinutes(settings.maxRingMinutes).minutes)
        }
    }

    private suspend fun loadSettings(): RingSettings = try {
        withTimeoutOrNull(3.seconds) { container.settings.current() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Cannot read settings; ringing with defaults", e)
        null
    } ?: RingSettings()

    private fun scheduleAutoSnooze(ringing: Ringing, after: kotlin.time.Duration) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(after)
            if (current.value === ringing) onAction(Action.AUTO_SNOOZE)
        }
    }

    private fun startGuardRefresh(ringing: Ringing) {
        guardJob?.cancel()
        guardJob = scope.launch {
            while (current.value === ringing) {
                delay(RingDecisions.GUARD_REFRESH.toKotlinDuration())
                if (current.value !== ringing || !firing.refreshGuard(ringing.armed)) break
            }
        }
    }

    private fun onAction(action: Action) {
        val ringing = current.value ?: return stopIfIdle()
        if (actionInProgress) return
        actionInProgress = true
        guardJob?.cancel() // so it cannot re-arm the guard the action is about to cancel
        scope.launch {
            // Persist first (best effort, never throws), then always stop: a failing disk must not leave the alarm
            // ringing forever. On failure AlarmFiring keeps it from being lost instead (guard kept / snooze armed directly).
            withContext(NonCancellable) {
                try {
                    when (action) {
                        Action.SNOOZE -> firing.snooze(ringing.armed, auto = false)
                        Action.AUTO_SNOOZE -> firing.snooze(ringing.armed, auto = true)
                        Action.DISMISS -> firing.dismiss(ringing.armed)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "$action for ${ringing.armed.key} failed", e)
                }
            }
            actionInProgress = false
            stopRinging(ringing)
        }
    }

    /**
     * Makes this service foreground with [ringing]'s full notification, or the minimal one (no full-screen intent) if
     * [ringing] is null or the full one fails. Returns false only if no notification could make it foreground.
     */
    private fun enterForeground(ringing: Ringing?): Boolean {
        if (ringing != null) {
            try {
                startForeground(Notifier.RINGING_ID, container.notifier.ringingNotification(ringing), FGS_TYPE)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed for ${ringing.armed.key}; retrying with a minimal notification", e)
            }
        }
        try {
            startForeground(Notifier.RINGING_ID, Notifier.minimalRingingNotification(this), FGS_TYPE)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground with the minimal notification failed", e)
        }
        return false
    }

    /**
     * Not foreground, so this service cannot ring. Rings through the fallback notification and arms the ring guard
     * before stopping. Residual risk: having been started with startForegroundService, the system may still crash
     * the process for not calling startForeground; the fallback notification and the guard alarm survive that.
     */
    private fun onForegroundFailed(armed: Armed) {
        runCatching { container.notifier.postFallbackAlarm(armed) }
        pendingDeliveries++
        scope.launch {
            try {
                withContext(NonCancellable) { firing.onStartFailed(armed) }
            } finally {
                FiringWakeLock.release()
                pendingDeliveries--
                stopIfIdle()
            }
        }
    }

    /**
     * Prepares the player off the main thread (prepare() does I/O), then starts it on the main thread if [ringing] is
     * still current. Returns false if no sound could be played; then the fallback notification provides it.
     */
    private suspend fun startSound(ringing: Ringing, settings: RingSettings): Boolean {
        var created: MediaPlayer? = null
        val prepared = try {
            withContext(Dispatchers.IO) { MediaPlayer().also { created = it; prepare(it, settings) } }
        } catch (e: CancellationException) {
            runCatching { created?.release() } // stopped or replaced while preparing: never leak the player
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No alarm sound could be played for ${ringing.armed.key}; posting fallback alarm", e)
            runCatching { created?.release() }
            runCatching { container.notifier.postFallbackAlarm(ringing.armed) }
            return false
        }
        if (current.value !== ringing) {
            runCatching { prepared.release() }
            return false
        }
        try {
            prepared.isLooping = true
            val startVolume = if (settings.volumeRamp) RAMP_START_VOLUME else 1f
            prepared.setVolume(startVolume, startVolume)
            prepared.start()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start alarm sound for ${ringing.armed.key}; posting fallback alarm", e)
            runCatching { prepared.release() }
            runCatching { container.notifier.postFallbackAlarm(ringing.armed) }
            return false
        }
        this.player = prepared
        if (settings.volumeRamp) {
            rampJob = scope.launch {
                for (step in 1..RAMP_STEPS) {
                    delay(1.seconds)
                    val volume = RAMP_START_VOLUME + (1f - RAMP_START_VOLUME) * step / RAMP_STEPS
                    prepared.setVolume(volume, volume)
                }
            }
        }
        return true
    }

    private fun prepare(player: MediaPlayer, settings: RingSettings) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val chosen = runCatching {
            // getDefaultUri is the settings indirection (content://settings/system/alarm_alert), resolved at play time.
            val uri = (settings.sound as? SoundRef.SystemTone)?.uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player.setAudioAttributes(attributes)
            player.setDataSource(this, uri)
            player.prepare()
        }
        if (chosen.isFailure) {
            // Media providers are unavailable before the first unlock; the bundled sound always works.
            Log.w(TAG, "Falling back to bundled alarm sound", chosen.exceptionOrNull())
            player.reset()
            player.setAudioAttributes(attributes)
            resources.openRawResourceFd(R.raw.fallback_alarm).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.prepare()
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reveille:ringing")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun stopOutput() {
        outputJob?.cancel()
        outputJob = null
        rampJob?.cancel()
        rampJob = null
        player?.run {
            runCatching { stop() }
            runCatching { release() }
        }
        player = null
        runCatching { vibrator.cancel() }
    }

    /** Stops [ringing] — only if it is still what rings (a newer alarm may have replaced it meanwhile). */
    private fun stopRinging(ringing: Ringing) {
        if (current.value !== ringing) return stopIfIdle()
        timeoutJob?.cancel()
        guardJob?.cancel()
        stopOutput()
        runCatching { container.notifier.cancelFallbackAlarm(ringing.armed) }
        current.value = null
        stopIfIdle()
    }

    /**
     * Stops the service if nothing rings or is pending. stopSelfResult(lastStartId) is a no-op when a newer start
     * (e.g. a RING from startForegroundService) is already queued: stopping then would crash the process for not
     * calling startForeground and lose that ring. The queued command calls this again when it is done.
     */
    private fun stopIfIdle() {
        if (destroyed || current.value != null || pendingDeliveries > 0 || actionInProgress) return
        if (stopSelfResult(lastStartId)) {
            wakeLock?.takeIf { it.isHeld }?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        destroyed = true
        stopOutput()
        wakeLock?.takeIf { it.isHeld }?.release()
        current.value = null // the guard rings it again if it was not handled
        scope.cancel()
        super.onDestroy()
    }

    private enum class Action { SNOOZE, AUTO_SNOOZE, DISMISS }

    companion object {
        const val ACTION_RING = "com.replydev.reveille.RING"
        const val ACTION_SNOOZE = "com.replydev.reveille.SNOOZE"
        const val ACTION_DISMISS = "com.replydev.reveille.DISMISS"
        private const val EXTRA_ARMED = "armed"
        private const val EXTRA_BACKUP = "backup"
        private const val EXTRA_GUARD = "guard"
        private const val RAMP_STEPS = 30
        private const val RAMP_START_VOLUME = 0.1f

        // Must outlive the longest ring (RingDecisions.MAX_RING_MINUTES) plus persistence time.
        private const val WAKE_LOCK_TIMEOUT_MS = 40 * 60 * 1000L
        private const val TAG = "Reveille"

        // systemExempted is API 34. On 31-33 the platform only checks the type is a subset of the manifest's
        // foregroundServiceType, which aapt compiles to the same flag value, so passing it there is harmless.
        @SuppressLint("InlinedApi")
        private const val FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED

        private val current = MutableStateFlow<Ringing?>(null)

        /** The alarm currently ringing, or null. RingingActivity closes itself when this becomes null. */
        val ringing: StateFlow<Ringing?> = current.asStateFlow()

        fun isRinging(armed: Armed): Boolean = current.value?.armed?.isSameOccurrence(armed) == true

        /**
         * Synchronously asks the system to start ringing [armed]. Returns false if the start was refused (exception or
         * null component), in which case the caller must fall back ([AlarmFiring.onStartFailed]).
         */
        fun tryStart(context: Context, armed: Armed, backup: Boolean, guard: Boolean): Boolean = try {
            context.startForegroundService(ringIntent(context, armed, backup, guard)) != null
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService refused for ${armed.key}", e)
            false
        }

        fun ringIntent(context: Context, armed: Armed, backup: Boolean, guard: Boolean): Intent =
            Intent(context, RingingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_ARMED, armed.encode())
                .putExtra(EXTRA_BACKUP, backup)
                .putExtra(EXTRA_GUARD, guard)

        fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, RingingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RingingService::class.java).setAction(action))
        }
    }
}
