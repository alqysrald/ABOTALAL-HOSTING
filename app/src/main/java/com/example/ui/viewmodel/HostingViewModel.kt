package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AbotalalHostingApp
import com.example.core.compat.DeviceCompatibilityProfile
import com.example.core.compat.PermissionHealthSnapshot
import com.example.core.database.AppLogEntity
import com.example.core.database.DatabaseInstanceEntity
import com.example.core.database.DomainEntity
import com.example.core.database.ProjectInstallEntity
import com.example.core.database.RuntimeConfigEntity
import com.example.core.database.SecuritySettingsEntity
import com.example.core.database.SiteEntity
import com.example.core.database.TunnelConfigEntity
import com.example.core.model.AndroidPackageSpec
import com.example.core.model.DbEngineType
import com.example.core.model.DownloadTaskState
import com.example.core.model.EngineInfo
import com.example.core.model.EngineType
import com.example.core.model.FileEntry
import com.example.core.model.PackageInstallProgress
import com.example.core.model.PhpMyAdminRelease
import com.example.core.model.PhpMyAdminStatus
import com.example.core.model.PhpVerificationReport
import com.example.core.model.ProjectAnalysisReport
import com.example.core.model.ProjectType
import com.example.core.model.PythonPackageInfo
import com.example.core.model.PythonVerificationReport
import com.example.core.model.SqlQueryResult
import com.example.core.model.SqliteTableInfo
import com.example.core.model.SystemMetrics
import com.example.core.model.TransferProgress
import com.example.core.model.TrustedEnginePackage
import com.example.core.model.TunnelProvider
import com.example.core.security.SecurityHelper
import com.example.engine.core.EngineEightPointReport
import com.example.engine.domain.LocalDnsVpnService
import com.example.engine.service.HostingForegroundService
import com.example.engine.tunnel.TunnelRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class HostingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AbotalalHostingApp
    private val container = app.container
    private val db = container.database

    val sites: StateFlow<List<SiteEntity>> = db.siteDao().observeAllSites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val runningSiteIds: StateFlow<Set<String>> = container.httpServer.runningSiteIds
    val activeConnections: StateFlow<Int> = container.httpServer.activeConnections

    val databases: StateFlow<List<DatabaseInstanceEntity>> = db.databaseInstanceDao().observeAllDatabases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val domains: StateFlow<List<DomainEntity>> = db.domainDao().observeAllDomains()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isDnsVpnRunning: StateFlow<Boolean> = LocalDnsVpnService.isDnsVpnRunning

    val projectInstalls: StateFlow<List<ProjectInstallEntity>> = db.projectInstallDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val runtimeConfigs: StateFlow<List<RuntimeConfigEntity>> = db.runtimeConfigDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tunnelConfigs: StateFlow<List<TunnelConfigEntity>> = db.tunnelConfigDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tunnelRuntimeStates: StateFlow<Map<String, TunnelRuntimeState>> = container.tunnelManager.runtimeStates

    val logs: StateFlow<List<AppLogEntity>> = db.appLogDao().observeRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val securitySettings: StateFlow<SecuritySettingsEntity?> = db.securitySettingsDao().observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val transferProgress: StateFlow<TransferProgress?> = container.fileManager.transferProgress
    val packageInstallProgress: StateFlow<PackageInstallProgress?> = container.requirementsInstaller.installProgress
    val downloadTaskState: StateFlow<DownloadTaskState?> = container.requirementsInstaller.downloadTask
    val packageCatalog: StateFlow<List<AndroidPackageSpec>> = container.requirementsInstaller.packageCatalog
    val isSyncingCatalog: StateFlow<Boolean> = container.requirementsInstaller.isSyncingCatalog
    val lastSyncMessage: StateFlow<String?> = container.requirementsInstaller.lastSyncMessage

    val installedPythonPackages: StateFlow<List<PythonPackageInfo>> = container.pythonRuntimeManager.installedPackages
    val pythonVerificationReport: StateFlow<PythonVerificationReport?> = container.pythonRuntimeManager.lastVerificationReport
    val phpVerificationReport: StateFlow<PhpVerificationReport?> = container.phpRuntimeManager.lastVerificationReport
    val lastPipOutput: StateFlow<String> = container.pythonRuntimeManager.lastPipOutput
    val phpMyAdminStatus: StateFlow<PhpMyAdminStatus> = container.phpMyAdminManager.status
    val permissionHealth: StateFlow<PermissionHealthSnapshot> = container.permissionManager.snapshot

    private val _deviceCompatProfile = MutableStateFlow(container.androidCompatLayer.collectDeviceProfile())
    val deviceCompatProfile: StateFlow<DeviceCompatibilityProfile> = _deviceCompatProfile.asStateFlow()

    private val _engineDiagnosticsMap = MutableStateFlow<Map<EngineType, EngineEightPointReport>>(emptyMap())
    val engineDiagnosticsMap: StateFlow<Map<EngineType, EngineEightPointReport>> = _engineDiagnosticsMap.asStateFlow()

    private val _toolingStatus = MutableStateFlow(container.runtimeCompatibilityLayer.inspectToolingStatus())
    val toolingStatus: StateFlow<Pair<String, String>> = _toolingStatus.asStateFlow()

    private val _phpLoadedModules = MutableStateFlow<List<String>>(emptyList())
    val phpLoadedModules: StateFlow<List<String>> = _phpLoadedModules.asStateFlow()

    private val _pypiInspectionResult = MutableStateFlow<PythonPackageInfo?>(null)
    val pypiInspectionResult: StateFlow<PythonPackageInfo?> = _pypiInspectionResult.asStateFlow()

    private val _engines = MutableStateFlow<List<EngineInfo>>(emptyList())
    val engines: StateFlow<List<EngineInfo>> = _engines.asStateFlow()

    private val _metrics = MutableStateFlow(SystemMetrics())
    val metrics: StateFlow<SystemMetrics> = _metrics.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isAdminLocked = MutableStateFlow(false)
    val isAdminLocked: StateFlow<Boolean> = _isAdminLocked.asStateFlow()

    private val _selectedSiteForFiles = MutableStateFlow<SiteEntity?>(null)
    val selectedSiteForFiles: StateFlow<SiteEntity?> = _selectedSiteForFiles.asStateFlow()

    private val _isSiteLockedFileMode = MutableStateFlow(false)
    val isSiteLockedFileMode: StateFlow<Boolean> = _isSiteLockedFileMode.asStateFlow()

    private val _currentDir = MutableStateFlow<File?>(null)
    val currentDir: StateFlow<File?> = _currentDir.asStateFlow()

    private val _fileEntries = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileEntries: StateFlow<List<FileEntry>> = _fileEntries.asStateFlow()

    private val _clipboardFile = MutableStateFlow<Pair<String, Boolean>?>(null)
    val clipboardFile: StateFlow<Pair<String, Boolean>?> = _clipboardFile.asStateFlow()

    private val _inspectedTables = MutableStateFlow<List<SqliteTableInfo>>(emptyList())
    val inspectedTables: StateFlow<List<SqliteTableInfo>> = _inspectedTables.asStateFlow()

    private val _lastSqlResult = MutableStateFlow<SqlQueryResult?>(null)
    val lastSqlResult: StateFlow<SqlQueryResult?> = _lastSqlResult.asStateFlow()

    private val _currentAnalysisReport = MutableStateFlow<ProjectAnalysisReport?>(null)
    val currentAnalysisReport: StateFlow<ProjectAnalysisReport?> = _currentAnalysisReport.asStateFlow()

    private val _lastBackupDir = MutableStateFlow<File?>(null)
    val lastBackupDir: StateFlow<File?> = _lastBackupDir.asStateFlow()

    val trustedPackages: List<TrustedEnginePackage>
        get() = container.requirementsInstaller.getCompatibleCatalog()

    init {
        startTelemetryLoop()
        refreshEngines()
        viewModelScope.launch(Dispatchers.IO) {
            container.phpMyAdminManager.refreshStatus()
            container.pythonRuntimeManager.refreshInstalledPackages("3.12")
            val sec = db.securitySettingsDao().observeSettings().firstOrNull()
            if (sec?.adminPinEnabled == true && sec.adminPinHash.isNotBlank()) {
                _isAdminLocked.value = true
            }
        }
    }

    private fun startTelemetryLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val conns = container.httpServer.activeConnections.value
                val running = container.httpServer.runningSiteIds.value.isNotEmpty() ||
                    container.runtimeEngine.activeServices.value.values.any { it.isAlive }
                _metrics.value = container.metricsProvider.collectMetrics(conns, running)
                delay(2000L)
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun dismissTransferProgress() {
        container.fileManager.dismissProgress()
    }

    fun dismissPackageInstallProgress() {
        container.requirementsInstaller.dismissProgress()
    }

    fun cancelActiveDownload(taskId: String) {
        container.requirementsInstaller.cancelActiveDownload(taskId)
    }

    fun retryDownload(task: DownloadTaskState) {
        val pkgSpec = packageCatalog.value.firstOrNull { it.id == task.packageId } ?: return
        installPackageSpec(pkgSpec, task.retryUrl.ifBlank { null }, task.retrySha256.ifBlank { null })
    }

    fun isInternetAvailable(): Boolean = container.requirementsInstaller.isInternetAvailable()

    fun refreshEngines() {
        viewModelScope.launch(Dispatchers.IO) {
            container.permissionManager.refreshSnapshot()
            _deviceCompatProfile.value = container.androidCompatLayer.collectDeviceProfile()
            container.packageManager.refreshLocalCatalogState()
            val list = listOf(
                container.httpServer.getHttpEngineStatus(),
                container.phpRuntimeManager.probeStatus(),
                container.pythonRuntimeManager.probeStatus(),
                container.nodeRuntimeManager.probeStatus(),
                container.mysqlModule.probeEngine(),
                container.databaseManager.getSqliteEngineStatus(),
                container.postgresModule.probeEngine(),
                container.databaseManager.probePhpMyAdminStatus()
            )
            _engines.value = list
            _engineDiagnosticsMap.value = EngineType.entries.associateWith {
                container.runtimeCompatibilityLayer.runEightPointDiagnostics(it)
            }
            _toolingStatus.value = container.runtimeCompatibilityLayer.inspectToolingStatus()
            container.phpMyAdminManager.refreshStatus()
        }
    }

    // --- Universal Compatibility, Permissions & SAF Storage ---

    fun refreshPermissionHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            container.permissionManager.refreshSnapshot()
            _deviceCompatProfile.value = container.androidCompatLayer.collectDeviceProfile()
        }
    }

    fun getRequiredLegacyStoragePermissions(): Array<String> =
        container.permissionManager.getRequiredLegacyPermissions()

    fun buildManageAllFilesIntent(): Intent? =
        container.permissionManager.buildManageAllFilesSettingsIntent()

    fun buildNotificationSettingsIntent(): Intent =
        container.permissionManager.buildAppNotificationSettingsIntent()

    fun persistSafFolderAccess(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.permissionManager.persistSafTreePermission(treeUri)
            _statusBanner.value = res.fold(
                onSuccess = { "تم ربط المجلد ($it) بنجاح عبر Storage Access Framework" },
                onFailure = { it.message ?: "تعذر حفظ إذن المجلد" }
            )
        }
    }

    fun importSafFolderIntoCurrentDir(treeUri: Uri) {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.permissionManager.importSafTreeIntoWorkspace(jail, dir, treeUri)
            res.onSuccess { count ->
                _statusBanner.value = "تم استيراد $count ملف من المجلد الخارجي (SAF / SD / USB)"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل استيراد مجلد SAF"
            }
        }
    }

    fun importSafFolderIntoSiteAndAnalyze(site: SiteEntity, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val siteRoot = File(site.rootFolder)
            val publicHtml = File(siteRoot, "public_html").apply { mkdirs() }
            val res = container.permissionManager.importSafTreeIntoWorkspace(siteRoot, publicHtml, treeUri)
            res.onSuccess { count ->
                val report = container.projectInstaller.analyzeProjectDirectory(siteRoot, site)
                _currentAnalysisReport.value = report
                _statusBanner.value = "تم استيراد $count ملف عبر SAF وتحليل المشروع: ${report.detectedScriptOrFramework}"
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل استيراد مجلد SAF إلى المشروع"
            }
        }
    }

    // --- Package Manager & Runtime Service Controls ---

    fun syncPackageRepositories() {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.requirementsInstaller.syncCompatibleCatalog()
            res.onSuccess {
                _statusBanner.value = "تمت مزامنة ${it.size} حزمة متوافقة مع معمارية الجهاز"
            }.onFailure {
                _statusBanner.value = it.message ?: "تعذرت مزامنة المستودع الرسمي"
            }
            refreshEngines()
        }
    }

    fun installPackageSpec(spec: AndroidPackageSpec, overrideUrl: String? = null, overrideSha: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.packageManager.installPackageFromUrl(spec, overrideUrl, overrideSha)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل التثبيت" })
            refreshEngines()
        }
    }

    fun importLocalPackageSpec(spec: AndroidPackageSpec, uri: Uri, optionalSha: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.packageManager.installPackageFromUri(spec, uri, optionalSha)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل الاستيراد" })
            refreshEngines()
        }
    }

    fun installMissingRequirement(engineType: EngineType) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.requirementsInstaller.installEngineRequirement(engineType)
            _statusBanner.value = res.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "تعذر تثبيت ${engineType.titleAr}" }
            )
            refreshEngines()
        }
    }

    fun startEngineService(engineType: EngineType, version: String = "8.4") {
        viewModelScope.launch(Dispatchers.IO) {
            val res: Result<String> = when (engineType) {
                EngineType.PHP -> container.phpRuntimeManager.startPhpService(version)
                EngineType.MYSQL_MARIADB -> container.mysqlModule.start().map { it.verificationDetails }
                EngineType.POSTGRESQL -> container.postgresModule.start().map { it.verificationDetails }
                EngineType.PHPMYADMIN -> container.phpMyAdminManager.startAndVerify().map { it.verificationDetails }
                else -> Result.failure(IllegalStateException("يتم تشغيل هذا المحرك مباشرة مع المواقع المرتبطة به"))
            }
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل التشغيل" })
            refreshEngines()
        }
    }

    fun stopEngineService(engineType: EngineType, version: String = "8.4") {
        viewModelScope.launch(Dispatchers.IO) {
            when (engineType) {
                EngineType.PHP -> container.phpRuntimeManager.stopPhpService(version)
                EngineType.MYSQL_MARIADB -> container.mysqlModule.stop()
                EngineType.POSTGRESQL -> container.postgresModule.stop()
                EngineType.PHPMYADMIN -> container.phpMyAdminManager.stopPhpMyAdmin()
                else -> Unit
            }
            _statusBanner.value = "تم إيقاف ${engineType.titleAr}"
            refreshEngines()
        }
    }

    fun restartEngineService(engineType: EngineType, version: String = "8.4") {
        viewModelScope.launch(Dispatchers.IO) {
            val res: Result<String> = when (engineType) {
                EngineType.PHP -> container.phpRuntimeManager.restartPhpService(version)
                EngineType.MYSQL_MARIADB -> container.mysqlModule.restart().map { it.verificationDetails }
                EngineType.POSTGRESQL -> container.postgresModule.restart().map { it.verificationDetails }
                EngineType.PHPMYADMIN -> {
                    container.phpMyAdminManager.stopPhpMyAdmin()
                    container.phpMyAdminManager.startAndVerify().map { it.verificationDetails }
                }
                else -> Result.failure(IllegalStateException("لا يدعم إعادة التشغيل المستقلة"))
            }
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل إعادة التشغيل" })
            refreshEngines()
        }
    }

    // --- Sites Management (with Strict Isolated Storage) ---

    fun createSite(
        name: String,
        port: Int,
        projectType: ProjectType,
        runtimeVersion: String,
        entryFile: String,
        allowLanAccess: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (port !in 1024..65535) {
                _statusBanner.value = "يرجى اختيار منفذ صالح بين 1024 و 65535"
                return@launch
            }
            val existingPort = db.siteDao().getSiteByPort(port)
            if (existingPort != null) {
                _statusBanner.value = "المنفذ $port مخصص مسبقاً للموقع ${existingPort.name}"
                return@launch
            }
            val id = SecurityHelper.sanitizeSlug(name) + "-" + UUID.randomUUID().toString().take(4)
            val rootDir = File(app.filesDir, "sites/$id")
            val actualEntry = entryFile.ifBlank { projectType.defaultEntry }

            val publicHtml = container.fileManager.ensureSiteWorkspace(
                siteRoot = rootDir,
                siteId = id,
                siteName = name.trim(),
                projectType = projectType.name,
                runtimeVersion = runtimeVersion,
                port = port,
                entryFile = actualEntry
            )
            val entryTarget = File(publicHtml, actualEntry)
            if (!entryTarget.exists()) {
                entryTarget.writeText(buildStarterContent(name, projectType, port))
            }

            val site = SiteEntity(
                id = id,
                name = name.trim(),
                rootFolder = rootDir.absolutePath,
                port = port,
                projectType = projectType.name,
                runtimeVersion = runtimeVersion,
                entryFile = actualEntry,
                directoryListingEnabled = true,
                allowLanAccess = allowLanAccess,
                status = "STOPPED"
            )
            if (projectType == ProjectType.PHP_APP) {
                container.phpRuntimeManager.ensureSitePhpEnvironment(site)
            }
            db.siteDao().upsertSite(site)
            db.appLogDao().insert(
                AppLogEntity(
                    level = "INFO",
                    source = "SITES",
                    message = "تم إنشاء موقع معزول '$name' (public_html/config/logs/backups) على المنفذ $port"
                )
            )
            _statusBanner.value = "تم إنشاء الموقع $name ببيئة ملفات وإعدادات معزولة بالكامل"
        }
    }

    fun updateSiteConfig(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = runningSiteIds.value.contains(site.id)
            if (wasRunning) {
                container.httpServer.stopSite(site.id)
            }
            db.siteDao().updateSite(site.copy(status = "STOPPED"))
            if (site.projectType == ProjectType.PHP_APP.name) {
                container.phpRuntimeManager.ensureSitePhpEnvironment(site)
            }
            if (wasRunning) {
                startSite(site)
            } else {
                _statusBanner.value = "تم تحديث إعدادات الموقع ${site.name}"
            }
        }
    }

    fun startSite(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (site.projectType.startsWith("PYTHON_") &&
                container.pythonRuntimeManager.findPythonBinary(site.runtimeVersion) != null
            ) {
                val pyRes = container.pythonRuntimeManager.startPythonWebApp(site, site.projectType)
                if (pyRes.isSuccess) {
                    db.siteDao().updateSiteStatus(site.id, "RUNNING", "")
                    _statusBanner.value = pyRes.getOrThrow()
                    refreshEngines()
                    return@launch
                }
            }
            val result = container.httpServer.startSite(site)
            result.onSuccess { url ->
                db.siteDao().updateSiteStatus(site.id, "RUNNING", "")
                HostingForegroundService.syncServiceState(app, container.httpServer.runningSiteIds.value.size)
                refreshEngines()
                _statusBanner.value = "تم تشغيل ${site.name} محلياً على $url"
            }.onFailure { err ->
                db.siteDao().updateSiteStatus(site.id, "ERROR", err.message ?: "خطأ تشغيل")
                _statusBanner.value = "فشل تشغيل ${site.name}: ${err.message}"
            }
        }
    }

    fun stopSite(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.httpServer.stopSite(site.id)
            container.runtimeEngine.stopService("py_site_${site.id}")
            db.siteDao().updateSiteStatus(site.id, "STOPPED", "")
            HostingForegroundService.syncServiceState(app, container.httpServer.runningSiteIds.value.size)
            refreshEngines()
            _statusBanner.value = "تم إيقاف الموقع ${site.name}"
        }
    }

    fun deleteSite(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.httpServer.stopSite(site.id)
            container.runtimeEngine.stopService("py_site_${site.id}")
            File(site.rootFolder).deleteRecursively()
            db.siteDao().deleteSite(site.id)
            if (_selectedSiteForFiles.value?.id == site.id) {
                _selectedSiteForFiles.value = null
                _currentDir.value = null
                _fileEntries.value = emptyList()
            }
            HostingForegroundService.syncServiceState(app, container.httpServer.runningSiteIds.value.size)
            refreshEngines()
            db.appLogDao().insert(
                AppLogEntity(level = "INFO", source = "SITES", message = "تم حذف الموقع '${site.name}' وملفاته")
            )
            _statusBanner.value = "تم حذف الموقع ${site.name}"
        }
    }

    // --- Domain Manager ---

    fun addOrUpdateDomain(
        rawDomain: String,
        site: SiteEntity,
        documentRoot: String,
        dnsA: String,
        dnsCname: String,
        dnsTxt: String,
        sslEnabled: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.domainManager.addOrUpdateDomain(
                rawDomain, site, documentRoot, dnsA, dnsCname, dnsTxt, sslEnabled
            )
            res.onSuccess { dom ->
                _statusBanner.value = if (dom.isLocalDomain) {
                    "تمت إضافة النطاق المحلي ${dom.domainName} (متاح محلياً فقط عند تشغيل خادم DNS أو المنفذ)"
                } else {
                    "تم حفظ النطاق ${dom.domainName} محلياً. تذكير: لا يصبح متاحاً عالمياً بدون ربط DNS عام أو نفق Tunnel."
                }
            }.onFailure {
                _statusBanner.value = it.message ?: "تعذر إضافة الدومين"
            }
        }
    }

    fun verifyDomainGlobalDns(domain: DomainEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = container.domainManager.verifyGlobalDnsResolution(domain)
            _statusBanner.value = if (updated.dnsResolvedGlobally) {
                "تم التحقق: ${updated.domainName} موجه إلى ${updated.dnsARecord}"
            } else {
                "النطاق ${updated.domainName} غير موجه عالمياً عبر DNS العام حالياً."
            }
        }
    }

    fun importDomainSslCert(domain: DomainEntity, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.domainManager.importSslCertificate(domain, uri)
            _statusBanner.value = res.fold(
                onSuccess = { "تم تثبيت شهادة SSL وتفعيل HTTPS للنطاق ${it.domainName}" },
                onFailure = { it.message ?: "شهادة SSL غير صالحة" }
            )
        }
    }

    fun linkDomainToTunnel(domain: DomainEntity, tunnelHost: String, linked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.domainManager.linkDomainToTunnel(domain, tunnelHost, linked)
            _statusBanner.value = if (linked) "تم ربط النطاق ${domain.domainName} بالنفق" else "تم فك الربط"
        }
    }

    fun deleteDomain(domain: DomainEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.domainManager.deleteDomain(domain)
            _statusBanner.value = "تم حذف النطاق ${domain.domainName}"
        }
    }

    // --- Project Installer & Requirements Installer ---

    fun analyzeSiteProject(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val report = container.projectInstaller.analyzeProjectDirectory(File(site.rootFolder), site)
            _currentAnalysisReport.value = report
            _statusBanner.value = "اكتمل تحليل المشروع: ${report.detectedScriptOrFramework}"
        }
    }

    fun importZipAndAnalyzeProject(site: SiteEntity, zipUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.projectInstaller.importZipIntoSiteAndAnalyze(site, zipUri)
            res.onSuccess { report ->
                _currentAnalysisReport.value = report
                _statusBanner.value = "تم استيراد الأرشيف وتحليل التوافق: ${report.detectedScriptOrFramework}"
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل استيراد وتحليل الأرشيف"
            }
        }
    }

    fun createProjectBackup(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.projectInstaller.createSafeBackup(site)
            res.onSuccess { dir ->
                _lastBackupDir.value = dir
                _statusBanner.value = "تم إنشاء نسخة احتياطية آمنة في ${dir.name}"
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل إنشاء النسخة الاحتياطية"
            }
        }
    }

    fun verifyProjectInstallation(site: SiteEntity) {
        val report = _currentAnalysisReport.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val backup = _lastBackupDir.value ?: container.projectInstaller.createSafeBackup(site).getOrNull()
            val (passed, detail) = container.projectInstaller.runPostInstallVerification(site, report, backup)
            _statusBanner.value = if (passed) "✅ $detail" else "⚠️ $detail"
            refreshEngines()
        }
    }

    fun restoreSafePermissionsForSite(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.restoreSafePermissions(File(site.rootFolder))
            _statusBanner.value = res.fold(
                onSuccess = { "تمت استعادة الصلاحيات الآمنة (755/644/700) لـ $it عنصر" },
                onFailure = { it.message ?: "تعذر ضبط الصلاحيات" }
            )
            if (_selectedSiteForFiles.value?.id == site.id) reloadCurrentDir()
        }
    }

    fun downloadAndInstallPackage(pkg: TrustedEnginePackage, httpsUrl: String, sha256: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.requirementsInstaller.downloadAndVerifyPackage(pkg, httpsUrl, sha256)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل التثبيت" })
            refreshEngines()
        }
    }

    fun importLocalRequirementPackage(pkg: TrustedEnginePackage, uri: Uri, optionalSha256: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.requirementsInstaller.importLocalPackageAndTest(pkg, uri, optionalSha256)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل تثبيت الحزمة" })
            refreshEngines()
        }
    }

    // --- Runtime Management (PHP & Python & Node.js) ---

    fun savePhpConfig(version: String, iniContent: String, enabledExtensions: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            container.phpRuntimeManager.savePhpIniAndExtensions(version, iniContent, enabledExtensions)
            _statusBanner.value = "تم حفظ إعدادات php.ini للإصدار PHP $version"
        }
    }

    fun applyComprehensivePhpPreset(version: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.phpRuntimeManager.applyComprehensiveWebPreset(version)
            _statusBanner.value = "تم تطبيق إعدادات التوافق الشاملة على PHP $version"
        }
    }

    fun probePhpModules(version: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.phpRuntimeManager.probeLoadedModules(version)
            res.onSuccess {
                _phpLoadedModules.value = it
                _statusBanner.value = "تم فحص ${it.size} إضافة محملة في PHP $version"
            }.onFailure {
                _phpLoadedModules.value = emptyList()
                _statusBanner.value = it.message ?: "تعذر فحص إضافات PHP"
            }
        }
    }

    fun importPhpRuntimeZip(version: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.phpRuntimeManager.importLocalRuntimeArchive(version, uri)
            res.onSuccess {
                _statusBanner.value = it
                refreshEngines()
            }.onFailure {
                _statusBanner.value = it.message ?: "تعذر تثبيت حزمة PHP"
                refreshEngines()
            }
        }
    }

    fun savePythonRequirements(version: String, requirementsText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.pythonRuntimeManager.saveRequirements(version, requirementsText)
            _statusBanner.value = "تم حفظ requirements.txt لإصدار Python $version"
        }
    }

    fun inspectPypiPackage(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.pythonRuntimeManager.inspectPypiPackageCompatibility(packageName)
            res.onSuccess {
                _pypiInspectionResult.value = it
                _statusBanner.value = "${it.name} ${it.version}: ${it.compatibilityNote}"
            }.onFailure {
                _statusBanner.value = it.message ?: "تعذر فحص الحزمة من PyPI"
            }
        }
    }

    fun pipInstallPackage(version: String, packageSpec: String, site: SiteEntity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.pythonRuntimeManager.pipInstallPackage(version, packageSpec, site)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل التثبيت" })
        }
    }

    fun pipUninstallPackage(version: String, packageName: String, site: SiteEntity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.pythonRuntimeManager.pipUninstallPackage(version, packageName, site)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل الحذف" })
        }
    }

    fun pipUpgradePackage(version: String, packageName: String, site: SiteEntity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.pythonRuntimeManager.pipUpgradePackage(version, packageName, site)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل التحديث" })
        }
    }

    fun installPythonRequirementsTxt(version: String, site: SiteEntity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = if (site != null) {
                File(site.rootFolder, "public_html/requirements.txt").takeIf { it.exists() }
                    ?: File(site.rootFolder, "requirements.txt")
            } else {
                container.pythonRuntimeManager.getRequirementsFile(version)
            }
            val res = container.pythonRuntimeManager.installFromRequirementsFile(version, file, site)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "فشل تثبيت المتطلبات" })
        }
    }

    fun createPythonVirtualEnv(site: SiteEntity, pythonVersion: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.pythonRuntimeManager.createVirtualEnv(site, pythonVersion)
            _statusBanner.value = res.fold(onSuccess = { it }, onFailure = { it.message ?: "تعذر إنشاء venv" })
        }
    }

    fun refreshPythonPackages(version: String, site: SiteEntity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            container.pythonRuntimeManager.refreshInstalledPackages(version, site)
        }
    }

    fun runPhpFullVerification(version: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val report = container.phpRuntimeManager.runPhpVerificationSuite(version, container.httpServer)
            val passedCount = report.steps.count { it.passed }
            _statusBanner.value = if (report.allPassed) {
                "اجتاز PHP $version جميع الاختبارات السبعة بنجاح (7/7)"
            } else {
                "اختبار PHP $version: نجح $passedCount من أصل 7 خطوات — راجع التقرير التشخيصي"
            }
            refreshEngines()
        }
    }

    fun runPythonFullVerification(version: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val report = container.pythonRuntimeManager.runPythonVerificationSuite(version)
            val passedCount = report.steps.count { it.passed }
            _statusBanner.value = if (report.allPassed) {
                "اجتاز Python $version جميع الاختبارات السبعة بنجاح (7/7)"
            } else {
                "اختبار Python $version: نجح $passedCount من أصل 7 خطوات — راجع التفاصيل"
            }
            refreshEngines()
        }
    }

    fun saveNodePackageJson(version: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.nodeRuntimeManager.savePackageJson(version, content)
            _statusBanner.value = "تم حفظ package.json لإصدار Node.js $version"
        }
    }

    // --- Databases & phpMyAdmin ---

    fun createDatabase(name: String, type: DbEngineType, username: String, password: String, privileges: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isBlank()) {
                _statusBanner.value = "يرجى إدخال اسم قاعدة البيانات"
                return@launch
            }
            val res = container.databaseManager.createDatabase(name, type, username, password, privileges)
            res.onSuccess {
                refreshEngines()
                _statusBanner.value = "تم إنشاء قاعدة البيانات ${it.name}"
            }.onFailure {
                _statusBanner.value = "فشل إنشاء قاعدة البيانات: ${it.message}"
            }
        }
    }

    fun deleteDatabase(entity: DatabaseInstanceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.databaseManager.deleteDatabase(entity)
            refreshEngines()
            _statusBanner.value = "تم حذف قاعدة البيانات ${entity.name}"
        }
    }

    fun updateDbUserPrivileges(entity: DatabaseInstanceEntity, username: String, password: String?, privileges: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.databaseManager.updateUserPrivileges(entity, username, password, privileges)
            _statusBanner.value = "تم تحديث صلاحيات ${entity.name}"
        }
    }

    fun inspectDatabaseTables(entity: DatabaseInstanceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (entity.engineType == DbEngineType.SQLITE.name) {
                _inspectedTables.value = container.databaseManager.inspectSqliteTables(entity.storageTarget)
            } else {
                _inspectedTables.value = emptyList()
            }
        }
    }

    fun runSqlQuery(entity: DatabaseInstanceEntity, sql: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.databaseManager.executeSqliteQuery(entity, sql)
            _lastSqlResult.value = res
            if (!res.isError && entity.engineType == DbEngineType.SQLITE.name) {
                _inspectedTables.value = container.databaseManager.inspectSqliteTables(entity.storageTarget)
            }
        }
    }

    fun importSqlIntoDb(entity: DatabaseInstanceEntity, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.databaseManager.importSqlFile(entity, uri)
            res.onSuccess {
                _statusBanner.value = it
                inspectDatabaseTables(entity)
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل استيراد ملف SQL"
            }
        }
    }

    fun exportSqlFromDb(entity: DatabaseInstanceEntity, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.databaseManager.exportSqlDump(entity, uri)
            _statusBanner.value = res.getOrElse { it.message ?: "فشل التصدير" }
        }
    }

    fun fetchPhpMyAdminReleases() {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.phpMyAdminManager.fetchAvailableReleases()
            _statusBanner.value = res.fold(
                onSuccess = { "تم جلب ${it.size} إصدار رسمي من phpMyAdmin" },
                onFailure = { it.message ?: "تعذر جلب إصدارات phpMyAdmin" }
            )
        }
    }

    fun downloadAndInstallPhpMyAdmin(release: PhpMyAdminRelease, phpVersion: String, host: String, port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.phpMyAdminManager.downloadAndInstallRelease(release, phpVersion, host, port)
            _statusBanner.value = res.fold(
                onSuccess = { it.verificationDetails },
                onFailure = { it.message ?: "فشل تثبيت phpMyAdmin" }
            )
            refreshEngines()
        }
    }

    fun startAndVerifyPhpMyAdmin(phpVersion: String, host: String, port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.phpMyAdminManager.startAndVerify(phpVersion, host, port)
            _statusBanner.value = res.fold(
                onSuccess = { it.verificationDetails },
                onFailure = { it.message ?: "فشل تشغيل phpMyAdmin" }
            )
            refreshEngines()
        }
    }

    fun stopPhpMyAdmin() {
        viewModelScope.launch(Dispatchers.IO) {
            container.phpMyAdminManager.stopPhpMyAdmin()
            _statusBanner.value = "تم إيقاف phpMyAdmin"
            refreshEngines()
        }
    }

    fun importPhpMyAdminZip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.phpMyAdminManager.importLocalZip(uri)
            res.onSuccess {
                refreshEngines()
                _statusBanner.value = it
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل استيراد phpMyAdmin"
            }
        }
    }

    // --- File Manager (Global Mode vs Strictly Site-Locked Mode) ---

    private fun activeJailRoot(): File? {
        return if (_isSiteLockedFileMode.value) {
            _selectedSiteForFiles.value?.let { File(it.rootFolder) }
        } else {
            _selectedSiteForFiles.value?.let { File(it.rootFolder) } ?: app.filesDir
        }
    }

    fun openGlobalFileManager() {
        _isSiteLockedFileMode.value = false
        _selectedSiteForFiles.value = null
        val globalRoot = File(app.filesDir, "sites").apply { mkdirs() }
        _currentDir.value = globalRoot
        reloadCurrentDir()
    }

    fun selectSiteForFiles(site: SiteEntity, lockToSite: Boolean = true) {
        _selectedSiteForFiles.value = site
        _isSiteLockedFileMode.value = lockToSite
        val root = File(site.rootFolder).apply { mkdirs() }
        _currentDir.value = root
        reloadCurrentDir()
    }

    fun navigateToDirectory(dirPath: String) {
        val jail = activeJailRoot() ?: return
        val safe = SecurityHelper.resolveSafeChild(jail, File(dirPath).relativeToOrSelf(jail).path) ?: jail
        _currentDir.value = safe
        reloadCurrentDir()
    }

    fun navigateUpDirectory(): Boolean {
        val jail = activeJailRoot()?.canonicalFile ?: return false
        val cur = _currentDir.value?.canonicalFile ?: return false
        if (cur == jail) return false
        val parent = cur.parentFile ?: return false
        _currentDir.value = if (parent.path.startsWith(jail.path)) parent else jail
        reloadCurrentDir()
        return true
    }

    fun reloadCurrentDir() {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            _fileEntries.value = container.fileManager.listDirectory(jail, dir)
        }
    }

    fun createFolder(name: String) {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.createFolder(jail, dir, name)
            _statusBanner.value = res.fold(onSuccess = { "تم إنشاء المجلد $name" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun createFile(name: String) {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.createFile(jail, dir, name)
            _statusBanner.value = res.fold(onSuccess = { "تم إنشاء الملف $name" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun renameFile(entry: FileEntry, newName: String) {
        val jail = activeJailRoot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.renameEntry(jail, entry, newName)
            _statusBanner.value = res.fold(onSuccess = { "تمت إعادة التسمية إلى $newName" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun deleteFile(entry: FileEntry) {
        val jail = activeJailRoot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.deleteEntry(jail, entry)
            _statusBanner.value = res.fold(onSuccess = { "تم حذف ${entry.name}" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun setClipboard(entry: FileEntry, isMove: Boolean) {
        _clipboardFile.value = entry.path to isMove
        _statusBanner.value = if (isMove) "تم تحديد ${entry.name} للنقل" else "تم نسخ ${entry.name} للحافظة"
    }

    fun pasteClipboard() {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: return
        val clip = _clipboardFile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.copyOrMoveEntry(jail, clip.first, dir.absolutePath, clip.second)
            res.onSuccess {
                if (clip.second) _clipboardFile.value = null
                _statusBanner.value = "تم إتمام العملية بنجاح"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشلت العملية"
            }
        }
    }

    fun uploadMultipleFiles(uris: List<Uri>) {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.uploadMultipleFiles(jail, dir, uris)
            res.onSuccess { count ->
                _statusBanner.value = "تم رفع $count ملف بنجاح"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل الرفع"
            }
        }
    }

    fun importAndExtractZip(uri: Uri) {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.importAndExtractZipFromUri(jail, dir, uri)
            res.onSuccess { count ->
                _statusBanner.value = "تم فك ضغط $count ملف بنجاح"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل فك الضغط"
            }
        }
    }

    fun extractLocalZip(entry: FileEntry) {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.extractLocalZip(jail, entry, dir)
            res.onSuccess {
                _statusBanner.value = "تم فك ضغط $it ملف"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل فك الضغط"
            }
        }
    }

    fun compressEntryToZip(entry: FileEntry) {
        val jail = activeJailRoot() ?: return
        val dir = _currentDir.value ?: jail
        viewModelScope.launch(Dispatchers.IO) {
            val outZip = File(dir, "${entry.name}.zip")
            val res = container.fileManager.compressToZip(jail, listOf(File(entry.path)), outZip)
            res.onSuccess {
                _statusBanner.value = "تم إنشاء الأرشيف ${it.name}"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل الضغط"
            }
        }
    }

    suspend fun loadFileText(path: String): Result<String> {
        val jail = activeJailRoot() ?: return Result.failure(IllegalStateException("لا يوجد مسار محدد"))
        return container.fileManager.readTextFile(jail, path)
    }

    fun saveFileText(path: String, content: String) {
        val jail = activeJailRoot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.writeTextFile(jail, path, content)
            _statusBanner.value = res.fold(onSuccess = { "تم حفظ التعديلات بنجاح" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun updateUnixPermissions(entry: FileEntry, octalMode: String) {
        val jail = activeJailRoot() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.setUnixPermissions(jail, entry.path, octalMode)
            _statusBanner.value = res.fold(onSuccess = { "تم تحديث الصلاحيات إلى $octalMode" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    // --- Tunnel Sharing ---

    fun saveAndPrepareTunnel(
        siteId: String,
        provider: TunnelProvider,
        endpoint: String,
        enforceHttps: Boolean,
        requireAuth: Boolean,
        username: String,
        password: String?,
        allowedIpCidr: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            container.tunnelManager.saveTunnelConfig(
                siteId = siteId,
                provider = provider,
                controlEndpoint = endpoint,
                enforceHttps = enforceHttps,
                requireAuth = requireAuth,
                authUsername = username,
                authPasswordPlain = password,
                allowedIpCidr = allowedIpCidr
            )
            _statusBanner.value = "تم حفظ إعدادات الحماية والمشاركة"
        }
    }

    fun startTunnelWithApproval(config: TunnelConfigEntity, sitePort: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = container.tunnelManager.startSharingWithApproval(
                config = config,
                sitePort = sitePort,
                explicitUserApproval = true
            )
            _statusBanner.value = state.detailMessage
        }
    }

    fun stopTunnel(config: TunnelConfigEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.tunnelManager.stopSharing(config)
            _statusBanner.value = "تم إيقاف المشاركة عبر الإنترنت"
        }
    }

    // --- Security & Logs ---

    fun saveSecurityPin(enabled: Boolean, newPin: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = db.securitySettingsDao().getSettings() ?: SecuritySettingsEntity()
            val hash = if (!newPin.isNullOrBlank()) SecurityHelper.hashSecret(newPin) else current.adminPinHash
            db.securitySettingsDao().upsert(
                current.copy(
                    adminPinEnabled = enabled && hash.isNotBlank(),
                    adminPinHash = hash
                )
            )
            db.appLogDao().insert(
                AppLogEntity(
                    level = "SECURITY",
                    source = "SECURITY",
                    message = if (enabled) "تم تفعيل حماية واجهة الإدارة برمز PIN" else "تم تعطيل قفل واجهة الإدارة"
                )
            )
            _statusBanner.value = "تم تحديث إعدادات الأمان"
        }
    }

    fun updateSecurityToggles(localhostOnly: Boolean, requireApproval: Boolean, logRequests: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = db.securitySettingsDao().getSettings() ?: SecuritySettingsEntity()
            db.securitySettingsDao().upsert(
                current.copy(
                    localhostOnlyByDefault = localhostOnly,
                    requireApprovalBeforeShare = requireApproval,
                    logAllRequests = logRequests
                )
            )
        }
    }

    fun unlockAdmin(pin: String): Boolean {
        val current = securitySettings.value ?: return true
        if (!current.adminPinEnabled || current.adminPinHash.isBlank()) {
            _isAdminLocked.value = false
            return true
        }
        val ok = SecurityHelper.verifySecret(pin, current.adminPinHash)
        if (ok) {
            _isAdminLocked.value = false
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                db.appLogDao().insert(
                    AppLogEntity(level = "SECURITY", source = "SECURITY", message = "محاولة دخول خاطئة لواجهة الإدارة")
                )
            }
        }
        return ok
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            db.appLogDao().clearAll()
            _statusBanner.value = "تم مسح سجل العمليات"
        }
    }

    private fun buildStarterContent(siteName: String, type: ProjectType, port: Int): String = when (type) {
        ProjectType.STATIC_HTML -> """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>$siteName — استضافة أبوطلال</title>
              <style>
                body { font-family: sans-serif; background: #0a2842; color: #fff; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
                .card { background: #fff; color: #0b1c33; padding: 32px; border-radius: 18px; max-width: 520px; border-top: 6px solid #0d47a1; box-shadow: 0 20px 50px rgba(0,0,0,0.35); }
                h1 { color: #0d47a1; margin-top: 0; }
                .badge { display: inline-block; background: #c62828; color: #fff; padding: 4px 12px; border-radius: 999px; font-size: 12px; font-weight: bold; }
              </style>
            </head>
            <body>
              <div class="card">
                <span class="badge">ABOTALAL HOSTING</span>
                <h1>مرحباً بك في موقع: $siteName</h1>
                <p>يعمل هذا الموقع محلياً بدون إنترنت داخل مجلد public_html المعزول.</p>
              </div>
            </body>
            </html>
        """.trimIndent()

        ProjectType.PHP_APP -> """
            <?php
            header('Content-Type: text/html; charset=utf-8');
            echo "<h1>$siteName — PHP " . phpversion() . "</h1>";
            echo "<p>يعمل بنجاح على استضافة أبوطلال.</p>";
            ?>
        """.trimIndent()

        ProjectType.PYTHON_FLASK -> """
            from flask import Flask
            app = Flask(__name__)

            @app.route("/")
            def index():
                return "<h1>$siteName - Flask on Abotalal Hosting</h1>"

            if __name__ == "__main__":
                app.run(host="127.0.0.1", port=$port)
        """.trimIndent()

        ProjectType.PYTHON_DJANGO -> """
            #!/usr/bin/env python3
            import os
            import sys

            def main():
                os.environ.setdefault("DJANGO_SETTINGS_MODULE", "project.settings")
                print("Django runner ready for $siteName")

            if __name__ == "__main__":
                main()
        """.trimIndent()

        ProjectType.PYTHON_FASTAPI -> """
            from fastapi import FastAPI
            app = FastAPI(title="$siteName")

            @app.get("/")
            def read_root():
                return {"service": "$siteName", "host": "Abotalal Hosting"}
        """.trimIndent()

        ProjectType.NODE_SOCKETIO -> """
            const http = require('http');
            const server = http.createServer((req, res) => {
              res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
              res.end('<h1>$siteName - Node.js / Socket.io</h1>');
            });
            server.listen($port, '127.0.0.1');
        """.trimIndent()
    }
}
