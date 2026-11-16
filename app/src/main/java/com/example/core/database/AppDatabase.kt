package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SiteEntity::class,
        DatabaseInstanceEntity::class,
        RuntimeConfigEntity::class,
        TunnelConfigEntity::class,
        AppLogEntity::class,
        SecuritySettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteDao(): SiteDao
    abstract fun databaseInstanceDao(): DatabaseInstanceDao
    abstract fun runtimeConfigDao(): RuntimeConfigDao
    abstract fun tunnelConfigDao(): TunnelConfigDao
    abstract fun appLogDao(): AppLogDao
    abstract fun securitySettingsDao(): SecuritySettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "abotalal_hosting.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
