package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {
    @Query("SELECT * FROM sites ORDER BY createdAt DESC")
    fun observeAllSites(): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites WHERE id = :siteId")
    suspend fun getSiteById(siteId: String): SiteEntity?

    @Query("SELECT * FROM sites WHERE port = :port LIMIT 1")
    suspend fun getSiteByPort(port: Int): SiteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSite(site: SiteEntity)

    @Update
    suspend fun updateSite(site: SiteEntity)

    @Query("UPDATE sites SET status = :status, lastError = :lastError WHERE id = :siteId")
    suspend fun updateSiteStatus(siteId: String, status: String, lastError: String = "")

    @Query("UPDATE sites SET status = 'STOPPED' WHERE status = 'RUNNING'")
    suspend fun markAllStopped()

    @Query("DELETE FROM sites WHERE id = :siteId")
    suspend fun deleteSite(siteId: String)
}

@Dao
interface DatabaseInstanceDao {
    @Query("SELECT * FROM databases ORDER BY createdAt DESC")
    fun observeAllDatabases(): Flow<List<DatabaseInstanceEntity>>

    @Query("SELECT * FROM databases WHERE id = :dbId")
    suspend fun getById(dbId: String): DatabaseInstanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(db: DatabaseInstanceEntity)

    @Query("DELETE FROM databases WHERE id = :dbId")
    suspend fun deleteById(dbId: String)
}

@Dao
interface RuntimeConfigDao {
    @Query("SELECT * FROM runtime_configs ORDER BY engineType, version")
    fun observeAll(): Flow<List<RuntimeConfigEntity>>

    @Query("SELECT * FROM runtime_configs WHERE id = :id")
    suspend fun getById(id: String): RuntimeConfigEntity?

    @Query("SELECT * FROM runtime_configs WHERE engineType = :engineType")
    suspend fun getByEngine(engineType: String): List<RuntimeConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: RuntimeConfigEntity)
}

@Dao
interface TunnelConfigDao {
    @Query("SELECT * FROM tunnel_configs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TunnelConfigEntity>>

    @Query("SELECT * FROM tunnel_configs WHERE siteId = :siteId LIMIT 1")
    suspend fun getBySiteId(siteId: String): TunnelConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: TunnelConfigEntity)

    @Query("DELETE FROM tunnel_configs WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AppLogDao {
    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC, id DESC LIMIT 400")
    fun observeRecentLogs(): Flow<List<AppLogEntity>>

    @Query("SELECT * FROM app_logs WHERE source = :source ORDER BY timestamp DESC, id DESC LIMIT 150")
    fun observeLogsForSource(source: String): Flow<List<AppLogEntity>>

    @Insert
    suspend fun insert(log: AppLogEntity)

    @Query("DELETE FROM app_logs")
    suspend fun clearAll()
}

@Dao
interface SecuritySettingsDao {
    @Query("SELECT * FROM security_settings WHERE id = 1")
    fun observeSettings(): Flow<SecuritySettingsEntity?>

    @Query("SELECT * FROM security_settings WHERE id = 1")
    suspend fun getSettings(): SecuritySettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SecuritySettingsEntity)
}

@Dao
interface DomainDao {
    @Query("SELECT * FROM domains ORDER BY createdAt DESC")
    fun observeAllDomains(): Flow<List<DomainEntity>>

    @Query("SELECT * FROM domains")
    suspend fun getAllDomainsOnce(): List<DomainEntity>

    @Query("SELECT * FROM domains WHERE LOWER(domainName) = LOWER(:domainName) LIMIT 1")
    suspend fun getByName(domainName: String): DomainEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(domain: DomainEntity)

    @Query("DELETE FROM domains WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProjectInstallDao {
    @Query("SELECT * FROM project_installs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectInstallEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ProjectInstallEntity)

    @Query("DELETE FROM project_installs WHERE id = :id")
    suspend fun deleteById(id: String)
}
