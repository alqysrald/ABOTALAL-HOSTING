package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AbotalalHostingApp
import com.example.core.database.AppLogEntity
import com.example.core.database.DatabaseInstanceEntity
import com.example.core.database.RuntimeConfigEntity
import com.example.core.database.SecuritySettingsEntity
import com.example.core.database.SiteEntity
import com.example.core.database.TunnelConfigEntity
import com.example.core.model.DbEngineType
import com.example.core.model.EngineInfo
import com.example.core.model.FileEntry
import com.example.core.model.ProjectType
import com.example.core.model.SqlQueryResult
import com.example.core.model.SqliteTableInfo
import com.example.core.model.SystemMetrics
import com.example.core.model.TransferProgress
import com.example.core.model.TunnelProvider
import com.example.core.security.SecurityHelper
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

    private val _engines = MutableStateFlow<List<EngineInfo>>(emptyList())
    val engines: StateFlow<List<EngineInfo>> = _engines.asStateFlow()

    private val _metrics = MutableStateFlow(SystemMetrics())
    val metrics: StateFlow<SystemMetrics> = _metrics.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isAdminLocked = MutableStateFlow(false)
    val isAdminLocked: StateFlow<Boolean> = _isAdminLocked.asStateFlow()

    // File Manager State
    private val _selectedSiteForFiles = MutableStateFlow<SiteEntity?>(null)
    val selectedSiteForFiles: StateFlow<SiteEntity?> = _selectedSiteForFiles.asStateFlow()

    private val _currentDir = MutableStateFlow<File?>(null)
    val currentDir: StateFlow<File?> = _currentDir.asStateFlow()

    private val _fileEntries = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileEntries: StateFlow<List<FileEntry>> = _fileEntries.asStateFlow()

    private val _clipboardFile = MutableStateFlow<Pair<String, Boolean>?>(null) // path to isMove
    val clipboardFile: StateFlow<Pair<String, Boolean>?> = _clipboardFile.asStateFlow()

    // SQLite Inspector State
    private val _inspectedTables = MutableStateFlow<List<SqliteTableInfo>>(emptyList())
    val inspectedTables: StateFlow<List<SqliteTableInfo>> = _inspectedTables.asStateFlow()

    private val _lastSqlResult = MutableStateFlow<SqlQueryResult?>(null)
    val lastSqlResult: StateFlow<SqlQueryResult?> = _lastSqlResult.asStateFlow()

    init {
        startTelemetryLoop()
        refreshEngines()
        viewModelScope.launch {
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
                val running = container.httpServer.runningSiteIds.value.isNotEmpty()
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

    fun refreshEngines() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = listOf(
                container.httpServer.getHttpEngineStatus(),
                container.phpRuntimeManager.probeStatus(),
                container.pythonRuntimeManager.probeStatus(),
                container.mysqlModule.probeEngine(),
                container.databaseManager.getSqliteEngineStatus(),
                container.postgresModule.probeEngine(),
                container.databaseManager.probePhpMyAdminStatus()
            )
            _engines.value = list
        }
    }

    // --- Sites Management ---

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
            val rootDir = File(app.filesDir, "sites/$id").apply { mkdirs() }
            val actualEntry = entryFile.ifBlank { projectType.defaultEntry }
            val entryTarget = File(rootDir, actualEntry)
            if (!entryTarget.exists()) {
                entryTarget.writeText(buildStarterContent(name, projectType))
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
            db.siteDao().upsertSite(site)
            db.appLogDao().insert(
                AppLogEntity(level = "INFO", source = "SITES", message = "تم إنشاء موقع معزول '$name' على المنفذ $port")
            )
            _statusBanner.value = "تم إنشاء الموقع $name بنجاح"
        }
    }

    fun updateSiteConfig(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = runningSiteIds.value.contains(site.id)
            if (wasRunning) {
                container.httpServer.stopSite(site.id)
            }
            db.siteDao().updateSite(site.copy(status = "STOPPED"))
            if (wasRunning) {
                startSite(site)
            } else {
                _statusBanner.value = "تم تحديث إعدادات الموقع ${site.name}"
            }
        }
    }

    fun startSite(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = container.httpServer.startSite(site)
            result.onSuccess { url ->
                db.siteDao().updateSiteStatus(site.id, "RUNNING", "")
                HostingForegroundService.syncServiceState(app, container.httpServer.runningSiteIds.value.size)
                refreshEngines()
                _statusBanner.value = "تم تشغيل ${site.name} على $url"
            }.onFailure { err ->
                db.siteDao().updateSiteStatus(site.id, "ERROR", err.message ?: "خطأ تشغيل")
                _statusBanner.value = "فشل تشغيل ${site.name}: ${err.message}"
            }
        }
    }

    fun stopSite(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.httpServer.stopSite(site.id)
            db.siteDao().updateSiteStatus(site.id, "STOPPED", "")
            HostingForegroundService.syncServiceState(app, container.httpServer.runningSiteIds.value.size)
            refreshEngines()
            _statusBanner.value = "تم إيقاف الموقع ${site.name}"
        }
    }

    fun deleteSite(site: SiteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.httpServer.stopSite(site.id)
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

    // --- Runtime Management (PHP & Python) ---

    fun savePhpConfig(version: String, iniContent: String, enabledExtensions: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            container.phpRuntimeManager.savePhpIniAndExtensions(version, iniContent, enabledExtensions)
            _statusBanner.value = "تم حفظ إعدادات php.ini للإصدار PHP $version"
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

    // --- Databases ---

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

    fun importPhpMyAdminZip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.databaseManager.importPhpMyAdminArchive(uri)
            res.onSuccess {
                refreshEngines()
                _statusBanner.value = it
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل استيراد phpMyAdmin"
            }
        }
    }

    // --- File Manager ---

    fun selectSiteForFiles(site: SiteEntity) {
        _selectedSiteForFiles.value = site
        val root = File(site.rootFolder).apply { mkdirs() }
        _currentDir.value = root
        reloadCurrentDir()
    }

    fun navigateToDirectory(dirPath: String) {
        val site = _selectedSiteForFiles.value ?: return
        val root = File(site.rootFolder)
        val safe = SecurityHelper.resolveSafeChild(root, File(dirPath).relativeToOrSelf(root).path) ?: root
        _currentDir.value = safe
        reloadCurrentDir()
    }

    fun navigateUpDirectory(): Boolean {
        val site = _selectedSiteForFiles.value ?: return false
        val root = File(site.rootFolder).canonicalFile
        val cur = _currentDir.value?.canonicalFile ?: return false
        if (cur == root) return false
        val parent = cur.parentFile ?: return false
        _currentDir.value = if (parent.path.startsWith(root.path)) parent else root
        reloadCurrentDir()
        return true
    }

    fun reloadCurrentDir() {
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: File(site.rootFolder)
        viewModelScope.launch(Dispatchers.IO) {
            _fileEntries.value = container.fileManager.listDirectory(File(site.rootFolder), dir)
        }
    }

    fun createFolder(name: String) {
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: File(site.rootFolder)
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.createFolder(File(site.rootFolder), dir, name)
            _statusBanner.value = res.fold(onSuccess = { "تم إنشاء المجلد $name" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun createFile(name: String) {
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: File(site.rootFolder)
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.createFile(File(site.rootFolder), dir, name)
            _statusBanner.value = res.fold(onSuccess = { "تم إنشاء الملف $name" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun renameFile(entry: FileEntry, newName: String) {
        val site = _selectedSiteForFiles.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.renameEntry(File(site.rootFolder), entry, newName)
            _statusBanner.value = res.fold(onSuccess = { "تمت إعادة التسمية إلى $newName" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun deleteFile(entry: FileEntry) {
        val site = _selectedSiteForFiles.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.deleteEntry(File(site.rootFolder), entry)
            _statusBanner.value = res.fold(onSuccess = { "تم حذف ${entry.name}" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun setClipboard(entry: FileEntry, isMove: Boolean) {
        _clipboardFile.value = entry.path to isMove
        _statusBanner.value = if (isMove) "تم تحديد ${entry.name} للنقل" else "تم نسخ ${entry.name} للحافظة"
    }

    fun pasteClipboard() {
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: return
        val clip = _clipboardFile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.copyOrMoveEntry(File(site.rootFolder), clip.first, dir.absolutePath, clip.second)
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
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: File(site.rootFolder)
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.uploadMultipleFiles(File(site.rootFolder), dir, uris)
            res.onSuccess { count ->
                _statusBanner.value = "تم رفع $count ملف بنجاح"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل الرفع"
            }
        }
    }

    fun importAndExtractZip(uri: Uri) {
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: File(site.rootFolder)
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.importAndExtractZipFromUri(File(site.rootFolder), dir, uri)
            res.onSuccess { count ->
                _statusBanner.value = "تم فك ضغط $count ملف بنجاح"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل فك الضغط"
            }
        }
    }

    fun extractLocalZip(entry: FileEntry) {
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: File(site.rootFolder)
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.extractLocalZip(File(site.rootFolder), entry, dir)
            res.onSuccess {
                _statusBanner.value = "تم فك ضغط $it ملف"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل فك الضغط"
            }
        }
    }

    fun compressEntryToZip(entry: FileEntry) {
        val site = _selectedSiteForFiles.value ?: return
        val dir = _currentDir.value ?: File(site.rootFolder)
        viewModelScope.launch(Dispatchers.IO) {
            val outZip = File(dir, "${entry.name}.zip")
            val res = container.fileManager.compressToZip(File(site.rootFolder), listOf(File(entry.path)), outZip)
            res.onSuccess {
                _statusBanner.value = "تم إنشاء الأرشيف ${it.name}"
                reloadCurrentDir()
            }.onFailure {
                _statusBanner.value = it.message ?: "فشل الضغط"
            }
        }
    }

    suspend fun loadFileText(path: String): Result<String> {
        val site = _selectedSiteForFiles.value ?: return Result.failure(IllegalStateException("لا يوجد موقع محدد"))
        return container.fileManager.readTextFile(File(site.rootFolder), path)
    }

    fun saveFileText(path: String, content: String) {
        val site = _selectedSiteForFiles.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.writeTextFile(File(site.rootFolder), path, content)
            _statusBanner.value = res.fold(onSuccess = { "تم حفظ التعديلات بنجاح" }, onFailure = { it.message })
            reloadCurrentDir()
        }
    }

    fun updateUnixPermissions(entry: FileEntry, octalMode: String) {
        val site = _selectedSiteForFiles.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.fileManager.setUnixPermissions(File(site.rootFolder), entry.path, octalMode)
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

    private fun buildStarterContent(siteName: String, type: ProjectType): String = when (type) {
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
                <p>يعمل هذا الموقع مباشرة من هاتف أندرويد عبر محرك استضافة أبوطلال المحلي المعزول.</p>
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
                app.run(host="127.0.0.1", port=8000)
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
    }
}
