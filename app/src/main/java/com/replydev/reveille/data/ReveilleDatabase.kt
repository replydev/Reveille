package com.replydev.reveille.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [PolicyEntity::class, RuleEntity::class, PauseEntity::class, OneOffEntity::class, SkipEntity::class, FireLogEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ReveilleDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun fireLogDao(): FireLogDao

    companion object {
        /** Stored in device-protected storage so it is readable before the first unlock after a reboot. */
        fun create(context: Context): ReveilleDatabase =
            Room.databaseBuilder(context.createDeviceProtectedStorageContext(), ReveilleDatabase::class.java, "reveille.db").build()
    }
}
