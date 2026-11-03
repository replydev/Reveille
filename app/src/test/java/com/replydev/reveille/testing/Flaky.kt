package com.replydev.reveille.testing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.replydev.reveille.alarm.Notifier
import com.replydev.reveille.data.FireLogDao
import com.replydev.reveille.data.FireLogEntity
import com.replydev.reveille.data.FireOutcome
import com.replydev.reveille.engine.Armed
import java.io.IOException

/** Preferences store whose writes fail while [failWrites] is set (e.g. a full disk). Reads keep working. */
class FlakyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> by delegate {
    @Volatile var failWrites = false

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (failWrites) throw IOException("disk full")
        return delegate.updateData(transform)
    }
}

/** Fire log whose writes fail while [failWrites] is set. Reads keep working. */
class FlakyFireLogDao(private val delegate: FireLogDao) : FireLogDao by delegate {
    @Volatile var failWrites = false

    override suspend fun insert(entry: FireLogEntity): Long {
        if (failWrites) throw IOException("disk full")
        return delegate.insert(entry)
    }

    override suspend fun setOutcome(key: String, scheduledAt: Long, outcome: FireOutcome) {
        if (failWrites) throw IOException("disk full")
        delegate.setOutcome(key, scheduledAt, outcome)
    }
}

/** Notifier whose alerts throw while [failAlerts] is set (e.g. a formatter or the notification service blowing up). */
class FailingNotifier(context: Context) : Notifier(context) {
    @Volatile var failAlerts = true

    override fun showMissed(armed: Armed) {
        if (failAlerts) throw IllegalArgumentException("bad pattern")
        super.showMissed(armed)
    }

    override fun cancelFallbackAlarm(armed: Armed) {
        if (failAlerts) throw IllegalStateException("notification service gone")
        super.cancelFallbackAlarm(armed)
    }

    override fun showSchedulingFailed() {
        if (failAlerts) throw IllegalArgumentException("bad pattern")
        super.showSchedulingFailed()
    }
}

/** Preferences store whose every read throws (an unreadable settings file). */
class UnreadableDataStore : DataStore<Preferences> {
    override val data: kotlinx.coroutines.flow.Flow<Preferences> = kotlinx.coroutines.flow.flow { throw IOException("unreadable") }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("unreadable")
}
