package com.example.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rootFolder: String,
    val port: Int,
    val projectType: String,
    val runtimeVersion: String,
    val entryFile: String,
    val directoryListingEnabled: Boolean = true,
    val allowLanAccess: Boolean = false,
    val envConfig: String = "",
    val status: String = "STOPPED",
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "databases")
data class DatabaseInstanceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val engineType: String,
    val storageTarget: String,
    val username: String,
    val passwordHash: String,
    val privileges: String = "ALL",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "runtime_configs")
data class RuntimeConfigEntity(
    @PrimaryKey val id: String,
    val engineType: String,
    val version: String,
    val installPath: String,
    val configText: String,
    val extensionsCsv: String,
    val port: Int,
    val isInstalled: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tunnel_configs")
data class TunnelConfigEntity(
    @PrimaryKey val id: String,
    val siteId: String,
    val provider: String,
    val controlEndpoint: String,
    val publicUrl: String = "",
    val enforceHttps: Boolean = true,
    val requireAuth: Boolean = true,
    val authUsername: String = "admin",
    val authPasswordHash: String = "",
    val allowedIpCidr: String = "",
    val userApproved: Boolean = false,
    val isActive: Boolean = false,
    val statusMessage: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_logs")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val source: String,
    val message: String
)

@Entity(tableName = "security_settings")
data class SecuritySettingsEntity(
    @PrimaryKey val id: Int = 1,
    val adminPinEnabled: Boolean = false,
    val adminPinHash: String = "",
    val localhostOnlyByDefault: Boolean = true,
    val requireApprovalBeforeShare: Boolean = true,
    val logAllRequests: Boolean = true,
    val blockTraversalAttempts: Boolean = true
)

@Entity(tableName = "domains")
data class DomainEntity(
    @PrimaryKey val id: String,
    val domainName: String,
    val isLocalDomain: Boolean,
    val parentDomain: String = "",
    val siteId: String,
    val documentRoot: String,
    val dnsARecord: String = "127.0.0.1",
    val dnsCnameRecord: String = "",
    val dnsTxtRecord: String = "",
    val sslEnabled: Boolean = false,
    val sslCertPath: String = "",
    val sslSubject: String = "",
    val tunnelLinked: Boolean = false,
    val tunnelHostname: String = "",
    val dnsResolvedGlobally: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "project_installs")
data class ProjectInstallEntity(
    @PrimaryKey val id: String,
    val siteId: String,
    val projectName: String,
    val detectedScript: String,
    val primaryLanguage: String,
    val backupFolderPath: String,
    val installStatus: String, // ANALYZED, VERIFIED, FAILED
    val reportSummary: String,
    val verificationDetails: String,
    val updatedAt: Long = System.currentTimeMillis()
)
