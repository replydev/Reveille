package com.replydev.reveille.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.replydev.reveille.data.FireLogDao
import com.replydev.reveille.data.FireLogEntity
import com.replydev.reveille.data.FireOutcome
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
