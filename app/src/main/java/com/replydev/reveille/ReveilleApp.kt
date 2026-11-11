package com.replydev.reveille

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Configuration
import kotlinx.coroutines.launch

class ReveilleApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        @VisibleForTesting internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        // Housekeeping only, never on the alarm path; DataStore is device-protected, so this is safe in Direct Boot too,
        // but waiting for the unlock keeps a locked-boot process doing nothing but ringing.
        if (getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true) {
            container.appScope.launch { runCatching { container.sounds.sweepOrphans() } }
        }
    }

    // On-demand WorkManager init: its database is not available before the first unlock,
    // so it must never initialize automatically in a direct-boot process.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

val Context.container: AppContainer
    get() = (applicationContext as ReveilleApp).container
