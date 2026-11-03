package com.replydev.reveille.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replydev.reveille.R
import com.replydev.reveille.container
import com.replydev.reveille.data.RingSettings
import com.replydev.reveille.engine.Armed
import com.replydev.reveille.ui.text.rememberLabels
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import android.graphics.Color as AndroidColor

/**
 * Full-screen alarm UI. Normal mode mirrors [RingingService.ringing] (Snooze/Dismiss go to the service). Fallback mode
 * ([fallbackIntent]) is opened by the fallback alarm notification when the service could not ring: it shows the
 * alarm and a Dismiss that acknowledges the fallback. Both work before the first unlock (directBootAware).
 *
 * Preview mode ([previewIntent]) shows a theme with sample data for the picker; it rings nothing and records nothing.
 *
 * The look comes from the [RingTheme] in the device-protected settings. It is cosmetic and never waited on: until the
 * settings are read (or if they can't be) the screen is Dawn.
 */
class RingingActivity : ComponentActivity() {
    private var fallback by mutableStateOf<Armed?>(null)
    private var preview by mutableStateOf<RingTheme?>(null)
    private var windowTheme: RingTheme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyWindowTheme(RingTheme.DAWN)
        fallback = fallbackArmed(intent)
        preview = previewTheme(intent)
        val (initialSettings, ringSettings) = ringSettings()
        setContent {
            ReveilleTheme {
                BackHandler { /* the alarm must be dismissed or snoozed explicitly */ }
                val settings by ringSettings.collectAsStateWithLifecycle(initialValue = initialSettings)
                val ringing by RingingService.ringing.collectAsStateWithLifecycle()
                val current = ringing
                val fallbackAlarm = fallback
                val previewTheme = preview
                when {
                    // The service took over (e.g. the ring guard succeeded): show the real ringing screen.
                    current != null -> Ring(
                        armed = current.armed,
                        settings = settings,
                        title = current.title(this@RingingActivity),
                        since = current.since,
                        backupOf = current.armed.at.takeUnless { current.backup },
                        actions = RingActions(
                            onDismiss = { act(RingingService.ACTION_DISMISS) },
                            onSnooze = { act(RingingService.ACTION_SNOOZE) },
                        ),
                    )
                    fallbackAlarm != null -> Ring(
                        armed = fallbackAlarm,
                        settings = settings,
                        title = stringResource(
                            R.string.ringing_fallback_title,
                            fallbackAlarm.label,
                            rememberLabels().formats.time(fallbackAlarm.at.atZone(ZoneId.systemDefault()).toLocalTime()),
                        ),
                        since = fallbackAlarm.at,
                        backupOf = null,
                        actions = RingActions(onDismiss = { dismissFallback(fallbackAlarm) }, onSnooze = null),
                    )
                    previewTheme != null -> Preview(previewTheme)
                    else -> LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }

    /**
     * The last settings this process read (so the first frame already has the theme) and the live ones. Device-protected
     * DataStore, so it reads before the first unlock too. Any failure leaves the defaults: Dawn and plain "Snooze".
     */
    private fun ringSettings(): Pair<RingSettings?, Flow<RingSettings>> = try {
        val store = container.settings
        store.lastRead to store.settings.catch { Log.w(TAG, "Cannot read settings for the ringing screen", it) }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot open settings for the ringing screen", e)
        null to emptyFlow()
    }

    @Composable
    private fun Ring(armed: Armed, settings: RingSettings?, title: String, since: Instant, backupOf: Instant?, actions: RingActions) {
        val theme = settings?.let { resolveTheme(armed, it.ringTheme, it.policyRingThemes) } ?: RingTheme.DAWN
        SideEffect { applyWindowTheme(theme) }
        val now by produceState(ZonedDateTime.now()) {
            while (true) {
                value = ZonedDateTime.now()
                delay(1_000)
            }
        }
        val policyId = remember(armed) { ringPolicyId(armed) }
        // Only Policy colour shows the policy's name, days and next alarm. Read off the main thread; until then (or if
        // the read fails) the screen shows the alarm's own label.
        val lookup by produceState<PolicyLookup?>(null, armed, theme) {
            if (theme == RingTheme.POLICY_COLOUR && policyId != null) value = lookUpPolicy(armed)
        }
        RingingContent(
            theme,
            RingState(
                now = now,
                title = title,
                policyId = policyId,
                policy = lookup?.info,
                policyGone = lookup?.let { it.info == null } ?: false,
                backupOf = backupOf,
                snoozeMinutes = settings?.snoozeMinutes?.coerceAtLeast(1),
                autoSnoozeMinutes = settings?.let { RingDecisions.ringMinutes(it.maxRingMinutes) },
                ringingFor = Duration.between(since, now.toInstant()),
            ),
            actions,
        )
    }

    /**
     * Preview mode: [theme] with sample data. Nothing behind it rings: no service, no wake lock, no fire log. Dismiss
     * and Snooze just close it.
     */
    @Composable
    private fun Preview(theme: RingTheme) {
        SideEffect { applyWindowTheme(theme) }
        val now by produceState(ZonedDateTime.now()) {
            while (true) {
                value = ZonedDateTime.now()
                delay(1_000)
            }
        }
        val close = {
            applyBrightness(null)
            finish()
        }
        RingingContent(theme, sampleRingState(stringResource(R.string.ring_theme_sample_label), now), RingActions(onDismiss = close, onSnooze = close))
    }

    private suspend fun lookUpPolicy(armed: Armed): PolicyLookup? = try {
        withContext(Dispatchers.IO) {
            PolicyLookup(policyRingInfo(container.repository.snapshot(), armed, Instant.now(), ZoneId.systemDefault()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the policy for the ringing screen", e)
        null
    }

    /** Sends Snooze or Dismiss to the service, giving the user their brightness back first. */
    private fun act(action: String) {
        applyBrightness(null)
        RingingService.send(this, action)
    }

    /** System-bar icons that read on the theme's background, and Midnight's dim window. Only touches this window. */
    private fun applyWindowTheme(theme: RingTheme) {
        if (theme == windowTheme) return
        windowTheme = theme
        runCatching {
            val bars = if (theme.darkBackground) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            enableEdgeToEdge(bars, bars)
        }.onFailure { Log.w(TAG, "Cannot style the system bars", it) }
        applyBrightness(theme.screenBrightness)
    }

    /** [brightness] for this window, or the user's own with null. */
    private fun applyBrightness(brightness: Float?) {
        runCatching {
            window.attributes = window.attributes.apply {
                screenBrightness = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }.onFailure { Log.w(TAG, "Cannot set the screen brightness", it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fallback = fallbackArmed(intent)
        preview = previewTheme(intent)
    }

    private fun dismissFallback(armed: Armed) {
        applyBrightness(null)
        sendBroadcast(
            Intent(this, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FALLBACK_DISMISS)
                .putExtra(AlarmScheduler.EXTRA_ARMED, armed.encode()),
        )
        finish()
    }

    /** The policy read for Policy colour; [info] is null when the policy no longer exists. */
    private class PolicyLookup(val info: PolicyRingInfo?)

    companion object {
        private const val TAG = "Reveille"
        private const val EXTRA_FALLBACK = "fallback_armed"
        private const val EXTRA_PREVIEW = "preview_theme"

        private fun fallbackArmed(intent: Intent?): Armed? = intent?.getStringExtra(EXTRA_FALLBACK)?.let(Armed::decode)

        private fun previewTheme(intent: Intent?): RingTheme? = intent?.getStringExtra(EXTRA_PREVIEW)?.let(RingTheme::fromName)

        /** Opens the ringing screen in preview mode: [theme] with sample data, no sound, nothing recorded. */
        fun previewIntent(context: Context, theme: RingTheme): Intent =
            Intent(context, RingingActivity::class.java).putExtra(EXTRA_PREVIEW, theme.name)

        fun fallbackIntent(context: Context, armed: Armed): Intent =
            Intent(context, RingingActivity::class.java)
                .putExtra(EXTRA_FALLBACK, armed.encode())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
