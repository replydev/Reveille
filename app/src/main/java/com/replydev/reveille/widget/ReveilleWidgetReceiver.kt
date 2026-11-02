package com.replydev.reveille.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.replydev.reveille.container
import kotlinx.coroutines.launch

class ReveilleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReveilleWidget()

    /**
     * System widget updates (placement, the 30-minute `updatePeriodMillis`) double as a re-arm safety net.
     *
     * No loop: the re-arm refreshes widgets through [WidgetUpdater] / Glance `updateAll`, which drives Glance sessions
     * and `AppWidgetManager.updateAppWidget` directly and never sends APPWIDGET_UPDATE, so it does not come back here.
     * Not done in `provideGlance`, which every re-arm triggers.
     *
     * Runs in the app scope rather than under `goAsync()`: Glance's `super.onUpdate` already took the receiver's
     * single pending result (a second `goAsync()` returns null) and keeps the process alive while it updates.
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val container = context.container
        container.appScope.launch {
            try {
                container.scheduler.rearm("widget update")
            } catch (e: Exception) {
                Log.e("Reveille", "Re-arm on widget update failed", e)
            }
        }
    }
}
