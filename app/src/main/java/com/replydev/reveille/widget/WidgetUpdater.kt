package com.replydev.reveille.widget

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.glance.appwidget.updateAll

object WidgetUpdater {
    /**
     * Refreshes every placed widget. No-op before the first unlock: widgets are not shown then, and Glance keeps its
     * state in credential-protected storage (this is called from re-arms that run in direct boot).
     */
    suspend fun update(context: Context) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        runCatching { ReveilleWidget().updateAll(context) }
            .onFailure { Log.w("Reveille", "Widget update failed", it) }
    }
}
