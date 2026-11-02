package com.replydev.reveille

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.replydev.reveille.alarm.RingingActivity
import com.replydev.reveille.alarm.RingingService
import com.replydev.reveille.alarm.WatchdogWorker
import com.replydev.reveille.ui.ReveilleNavHost
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A screen the widget or a notification asked for ([OPEN_ONE_OFF], [OPEN_HEALTH], [OPEN_UPDATE]); cleared once the nav host has opened it. */
    private var launchTarget by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on Bg with light system-bar icons, in both system modes (the app is dark-only).
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) {
            requestNotificationPermission()
            // Only on a fresh start: a recreated activity has already opened it.
            launchTarget = intent.getStringExtra(EXTRA_OPEN)
        }
        // App scope, not lifecycleScope: the re-arm must finish even if the activity is closed right away.
        val container = container
        container.appScope.launch {
            try {
                container.scheduler.rearm("app opened")
            } catch (e: Exception) {
                Log.e("Reveille", "Re-arm on app start failed", e)
            }
        }
        WatchdogWorker.schedule(this)
        container.appScope.launch {
            try {
                container.updates.syncSchedule()
            } catch (e: Exception) {
                Log.e("Reveille", "Could not schedule the update check", e)
            }
        }
        setContent {
            ReveilleTheme { ReveilleNavHost(launchTarget, onLaunched = { launchTarget = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { launchTarget = it }
    }

    override fun onResume() {
        super.onResume()
        // Covers disabled notifications: opening the app while an alarm rings must lead to its Dismiss/Snooze screen.
        if (RingingService.ringing.value != null) {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_ONE_OFF = "oneoff"
        const val OPEN_HEALTH = "health"
        const val OPEN_UPDATE = "update"
    }

    /** POST_NOTIFICATIONS is a runtime permission from Android 13; before that notifications are on by default. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
