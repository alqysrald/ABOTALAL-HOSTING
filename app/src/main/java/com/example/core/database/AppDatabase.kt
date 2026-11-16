package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SiteEntity::class,
        DatabaseInstanceEntity::class,
        RuntimeConfigEntity::class,
        TunnelConfigEntity::class,
        AppLogEntity::class,
        SecuritySettingsEntity::class,
        DomainEntity::class,
        ProjectInstallEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteDao(): SiteDao
    abstract fun databaseInstanceDao(): DatabaseInstanceDao
    abstract fun runtimeConfigDao(): RuntimeConfigDao
    abstract fun tunnelConfigDao(): TunnelConfigDao
    abstract fun appLogDao(): AppLogDao
    abstract fun securitySettingsDao(): SecuritySettingsDao
    abstract fun domainDao(): DomainDao
    abstract fun projectInstallDao(): ProjectInstallDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS domains (
                        id TEXT NOT NULL PRIMARY KEY,
                        domainName TEXT NOT NULL,
                        isLocalDomain INTEGER NOT NULL,
                        parentDomain TEXT NOT NULL,
                        siteId TEXT NOT NULL,
                        documentRoot TEXT NOT NULL,
                        dnsARecord TEXT NOT NULL,
                        dnsCnameRecord TEXT NOT NULL,
                        dnsTxtRecord TEXT NOT NULL,
                        sslEnabled INTEGER NOT NULL,
                        sslCertPath TEXT NOT NULL,
                        sslSubject TEXT NOT NULL,
                        tunnelLinked INTEGER NOT NULL,
                        tunnelHostname TEXT NOT NULL,
                        dnsResolvedGlobally INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS project_installs (
                        id TEXT NOT NULL PRIMARY KEY,
                        siteId TEXT NOT NULL,
                        projectName TEXT NOT NULL,
                        detectedScript TEXT NOT NULL,
                        primaryLanguage TEXT NOT NULL,
                        backupFolderPath TEXT NOT NULL,
                        installStatus TEXT NOT NULL,
                        reportSummary TEXT NOT NULL,
                        verificationDetails TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "abotalal_hosting.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
