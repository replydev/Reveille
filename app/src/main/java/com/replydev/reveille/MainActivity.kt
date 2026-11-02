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
import androidx.core.content.ContextCompat
import com.replydev.reveille.alarm.RingingActivity
import com.replydev.reveille.alarm.RingingService
import com.replydev.reveille.alarm.WatchdogWorker
import com.replydev.reveille.ui.ReveilleNavHost
import com.replydev.reveille.ui.theme.ReveilleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on Bg with light system-bar icons, in both system modes (the app is dark-only).
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) requestNotificationPermission()
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
        setContent {
            ReveilleTheme { ReveilleNavHost() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers disabled notifications: opening the app while an alarm rings must lead to its Dismiss/Snooze screen.
        if (RingingService.ringing.value != null) {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** POST_NOTIFICATIONS is a runtime permission from Android 13; before that notifications are on by default. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
