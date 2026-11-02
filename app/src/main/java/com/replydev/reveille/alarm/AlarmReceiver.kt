package com.replydev.reveille.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.replydev.reveille.container
import com.replydev.reveille.engine.Armed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val armed = intent.getStringExtra(AlarmScheduler.EXTRA_ARMED)?.let(Armed::decode) ?: return
        val backup = intent.getBooleanExtra(AlarmScheduler.EXTRA_BACKUP, false)
        val container = context.container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (container.scheduler.claim(armed)) {
                    if (armed.isSnooze) container.schedulerState.setSnooze(null)
                    RingingService.start(context, armed, backup)
                }
                container.scheduler.rearm(if (backup) "backup fired" else "alarm fired")
            } finally {
                pending.finish()
            }
        }
    }
}
