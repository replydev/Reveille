package com.replydev.reveille.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.replydev.reveille.container

/** Widget "Skip": skips the armed alarm. The re-arm that follows refreshes the widget. */
class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            context.container.scheduler.skipNext()
        } catch (e: Exception) {
            Log.e("Reveille", "Skip from widget failed", e)
        }
    }
}
