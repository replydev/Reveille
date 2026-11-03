package com.replydev.reveille

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Configuration

class ReveilleApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        @VisibleForTesting internal set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
    }

    // On-demand WorkManager init: its database is not available before the first unlock,
    // so it must never initialize automatically in a direct-boot process.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

val Context.container: AppContainer
    get() = (applicationContext as ReveilleApp).container
