package com.example.core.di

import android.content.Context
import com.example.core.database.AppDatabase
import com.example.core.database.SecuritySettingsEntity
import com.example.core.model.EngineType
import com.example.core.system.SystemMetricsProvider
import com.example.engine.core.AndroidPackageManager
import com.example.engine.core.ExternalTcpEngineModule
import com.example.engine.core.PackageDownloader
import com.example.engine.core.RuntimeEngine
import com.example.engine.db.DatabaseManager
import com.example.engine.db.PhpMyAdminManager
import com.example.engine.domain.DomainManager
import com.example.engine.http.EmbeddedHttpServer
import com.example.engine.installer.ProjectInstaller
import com.example.engine.installer.RequirementsInstaller
import com.example.engine.runtime.NodeRuntimeManager
import com.example.engine.runtime.PhpRuntimeManager
import com.example.engine.runtime.PythonRuntimeManager
import com.example.engine.tunnel.TunnelManager
import com.example.files.IsolatedFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val metricsProvider = SystemMetricsProvider(appContext)

    val downloader = PackageDownloader(appContext, database.appLogDao())
    val runtimeEngine = RuntimeEngine(appContext, database.appLogDao())
    val packageManager = AndroidPackageManager(appContext, downloader, runtimeEngine, database.appLogDao())

    val httpServer = EmbeddedHttpServer(
        appContext,
        database.appLogDao(),
        database.domainDao(),
        database.siteDao()
    )
    val phpRuntimeManager = PhpRuntimeManager(appContext, database.runtimeConfigDao(), runtimeEngine, database.appLogDao())
    val pythonRuntimeManager = PythonRuntimeManager(appContext, database.runtimeConfigDao(), runtimeEngine, downloader, database.appLogDao())
    val nodeRuntimeManager = NodeRuntimeManager(appContext, database.runtimeConfigDao(), runtimeEngine)
    val databaseManager = DatabaseManager(appContext, database.databaseInstanceDao(), database.appLogDao())
    val phpMyAdminManager = PhpMyAdminManager(
        appContext,
        downloader,
        runtimeEngine,
        phpRuntimeManager,
        httpServer,
        database.appLogDao()
    )
    val domainManager = DomainManager(appContext, database.domainDao(), database.appLogDao())
    val tunnelManager = TunnelManager(appContext, database.tunnelConfigDao(), database.appLogDao())
    val fileManager = IsolatedFileManager(appContext, database.appLogDao())
    val projectInstaller = ProjectInstaller(
        appContext,
        fileManager,
        httpServer,
        database.projectInstallDao(),
        database.appLogDao()
    )
    val requirementsInstaller = RequirementsInstaller(appContext, packageManager, downloader)

    val mysqlModule = ExternalTcpEngineModule(
        context = appContext,
        runtimeEngine = runtimeEngine,
        engineType = EngineType.MYSQL_MARIADB,
        binaryCandidates = listOf("mariadbd", "mysqld"),
        subDir = "mariadb",
        defaultPort = 3306,
        requirementNoticeText = "يتطلب تشغيل MySQL / MariaDB حزمة تنفيذية متوافقة مع معمارية الجهاز أو خدمة مستمعة على المنفذ 3306."
    )

    val postgresModule = ExternalTcpEngineModule(
        context = appContext,
        runtimeEngine = runtimeEngine,
        engineType = EngineType.POSTGRESQL,
        binaryCandidates = listOf("postgres", "pg_ctl"),
        subDir = "postgresql",
        defaultPort = 5432,
        requirementNoticeText = "يتطلب تشغيل PostgreSQL حزمة تنفيذية متوافقة مع معمارية الجهاز أو خادماً محلياً على المنفذ 5432."
    )

    init {
        initScope.launch {
            database.siteDao().markAllStopped()
            phpRuntimeManager.ensureDefaultConfigs()
            pythonRuntimeManager.ensureDefaultConfigs()
            nodeRuntimeManager.ensureDefaultConfigs()
            if (database.securitySettingsDao().getSettings() == null) {
                database.securitySettingsDao().upsert(SecuritySettingsEntity())
            }
        }
    }
}
