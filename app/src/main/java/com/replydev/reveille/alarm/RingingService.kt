package com.replydev.reveille.alarm

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
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class Ringing(val armed: Armed, val backup: Boolean) {
    val title: String get() = if (backup) "${armed.label} (backup)" else armed.label
}

class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var rampJob: Job? = null
    private var timeoutJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val vibrator: Vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                // Fail loud: if the extra is somehow unreadable, ring anyway with a generic label.
                val armed = intent.getStringExtra(EXTRA_ARMED)?.let(Armed::decode) ?: Armed("unknown", Instant.now(), "Alarm")
                ring(Ringing(armed, intent.getBooleanExtra(EXTRA_BACKUP, false)))
            }
            ACTION_SNOOZE -> snooze(auto = false)
            ACTION_DISMISS -> dismiss()
            else -> if (current.value == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ring(ringing: Ringing) {
        current.value = ringing
        val notification = container.notifier.ringingNotification(ringing)
        try {
            startForeground(Notifier.RINGING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed; ringing without foreground status", e)
            container.notifier.postRinging(ringing)
        }
        stopOutput()
        timeoutJob?.cancel()
        acquireWakeLock()
        scope.launch {
            val settings = container.settings.current()
            startSound(settings)
            if (settings.vibrate) startVibration()
            timeoutJob = launch {
                delay(settings.maxRingMinutes.minutes)
                snooze(auto = true)
            }
        }
    }

    private fun snooze(auto: Boolean) {
        val ringing = current.value ?: return stopRinging()
        stopOutput()
        scope.launch {
            val c = container
            val count = if (auto) c.schedulerState.autoSnoozeCount() + 1 else 0
            if (count > MAX_AUTO_SNOOZES) {
                c.schedulerState.setAutoSnoozeCount(0)
                c.scheduler.recordOutcome(ringing.armed, FireOutcome.MISSED)
                c.notifier.showMissed(ringing.armed)
            } else {
                c.schedulerState.setAutoSnoozeCount(count)
                val at = Instant.now().plus(Duration.ofMinutes(c.settings.current().snoozeMinutes.toLong()))
                c.schedulerState.setSnooze(Armed.snooze(at, ringing.armed.label))
                c.scheduler.recordOutcome(ringing.armed, if (auto) FireOutcome.AUTO_SNOOZED else FireOutcome.SNOOZED)
                c.scheduler.rearm("snoozed")
            }
            stopRinging()
        }
    }

    private fun dismiss() {
        val ringing = current.value ?: return stopRinging()
        stopOutput()
        scope.launch {
            container.schedulerState.setAutoSnoozeCount(0)
            container.scheduler.recordOutcome(ringing.armed, FireOutcome.DISMISSED)
            stopRinging()
        }
    }

    private fun startSound(settings: RingSettings) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val player = MediaPlayer()
        val chosen = runCatching {
            val uri = settings.ringtoneUri?.let(Uri::parse)
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: error("No default alarm sound")
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
        player.isLooping = true
        val startVolume = if (settings.volumeRamp) RAMP_START_VOLUME else 1f
        player.setVolume(startVolume, startVolume)
        player.start()
        this.player = player
        if (settings.volumeRamp) {
            rampJob = scope.launch {
                for (step in 1..RAMP_STEPS) {
                    delay(1.seconds)
                    val volume = RAMP_START_VOLUME + (1f - RAMP_START_VOLUME) * step / RAMP_STEPS
                    player.setVolume(volume, volume)
                }
            }
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
        rampJob?.cancel()
        rampJob = null
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator.cancel()
    }

    private fun stopRinging() {
        timeoutJob?.cancel()
        stopOutput()
        current.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopOutput()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.replydev.reveille.RING"
        const val ACTION_SNOOZE = "com.replydev.reveille.SNOOZE"
        const val ACTION_DISMISS = "com.replydev.reveille.DISMISS"
        private const val EXTRA_ARMED = "armed"
        private const val EXTRA_BACKUP = "backup"
        private const val MAX_AUTO_SNOOZES = 3
        private const val RAMP_STEPS = 30
        private const val RAMP_START_VOLUME = 0.1f
        private const val WAKE_LOCK_TIMEOUT_MS = 40 * 60 * 1000L
        private const val TAG = "Reveille"

        private val current = MutableStateFlow<Ringing?>(null)

        /** The alarm currently ringing, or null. RingingActivity closes itself when this becomes null. */
        val ringing: StateFlow<Ringing?> = current.asStateFlow()

        fun start(context: Context, armed: Armed, backup: Boolean) {
            context.startForegroundService(
                Intent(context, RingingService::class.java)
                    .setAction(ACTION_RING)
                    .putExtra(EXTRA_ARMED, armed.encode())
                    .putExtra(EXTRA_BACKUP, backup),
            )
        }

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
