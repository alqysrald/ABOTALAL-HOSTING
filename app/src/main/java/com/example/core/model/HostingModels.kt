package com.example.core.model

enum class EngineType(
    val titleAr: String,
    val titleEn: String,
    val defaultPort: Int,
    val isBuiltInNative: Boolean
) {
    HTTP_SERVER("خادم HTTP / Rewrite المحلي", "HTTP/1.1 + .htaccess Server", 8080, true),
    PHP("بيئة تشغيل PHP", "PHP Runtime (CGI/FPM)", 9000, false),
    PYTHON("بيئة تشغيل Python", "Python Runtime (WSGI/ASGI)", 8000, false),
    NODEJS("بيئة تشغيل Node.js / Socket.io", "Node.js + Socket.io Runtime", 3000, false),
    MYSQL_MARIADB("قاعدة بيانات MySQL / MariaDB", "MySQL / MariaDB Server", 3306, false),
    SQLITE("محرك SQLite المحلي", "SQLite Embedded", 0, true),
    POSTGRESQL("قاعدة بيانات PostgreSQL", "PostgreSQL", 5432, false),
    PHPMYADMIN("واجهة phpMyAdmin", "phpMyAdmin Web UI", 8090, false)
}

enum class EngineStatus(val labelAr: String) {
    RUNNING("يعمل (متحقق منه)"),
    STOPPED("متوقف"),
    NOT_INSTALLED("غير مثبت"),
    INSTALLING("جاري التجهيز..."),
    ERROR("خطأ في التشغيل")
}

enum class ProjectType(val titleAr: String, val defaultEntry: String, val requiredEngine: EngineType) {
    STATIC_HTML("موقع ويب ثابت (HTML/CSS/JS)", "index.html", EngineType.HTTP_SERVER),
    PHP_APP("تطبيق ويب PHP متكامل", "index.php", EngineType.PHP),
    PYTHON_FLASK("تطبيق Python Flask", "app.py", EngineType.PYTHON),
    PYTHON_DJANGO("مشروع Python Django", "manage.py", EngineType.PYTHON),
    PYTHON_FASTAPI("واجهة Python FastAPI", "main.py", EngineType.PYTHON),
    NODE_SOCKETIO("خادم Node.js / Socket.io", "server.js", EngineType.NODEJS)
}

enum class DbEngineType(val titleAr: String, val defaultPort: Int) {
    SQLITE("SQLite (محلي مدمج)", 0),
    MYSQL_MARIADB("MySQL / MariaDB", 3306),
    POSTGRESQL("PostgreSQL", 5432)
}

enum class TunnelProvider(val titleAr: String, val requiresExternalBinary: Boolean) {
    CLOUDFLARED("نفق Cloudflare (cloudflared)", true),
    SSH_REVERSE("نفق SSH عكسي مشفر", true),
    CUSTOM_REVERSE_PROXY("وسيط HTTPS مخصص (Reverse Proxy)", false)
}

enum class TunnelStatus(val labelAr: String) {
    DISCONNECTED("غير متصل"),
    AWAITING_APPROVAL("بانتظار موافقة المستخدم"),
    AWAITING_CLIENT("يتطلب تثبيت عميل النفق"),
    CONNECTING("جاري الاتصال..."),
    CONNECTED("متصل وآمن"),
    ERROR("فشل الاتصال")
}

enum class CheckSeverity { OK, WARNING, MISSING }

data class CompatibilityCheckItem(
    val title: String,
    val detail: String,
    val severity: CheckSeverity,
    val missingEngineType: EngineType? = null,
    val suggestedPackageQuery: String? = null
)

data class ProjectAnalysisReport(
    val projectPath: String,
    val detectedScriptOrFramework: String,
    val primaryLanguage: String,
    val recommendedProjectType: ProjectType,
    val requiredPhpVersion: String?,
    val requiredPythonVersion: String?,
    val requiredNodeVersion: String?,
    val hasComposerJson: Boolean,
    val hasPackageJson: Boolean,
    val hasRequirementsTxt: Boolean,
    val hasHtaccess: Boolean,
    val requiresSocketIo: Boolean,
    val detectedConfigFiles: List<String>,
    val requiredPhpExtensions: List<String>,
    val detectedPythonPackages: List<String> = emptyList(),
    val detectedNodePackages: List<String> = emptyList(),
    val detectedDatabases: List<String>,
    val detectedUploadDirs: List<String>,
    val rewriteRulesCount: Int,
    val checks: List<CompatibilityCheckItem>,
    val isFullyCompatible: Boolean,
    val analyzedAt: Long = System.currentTimeMillis()
)

enum class PackageInstallState(val labelAr: String) {
    NOT_INSTALLED("غير مثبت"),
    DOWNLOADING("جاري التنزيل"),
    VERIFYING("جاري التحقق من السلامة"),
    INSTALLING("جاري التثبيت"),
    INSTALLED_UNVERIFIED("مثبت (غير مختبر)"),
    VERIFIED("مثبت ومتحقق منه"),
    FAILED("فشل التثبيت")
}

data class AndroidPackageSpec(
    val id: String,
    val programName: String,
    val engineType: EngineType,
    val version: String,
    val abi: String,
    val minAndroidApi: Int,
    val repositoryName: String,
    val officialUrl: String,
    val sha256: String,
    val downloadSizeBytes: Long,
    val dependencies: List<String>,
    val installState: PackageInstallState = PackageInstallState.NOT_INSTALLED,
    val verificationSummary: String = "",
    val androidCompatibilityNote: String = "",
    val isAvailableForInstall: Boolean = false
) {
    val approxSizeLabel: String
        get() = when {
            downloadSizeBytes <= 0L -> "غير محدد"
            downloadSizeBytes < 1024L * 1024L -> "${downloadSizeBytes / 1024L} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", downloadSizeBytes / (1024.0 * 1024.0))
        }
}

data class DownloadTaskState(
    val taskId: String,
    val packageId: String,
    val title: String,
    val stageLabel: String,
    val downloadProgress: Float = 0f,
    val installProgress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val isDownloading: Boolean = false,
    val isInstalling: Boolean = false,
    val isCancelled: Boolean = false,
    val isFinished: Boolean = false,
    val isVerified: Boolean = false,
    val errorDetails: String? = null,
    val verificationResult: String? = null,
    val retryUrl: String = "",
    val retrySha256: String = ""
)

data class TrustedEnginePackage(
    val id: String,
    val engineType: EngineType,
    val version: String,
    val abi: String,
    val descriptionAr: String,
    val officialDownloadUrl: String,
    val expectedSha256: String,
    val approxSizeLabel: String
)

data class PackageInstallProgress(
    val packageId: String,
    val stageLabel: String,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0L,
    val installProgress: Float = 0f,
    val isFinished: Boolean = false,
    val isSuccess: Boolean = false,
    val isCancelled: Boolean = false,
    val errorMessage: String? = null,
    val verificationDetails: String? = null
)

data class PythonPackageInfo(
    val name: String,
    val version: String,
    val summary: String = "",
    val isPurePythonWheel: Boolean = false,
    val wheelUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val dependencies: List<String> = emptyList(),
    val compatibilityNote: String = "",
    val isInstalled: Boolean = false,
    val installError: String? = null
)

data class PythonCheckStep(
    val stepNumber: Int,
    val title: String,
    val probeTarget: String,
    val passed: Boolean,
    val detail: String
)

data class PythonVerificationReport(
    val pythonVersion: String,
    val abi: String,
    val steps: List<PythonCheckStep>,
    val allPassed: Boolean,
    val checkedAt: Long = System.currentTimeMillis()
)

data class PhpMyAdminRelease(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minPhpVersion: String,
    val releaseDate: String
)

data class PhpMyAdminStatus(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isVerified: Boolean = false,
    val installedVersion: String = "غير مثبت",
    val selectedPhpVersion: String = "8.4",
    val mysqlHost: String = "127.0.0.1",
    val mysqlPort: Int = 3306,
    val localUrl: String = "http://127.0.0.1:8090",
    val missingPhpExtensions: List<String> = emptyList(),
    val mysqlReachable: Boolean = false,
    val loginPageVerified: Boolean = false,
    val verificationDetails: String = "",
    val availableReleases: List<PhpMyAdminRelease> = emptyList()
)

data class EngineInfo(
    val type: EngineType,
    val status: EngineStatus,
    val activeVersion: String,
    val availableVersions: List<String>,
    val port: Int,
    val binaryPath: String?,
    val architecture: String,
    val verificationDetails: String,
    val requirementNotice: String,
    val lastCheckedAt: Long
)

data class SystemMetrics(
    val cpuUsagePercent: Float = 0f,
    val ramUsedMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val storageUsedBytes: Long = 0L,
    val storageFreeBytes: Long = 0L,
    val storageTotalBytes: Long = 0L,
    val activeConnections: Int = 0,
    val localIpAddress: String = "127.0.0.1",
    val deviceAbi: String = "",
    val androidVersion: String = "",
    val isServerRunning: Boolean = false,
    val isOfflineReady: Boolean = true
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val permissionsOctal: String,
    val permissionsSymbolic: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canExecute: Boolean
)

data class TransferProgress(
    val operationLabel: String,
    val currentFileName: String,
    val processedBytes: Long,
    val totalBytes: Long,
    val processedCount: Int,
    val totalCount: Int,
    val progress: Float,
    val isCompleted: Boolean = false,
    val error: String? = null
)

data class SqlQueryResult(
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val affectedRows: Int = 0,
    val message: String = "",
    val isError: Boolean = false,
    val executionTimeMs: Long = 0L
)

data class SqliteTableInfo(
    val name: String,
    val rowCount: Long,
    val columns: List<SqliteColumnInfo>
)

data class SqliteColumnInfo(
    val name: String,
    val type: String,
    val isPrimaryKey: Boolean,
    val notNull: Boolean
)
