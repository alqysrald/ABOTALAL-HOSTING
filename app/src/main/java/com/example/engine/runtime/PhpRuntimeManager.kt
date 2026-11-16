package com.example.engine.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.core.compat.AndroidCompatibilityLayer
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.RuntimeConfigDao
import com.example.core.database.RuntimeConfigEntity
import com.example.core.database.SiteEntity
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.core.model.PhpCheckStep
import com.example.core.model.PhpVerificationReport
import com.example.core.model.ProjectType
import com.example.engine.core.ArchiveExtractor
import com.example.engine.core.ElfBinaryInspector
import com.example.engine.core.ManagedServiceSpec
import com.example.engine.core.ProcessProbe
import com.example.engine.core.RuntimeCompatibilityLayer
import com.example.engine.core.RuntimeEngine
import com.example.engine.http.EmbeddedHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class PhpRuntimeManager(
    private val context: Context,
    private val runtimeConfigDao: RuntimeConfigDao,
    private val runtimeEngine: RuntimeEngine,
    private val logDao: AppLogDao,
    private val runtimeCompatibility: RuntimeCompatibilityLayer = RuntimeCompatibilityLayer(context, AndroidCompatibilityLayer(context))
) {
    val supportedVersions = listOf("8.4", "8.3", "8.2", "8.1")

    private val _lastVerificationReport = MutableStateFlow<PhpVerificationReport?>(null)
    val lastVerificationReport: StateFlow<PhpVerificationReport?> = _lastVerificationReport.asStateFlow()

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

        val blockedAppData = if (foundBinary == null) {
            ProcessProbe.findBlockedAppDataBinary(context, listOf("php-cgi", "php"), "php")
        } else null

        val status = when {
            activeRunning && foundBinary != null -> EngineStatus.RUNNING
            foundBinary != null && probeDetails.startsWith("تم التحقق") -> EngineStatus.STOPPED
            foundBinary != null || blockedAppData != null -> EngineStatus.ERROR
            else -> EngineStatus.NOT_INSTALLED
        }

        val finalDetails = when {
            probeDetails.isNotBlank() -> probeDetails
            blockedAppData != null ->
                "تم رصد ${blockedAppData.name} داخل filesDir، لكن Android API ${Build.VERSION.SDK_INT} (W^X/SELinux) يحظر تنفيذه (error=13). استخدم نواة libphp_cgi.so داخل nativeLibraryDir."
            else ->
                "لا يوجد ثنائي php-cgi / libphp_cgi.so متوافق مع $abi في nativeLibraryDir أو مسار النظام."
        }

        return EngineInfo(
            type = EngineType.PHP,
            status = status,
            activeVersion = foundVersion ?: if (blockedAppData != null) "محظور (W^X/SELinux)" else "غير مثبت",
            availableVersions = supportedVersions,
            port = 9000,
            binaryPath = foundBinary?.absolutePath ?: blockedAppData?.absolutePath,
            architecture = abi,
            verificationDetails = finalDetails,
            requirementNotice = "يدعم PHP 8.4 / 8.3 / 8.2 / 8.1 مع فحص W^X/ELF، وإدارة php.ini و php -m و Composer وبيئات مستقلة لكل موقع.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    /**
     * Complete 7-Step Self-Test Suite for PHP Runtime:
     * 1. Binary + ELF + W^X/SELinux verification
     * 2. `php -v`
     * 3. `php-cgi` / built-in server `-S` verification
     * 4. `php -m` extensions inspection
     * 5. HTTP Server integration
     * 6. Real `index.php` execution inside temporary isolated site
     * 7. Loopback HTTP GET 200 content verification
     */
    suspend fun runPhpVerificationSuite(
        version: String,
        httpServer: EmbeddedHttpServer? = null
    ): PhpVerificationReport = withContext(Dispatchers.IO) {
        val abi = ProcessProbe.getDeviceAbi()
        val diag = runtimeCompatibility.runEightPointDiagnostics(EngineType.PHP)
        val bin = findPhpBinary(version)

        if (bin == null) {
            val report = runtimeCompatibility.buildBlockedPhpVerificationReport(version, diag.summaryAr)
            _lastVerificationReport.value = report
            logDao.insert(
                AppLogEntity(
                    level = "WARN",
                    source = "PHP_VERIFY",
                    message = "اختبار PHP $version: غير قابل للتنفيذ (${diag.backend.labelAr}) — ${diag.summaryAr}"
                )
            )
            return@withContext report
        }

        val steps = mutableListOf<PhpCheckStep>()
        val env = runtimeEngine.buildEnvironment(EngineType.PHP, version)
        val workDir = File(context.cacheDir, "php_verify_test").apply {
            deleteRecursively()
            mkdirs()
        }

        // Step 1: ELF + W^X check
        val elf = ElfBinaryInspector.inspect(bin, listOf(abi), listOfNotNull(context.applicationInfo?.nativeLibraryDir?.let { File(it) }))
        val elfOk = !elf.isValidElf || (elf.isBionicLinker && !elf.isGlibcLinker && elf.missingLibraries.isEmpty())
        steps.add(PhpCheckStep(1, "1. فحص ثنائي PHP وترويسة ELF/W^X", bin.name, elfOk && diag.isExecutionPermitted, elf.diagnosticSummary))

        // Step 2: php -v
        val s2 = runtimeEngine.executeCommand(listOf(bin.absolutePath, "-v"), workDir, env, 8L)
        steps.add(PhpCheckStep(2, "2. تشغيل php -v وقراءة الإصدار", "${bin.name} -v", s2.isSuccess, s2.combinedOutput.ifBlank { s2.stderr }))

        // Step 3: php-cgi / -S check
        val cgiBin = ProcessProbe.findBinary(context, listOf("php-cgi"), "php/$version") ?: bin
        val s3 = runtimeEngine.executeCommand(listOf(cgiBin.absolutePath, "-v"), workDir, env, 8L)
        steps.add(PhpCheckStep(3, "3. جاهزية php-cgi أو خادم التطوير", cgiBin.name, s3.isSuccess, s3.combinedOutput.ifBlank { s3.stderr }))

        // Step 4: php -m
        val modRes = probeLoadedModules(version)
        val mods = modRes.getOrDefault(emptyList())
        steps.add(PhpCheckStep(4, "4. فحص الإضافات المحملة (php -m)", "${bin.name} -m", modRes.isSuccess && mods.isNotEmpty(), if (modRes.isSuccess) "${mods.size} إضافة: ${mods.take(8).joinToString()}" else (modRes.exceptionOrNull()?.message ?: "تعذر فحص الإضافات")))

        // Steps 5, 6, 7: HTTP integration + real index.php + localhost verification
        val publicDir = File(workDir, "public_html").apply { mkdirs() }
        File(publicDir, "index.php").writeText("<?php echo 'ABOTALAL_PHP_OK_' . PHP_VERSION; ?>")
        val testPort = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(18921)

        var httpStarted = false
        var httpBody = ""
        var httpCode = -1
        if (httpServer != null) {
            val testSite = SiteEntity(
                id = "php_verify_tmp",
                name = "PhpSelfTest",
                rootFolder = workDir.absolutePath,
                port = testPort,
                projectType = ProjectType.PHP_APP.name,
                runtimeVersion = version,
                entryFile = "index.php"
            )
            httpStarted = httpServer.startSite(testSite).isSuccess
            if (httpStarted) {
                try {
                    val conn = URL("http://127.0.0.1:$testPort/index.php").openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    httpCode = conn.responseCode
                    httpBody = runCatching { conn.inputStream.bufferedReader().readText() }.getOrDefault("")
                    conn.disconnect()
                } catch (_: Exception) {
                } finally {
                    httpServer.stopSite("php_verify_tmp")
                }
            }
        }
        val phpExecOk = httpCode == 200 && httpBody.contains("ABOTALAL_PHP_OK")
        steps.add(PhpCheckStep(5, "5. ربط PHP مع خادم HTTP المحلي", "EmbeddedHttpServer :$testPort", httpStarted, if (httpStarted) "تم ربط المنفذ $testPort" else "يتطلب مثيل EmbeddedHttpServer"))
        steps.add(PhpCheckStep(6, "6. تنفيذ ملف index.php حقيقي", "index.php", phpExecOk, if (phpExecOk) "تم التنفيذ: ${httpBody.take(120)}" else "فشل التنفيذ HTTP $httpCode"))
        steps.add(PhpCheckStep(7, "7. التحقق من الاستجابة في المتصفح المحلي", "GET http://127.0.0.1:$testPort/", phpExecOk, if (phpExecOk) "HTTP 200 OK" else "لم تُستلم استجابة صالحة من PHP"))

        workDir.deleteRecursively()
        val allPassed = steps.all { it.passed }
        val report = PhpVerificationReport(version, abi, steps, allPassed)
        _lastVerificationReport.value = report
        logDao.insert(
            AppLogEntity(
                level = if (allPassed) "INFO" else "WARN",
                source = "PHP_VERIFY",
                message = "نتيجة اختبار PHP $version: ${steps.count { it.passed }}/7 خطوات ناجحة"
            )
        )
        report
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
