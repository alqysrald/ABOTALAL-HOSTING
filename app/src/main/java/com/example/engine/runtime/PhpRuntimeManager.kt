package com.example.engine.runtime

import android.content.Context
import android.net.Uri
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.RuntimeConfigDao
import com.example.core.database.RuntimeConfigEntity
import com.example.core.database.SiteEntity
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.engine.core.ArchiveExtractor
import com.example.engine.core.ManagedServiceSpec
import com.example.engine.core.ProcessProbe
import com.example.engine.core.RuntimeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PhpRuntimeManager(
    private val context: Context,
    private val runtimeConfigDao: RuntimeConfigDao,
    private val runtimeEngine: RuntimeEngine,
    private val logDao: AppLogDao
) {
    val supportedVersions = listOf("8.4", "8.3", "8.2", "8.1")

    val standardExtensions = listOf(
        "mysqli",
        "pdo_mysql",
        "mbstring",
        "curl",
        "gd",
        "fileinfo",
        "zip",
        "openssl",
        "pdo_sqlite",
        "sqlite3",
        "intl"
    )

    val comprehensiveWebExtensions = listOf(
        "mysqli", "pdo_mysql", "mbstring", "curl", "gd", "fileinfo", "zip", "openssl", "pdo_sqlite", "sqlite3"
    )

    suspend fun ensureDefaultConfigs() {
        supportedVersions.forEach { ver ->
            val id = "php_$ver"
            val iniFile = getPhpIniFile(ver)
            iniFile.parentFile?.mkdirs()
            if (!iniFile.exists()) {
                iniFile.writeText(buildDefaultPhpIni(ver, comprehensiveWebExtensions))
            }
            if (runtimeConfigDao.getById(id) == null) {
                val bin = findPhpBinary(ver)
                runtimeConfigDao.upsert(
                    RuntimeConfigEntity(
                        id = id,
                        engineType = EngineType.PHP.name,
                        version = ver,
                        installPath = runtimeEngine.getRuntimeDir(EngineType.PHP, ver).absolutePath,
                        configText = iniFile.readText(),
                        extensionsCsv = comprehensiveWebExtensions.joinToString(","),
                        port = 9000,
                        isInstalled = bin != null
                    )
                )
            }
        }
    }

    fun getPhpIniFile(version: String): File =
        File(runtimeEngine.getRuntimeDir(EngineType.PHP, version), "php.ini")

    fun getSitePhpIniFile(site: SiteEntity): File =
        File(site.rootFolder, "config/php.ini")

    fun findPhpBinary(version: String): File? =
        ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php/$version")
            ?: ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php")

    suspend fun ensureSitePhpEnvironment(site: SiteEntity): File = withContext(Dispatchers.IO) {
        val siteIni = getSitePhpIniFile(site)
        siteIni.parentFile?.mkdirs()
        if (!siteIni.exists()) {
            val globalIni = getPhpIniFile(site.runtimeVersion)
            val content = if (globalIni.exists()) {
                globalIni.readText()
            } else {
                buildDefaultPhpIni(site.runtimeVersion, comprehensiveWebExtensions)
            }
            siteIni.writeText(content)
        }
        siteIni
    }

    suspend fun probeLoadedModules(version: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val bin = ProcessProbe.findBinary(context, listOf("php", "php-cgi"), "php/$version")
            ?: ProcessProbe.findBinary(context, listOf("php", "php-cgi"), "php")
            ?: return@withContext Result.failure(IllegalStateException("ملف PHP غير مثبت للإصدار $version"))

        val env = runtimeEngine.buildEnvironment(EngineType.PHP, version)
        val res = runtimeEngine.executeCommand(
            command = listOf(bin.absolutePath, "-c", getPhpIniFile(version).absolutePath, "-m"),
            workingDir = runtimeEngine.getRuntimeDir(EngineType.PHP, version),
            env = env,
            timeoutSeconds = 10L
        )
        if (!res.isSuccess) {
            return@withContext Result.failure(IllegalStateException(res.stderr.ifBlank { "تعذر فحص إضافات PHP" }))
        }
        val modules = res.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") }
        Result.success(modules)
    }

    suspend fun startPhpService(version: String, port: Int = 9000): Result<String> = withContext(Dispatchers.IO) {
        val bin = findPhpBinary(version)
            ?: return@withContext Result.failure(IllegalStateException("محرك PHP $version غير مثبت لمعمارية ${ProcessProbe.getDeviceAbi()}"))

        val runtimeDir = runtimeEngine.getRuntimeDir(EngineType.PHP, version)
        val iniFile = getPhpIniFile(version)
        val env = runtimeEngine.buildEnvironment(EngineType.PHP, version)
        val cmd = if (bin.name.contains("cgi")) {
            listOf(bin.absolutePath, "-c", iniFile.absolutePath, "-b", "127.0.0.1:$port")
        } else {
            val docRoot = File(context.filesDir, "sites").apply { mkdirs() }
            listOf(bin.absolutePath, "-c", iniFile.absolutePath, "-S", "127.0.0.1:$port", "-t", docRoot.absolutePath)
        }
        val res = runtimeEngine.startService(
            ManagedServiceSpec(
                serviceId = "php_$version",
                engineType = EngineType.PHP,
                version = version,
                command = cmd,
                workingDir = runtimeDir,
                env = env,
                listenPort = port
            )
        )
        res.map { "خدمة PHP $version تعمل على المنفذ $port" }
    }

    suspend fun stopPhpService(version: String) {
        runtimeEngine.stopService("php_$version")
    }

    suspend fun restartPhpService(version: String, port: Int = 9000): Result<String> {
        stopPhpService(version)
        return startPhpService(version, port)
    }

    suspend fun probeStatus(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        var foundVersion: String? = null
        var foundBinary: File? = null
        var probeDetails = ""
        var activeRunning = false

        for (ver in supportedVersions) {
            if (runtimeEngine.isServiceAlive("php_$ver") || ProcessProbe.isTcpPortListening("127.0.0.1", 9000, 150)) {
                activeRunning = true
            }
            val bin = findPhpBinary(ver)
            if (bin != null) {
                val env = runtimeEngine.buildEnvironment(EngineType.PHP, ver)
                val res = runtimeEngine.executeCommand(listOf(bin.absolutePath, "-v"), bin.parentFile ?: runtimeEngine.runtimesRoot, env, 6L)
                foundBinary = bin
                val firstLine = res.stdout.lineSequence().firstOrNull().orEmpty()
                foundVersion = if (res.isSuccess && firstLine.isNotBlank()) "$ver ($firstLine)" else ver
                probeDetails = if (res.isSuccess) {
                    "تم التحقق التنفيذي: $firstLine"
                } else {
                    res.stderr.ifBlank { "تعذر التنفيذ" }
                }
                break
            }
        }

        val status = when {
            activeRunning && foundBinary != null -> EngineStatus.RUNNING
            foundBinary != null && probeDetails.startsWith("تم التحقق") -> EngineStatus.STOPPED
            foundBinary != null -> EngineStatus.ERROR
            else -> EngineStatus.NOT_INSTALLED
        }

        return EngineInfo(
            type = EngineType.PHP,
            status = status,
            activeVersion = foundVersion ?: "غير مثبت",
            availableVersions = supportedVersions,
            port = 9000,
            binaryPath = foundBinary?.absolutePath,
            architecture = abi,
            verificationDetails = if (probeDetails.isNotBlank()) probeDetails else "لا يوجد ملف تنفيذي php / php-cgi متوافق مع $abi في مسار التشغيل.",
            requirementNotice = "يدعم PHP 8.4 / 8.3 / 8.2 / 8.1 وإدارة php.ini و php -m وبيئات مستقلة لكل موقع.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    suspend fun savePhpIniAndExtensions(
        version: String,
        iniContent: String,
        enabledExtensions: List<String>
    ) {
        val iniFile = getPhpIniFile(version)
        iniFile.parentFile?.mkdirs()
        iniFile.writeText(iniContent)
        val existing = runtimeConfigDao.getById("php_$version")
        runtimeConfigDao.upsert(
            (existing ?: RuntimeConfigEntity(
                id = "php_$version",
                engineType = EngineType.PHP.name,
                version = version,
                installPath = runtimeEngine.getRuntimeDir(EngineType.PHP, version).absolutePath,
                configText = iniContent,
                extensionsCsv = "",
                port = 9000,
                isInstalled = findPhpBinary(version) != null
            )).copy(
                configText = iniContent,
                extensionsCsv = enabledExtensions.joinToString(","),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun applyComprehensiveWebPreset(version: String): String {
        val ini = buildDefaultPhpIni(version, comprehensiveWebExtensions)
        savePhpIniAndExtensions(version, ini, comprehensiveWebExtensions)
        return ini
    }

    suspend fun importLocalRuntimeArchive(version: String, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val targetDir = runtimeEngine.getRuntimeDir(EngineType.PHP, version)
        val tempFile = File(context.cacheDir, "php_import_$version.pkg")
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: return@withContext Result.failure(IllegalStateException("تعذر فتح ملف حزمة PHP"))

            val extRes = ArchiveExtractor.extractArchive(tempFile, targetDir)
            tempFile.delete()
            if (extRes.isFailure) {
                return@withContext Result.failure(extRes.exceptionOrNull() ?: IllegalStateException("فشل فك حزمة PHP"))
            }

            val bin = findPhpBinary(version)
                ?: return@withContext Result.failure(IllegalStateException("تم فك الحزمة لكن لم يُعثر على php أو php-cgi بداخلها"))

            val env = runtimeEngine.buildEnvironment(EngineType.PHP, version)
            val testRes = runtimeEngine.executeCommand(listOf(bin.absolutePath, "-v"), targetDir, env, 8L)
            if (testRes.isSuccess) {
                val existing = runtimeConfigDao.getById("php_$version")
                if (existing != null) runtimeConfigDao.upsert(existing.copy(isInstalled = true))
                logDao.insert(AppLogEntity(level = "INFO", source = "PHP", message = "تم تثبيت والتحقق من PHP $version: ${testRes.stdout.lineSequence().firstOrNull()}"))
                Result.success("تم التثبيت والتحقق: ${testRes.stdout.lineSequence().firstOrNull()}")
            } else {
                logDao.insert(AppLogEntity(level = "ERROR", source = "PHP", message = "فشل اختبار PHP $version: ${testRes.stderr}"))
                Result.failure(IllegalStateException(testRes.stderr))
            }
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    fun buildDefaultPhpIni(version: String, extensions: List<String>): String {
        val extLines = extensions.distinct().joinToString("\n") { "extension=$it.so" }
        return """
            ; Abotalal Hosting - PHP $version Configuration
            [PHP]
            engine = On
            short_open_tag = On
            memory_limit = 512M
            max_execution_time = 120
            max_input_time = 120
            file_uploads = On
            upload_max_filesize = 128M
            post_max_size = 128M
            allow_url_fopen = On
            allow_url_include = Off
            display_errors = Off
            log_errors = On
            date.timezone = Asia/Riyadh
            cgi.fix_pathinfo = 1

            [mail function]
            sendmail_path = /system/bin/sendmail -t -i

            ; Enabled Extensions
            $extLines
        """.trimIndent()
    }
}
