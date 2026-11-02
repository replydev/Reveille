package com.replydev.reveille

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.replydev.reveille.alarm.RingingActivity
import com.replydev.reveille.alarm.RingingService
import com.replydev.reveille.ui.theme.ReveilleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReveilleTheme {
                Surface(Modifier.fillMaxSize()) { Text("Reveille") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers disabled notifications: opening the app while an alarm rings must lead to its Dismiss/Snooze screen.
        if (RingingService.ringing.value != null) {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
