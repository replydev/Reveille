package com.replydev.reveille.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.replydev.reveille.container
import java.time.Instant

/**
 * Widget "Skip": skips the alarm the widget displayed ([KEY] at [AT]). If the armed alarm changed in the meantime
 * (e.g. a double tap), it does nothing. The re-arm that follows refreshes the widget.
 */
class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val key = parameters[KEY] ?: return
        val at = parameters[AT] ?: return
        try {
            context.container.scheduler.skipNext(key, Instant.ofEpochMilli(at))
        } catch (e: Exception) {
            Log.e("Reveille", "Skip from widget failed", e)
        }
    }

    companion object {
        val KEY = ActionParameters.Key<String>("armed_key")
        val AT = ActionParameters.Key<Long>("armed_at")
    }
}
