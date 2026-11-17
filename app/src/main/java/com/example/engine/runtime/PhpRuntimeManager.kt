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
import com.example.engine.php.AbotalalPhpNativeBridge
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
    val supportedVersions: List<String>
        get() {
            val nativeVers = AbotalalPhpNativeBridge.listInstalledNativeVersions(context)
            return (nativeVers + listOf("8.3", "8.4", "8.2", "8.1")).distinct()
        }

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
                val jniAvailable = AbotalalPhpNativeBridge.findNativeLibsForVersion(context, ver) != null
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
                        isInstalled = jniAvailable || bin != null
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
        if (AbotalalPhpNativeBridge.isEngineAvailable(context, version)) {
            val iniPath = getPhpIniFile(version).takeIf { it.exists() }?.absolutePath
            if (!AbotalalPhpNativeBridge.isEngineRunning(context, version)) {
                AbotalalPhpNativeBridge.startEngine(context, version, iniPath)
            }
            val mods = AbotalalPhpNativeBridge.getLoadedModules(context, version)
            if (mods.isNotEmpty()) {
                return@withContext Result.success(mods)
            }
        }

        val bin = ProcessProbe.findBinary(context, listOf("php", "php-cgi"), "php/$version")
            ?: ProcessProbe.findBinary(context, listOf("php", "php-cgi"), "php")
            ?: return@withContext Result.failure(IllegalStateException("محرك PHP غير مثبت للإصدار $version"))

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
        if (AbotalalPhpNativeBridge.isEngineAvailable(context, version)) {
            val iniFile = getPhpIniFile(version).apply {
                parentFile?.mkdirs()
                if (!exists()) writeText(buildDefaultPhpIni(version, comprehensiveWebExtensions))
            }
            val started = AbotalalPhpNativeBridge.startEngine(context, version, iniFile.absolutePath)
            return@withContext if (started) {
                val phpVer = AbotalalPhpNativeBridge.getPhpVersionString(context, version).ifBlank { version }
                logDao.insert(AppLogEntity(level = "INFO", source = "PHP", message = "تم تشغيل PHP Embedded SAPI ($phpVer) عبر JNI"))
                Result.success("محرك PHP Embedded SAPI ($phpVer) يعمل عبر JNI")
            } else {
                Result.failure(IllegalStateException("فشل تهيئة محرك PHP $version عبر JNI Embedded SAPI"))
            }
        }

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
        if (AbotalalPhpNativeBridge.isEngineAvailable(context, version)) {
            AbotalalPhpNativeBridge.stopEngine(context, version)
        }
        runtimeEngine.stopService("php_$version")
    }

    suspend fun restartPhpService(version: String, port: Int = 9000): Result<String> {
        stopPhpService(version)
        return startPhpService(version, port)
    }

    suspend fun probeStatus(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        val nativeInstalled = AbotalalPhpNativeBridge.listInstalledNativeVersions(context)
        for (ver in nativeInstalled) {
            val libs = AbotalalPhpNativeBridge.findNativeLibsForVersion(context, ver)
            if (libs != null && AbotalalPhpNativeBridge.isEngineAvailable(context, ver)) {
                val running = AbotalalPhpNativeBridge.isEngineRunning(context, ver)
                val fullVer = AbotalalPhpNativeBridge.getPhpVersionString(context, ver).ifBlank { ver }
                val modulesCount = if (running) AbotalalPhpNativeBridge.getLoadedModules(context, ver).size else 0
                val status = if (running) EngineStatus.RUNNING else EngineStatus.STOPPED
                val modHint = if (modulesCount > 0) " • $modulesCount إضافة محملة" else ""
                return EngineInfo(
                    type = EngineType.PHP,
                    status = status,
                    activeVersion = "$ver ($fullVer Embedded SAPI)",
                    availableVersions = supportedVersions,
                    port = 9000,
                    binaryPath = "${libs.second.absolutePath} (${libs.first.name})",
                    architecture = abi,
                    verificationDetails = "محرك Embedded SAPI أصلي عبر JNI (${libs.second.name} + ${libs.first.name})$modHint",
                    requirementNotice = "يدعم PHP Embedded SAPI عبر JNI (GET / POST / Sessions / Cookies / Uploads / SQLite / mysqli) مع امتثال كامل لـ W^X و SELinux.",
                    lastCheckedAt = System.currentTimeMillis()
                )
            }
        }

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
                "تم رصد ${blockedAppData.name} داخل filesDir، لكن Android API ${Build.VERSION.SDK_INT} (W^X/SELinux) يحظر تنفيذه (error=13). استخدم نواة libabotalal_php_8_3.so في nativeLibraryDir."
            else ->
                "لا توجد نواة PHP Embedded SAPI أو php-cgi متوافقة لمعمارية $abi في nativeLibraryDir."
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
            requirementNotice = "يدعم PHP Embedded SAPI الأصلي لمعمارية arm64-v8a مع فحص W^X/ELF، وإدارة php.ini وبيئات مستقلة لكل موقع.",
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

        // Primary verification path: real JNI Embedded SAPI engine
        if (AbotalalPhpNativeBridge.isEngineAvailable(context, version)) {
            return@withContext runJniEmbeddedVerificationSuite(version, abi, diag.isExecutionPermitted, httpServer)
        }

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

        val elf = ElfBinaryInspector.inspect(bin, listOf(abi), listOfNotNull(context.applicationInfo?.nativeLibraryDir?.let { File(it) }))
        val elfOk = !elf.isValidElf || (elf.isBionicLinker && !elf.isGlibcLinker && elf.missingLibraries.isEmpty())
        steps.add(PhpCheckStep(1, "1. فحص ثنائي PHP وترويسة ELF/W^X", bin.name, elfOk && diag.isExecutionPermitted, elf.diagnosticSummary))

        val s2 = runtimeEngine.executeCommand(listOf(bin.absolutePath, "-v"), workDir, env, 8L)
        steps.add(PhpCheckStep(2, "2. تشغيل php -v وقراءة الإصدار", "${bin.name} -v", s2.isSuccess, s2.combinedOutput.ifBlank { s2.stderr }))

        val cgiBin = ProcessProbe.findBinary(context, listOf("php-cgi"), "php/$version") ?: bin
        val s3 = runtimeEngine.executeCommand(listOf(cgiBin.absolutePath, "-v"), workDir, env, 8L)
        steps.add(PhpCheckStep(3, "3. جاهزية php-cgi أو خادم التطوير", cgiBin.name, s3.isSuccess, s3.combinedOutput.ifBlank { s3.stderr }))

        val modRes = probeLoadedModules(version)
        val mods = modRes.getOrDefault(emptyList())
        steps.add(PhpCheckStep(4, "4. فحص الإضافات المحملة (php -m)", "${bin.name} -m", modRes.isSuccess && mods.isNotEmpty(), if (modRes.isSuccess) "${mods.size} إضافة: ${mods.take(8).joinToString()}" else (modRes.exceptionOrNull()?.message ?: "تعذر فحص الإضافات")))

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
                message = "نتيجة اختبار PHP $version: ${steps.count { it.passed }}/${steps.size} خطوات ناجحة"
            )
        )
        report
    }

    private suspend fun runJniEmbeddedVerificationSuite(
        version: String,
        abi: String,
        diagPermitted: Boolean,
        httpServer: EmbeddedHttpServer?
    ): PhpVerificationReport {
        val steps = mutableListOf<PhpCheckStep>()
        val libs = AbotalalPhpNativeBridge.findNativeLibsForVersion(context, version)
        val iniFile = getPhpIniFile(version).apply {
            parentFile?.mkdirs()
            if (!exists()) writeText(buildDefaultPhpIni(version, comprehensiveWebExtensions))
        }
        val workDir = File(context.cacheDir, "php_jni_verify_${System.currentTimeMillis()}").apply { mkdirs() }
        val publicDir = File(workDir, "public_html").apply { mkdirs() }
        val sessDir = File(workDir, "tmp/sessions").apply { mkdirs() }
        val upDir = File(workDir, "tmp/uploads").apply { mkdirs() }

        // 1. Load JNI & native libs
        val started = AbotalalPhpNativeBridge.startEngine(context, version, iniFile.absolutePath)
        steps.add(
            PhpCheckStep(
                1,
                "1. تحميل نواة PHP (${libs?.first?.name ?: "libphp"}) عبر JNI",
                libs?.second?.name ?: "libabotalal_php_jni.so",
                libs != null && diagPermitted && started,
                if (started) "تم تحميل المحرك من nativeLibraryDir وربطه بالعملية بنجاح" else "تعذر تحميل نواة JNI"
            )
        )

        // 2. Initialize & read PHP version
        val realVer = AbotalalPhpNativeBridge.getPhpVersionString(context, version)
        steps.add(
            PhpCheckStep(
                2,
                "2. تهيئة Embedded SAPI وقراءة إصدار PHP",
                "PHP_VERSION",
                realVer.isNotBlank(),
                if (realVer.isNotBlank()) "PHP $realVer ($abi)" else "تعذر قراءة الإصدار"
            )
        )

        // 3. phpinfo()
        val infoFile = File(publicDir, "info.php").apply { writeText("<?php phpinfo(); ?>") }
        val infoResp = AbotalalPhpNativeBridge.executePhpScript(
            context, version, infoFile, publicDir, iniFile.absolutePath, sessDir, upDir,
            "GET", "/info.php", "", "/info.php", "127.0.0.1", 8080
        )
        val infoText = infoResp.bodyBytes.toString(Charsets.UTF_8)
        val infoOk = infoResp.success && infoText.contains("PHP Version") &&
            (infoText.contains("Abotalal Android Embedded PHP") || infoText.contains("abotalal-embed"))
        steps.add(
            PhpCheckStep(
                3,
                "3. تنفيذ phpinfo() الحقيقي",
                "phpinfo()",
                infoOk,
                if (infoOk) "تم توليد تقرير phpinfo() (${infoText.length} بايت • SAPI=abotalal-embed)" else infoResp.errorMessage
            )
        )

        // 4. Math expression
        val mathFile = File(publicDir, "math.php").apply { writeText("<?php echo 'SUM=' . (21 * 2 + 58); ?>") }
        val mathResp = AbotalalPhpNativeBridge.executePhpScript(
            context, version, mathFile, publicDir, iniFile.absolutePath, sessDir, upDir,
            "GET", "/math.php", "", "/math.php", "127.0.0.1", 8080
        )
        val mathOut = mathResp.bodyBytes.toString(Charsets.UTF_8)
        val mathOk = mathResp.success && mathOut.trim() == "SUM=100"
        steps.add(
            PhpCheckStep(
                4,
                "4. تنفيذ عملية حسابية PHP (21*2+58)",
                "math.php",
                mathOk,
                if (mathOk) "الناتج الفعلي: $mathOut" else "فشل: $mathOut ${mathResp.errorMessage}"
            )
        )

        // 5. Allowed file R/W inside cacheDir
        val rwTarget = File(workDir, "io_check.txt")
        val rwFile = File(publicDir, "io.php").apply {
            writeText("<?php \$p='${rwTarget.absolutePath}'; file_put_contents(\$p, 'RW_OK_'.PHP_VERSION); echo file_get_contents(\$p); ?>")
        }
        val rwResp = AbotalalPhpNativeBridge.executePhpScript(
            context, version, rwFile, publicDir, iniFile.absolutePath, sessDir, upDir,
            "GET", "/io.php", "", "/io.php", "127.0.0.1", 8080
        )
        val rwOut = rwResp.bodyBytes.toString(Charsets.UTF_8)
        val rwOk = rwResp.success && rwOut.startsWith("RW_OK_") && rwTarget.exists()
        steps.add(
            PhpCheckStep(
                5,
                "5. قراءة وكتابة ملف في مسار مسموح",
                rwTarget.name,
                rwOk,
                if (rwOk) "تمت الكتابة والقراءة بنجاح: $rwOut" else "فشل I/O: $rwOut"
            )
        )

        // 6. Extensions inspection
        val mods = AbotalalPhpNativeBridge.getLoadedModules(context, version)
        val required = listOf("openssl", "sqlite3", "PDO", "pdo_sqlite", "mysqli", "mbstring", "session")
        val missingMods = required.filter { req -> mods.none { it.equals(req, ignoreCase = true) } }
        val modsOk = mods.isNotEmpty() && missingMods.isEmpty()
        steps.add(
            PhpCheckStep(
                6,
                "6. فحص الإضافات المحملة (mysqli / sqlite / mbstring / openssl)",
                "getLoadedModules()",
                modsOk,
                if (modsOk) "${mods.size} إضافة مدمجة: ${mods.take(12).joinToString()}" else "ناقص: ${missingMods.joinToString()}"
            )
        )

        // 7, 8, 9: HTTP Server GET + POST/Cookie + Loopback check
        val indexFile = File(publicDir, "index.php").apply {
            writeText(
                """<?php
                session_start();
                setcookie('abt_ck', 'val123', 0, '/');
                if (${'$'}_SERVER['REQUEST_METHOD'] === 'POST') {
                    echo 'POST_OK:' . (${'$'}_POST['msg'] ?? '') . '|CK=' . (${'$'}_COOKIE['abt_ck'] ?? '');
                } else {
                    ${'$'}_SESSION['hits'] = (${'$'}_SESSION['hits'] ?? 0) + 1;
                    echo 'GET_OK:' . (${'$'}_GET['name'] ?? '') . '|HITS=' . ${'$'}_SESSION['hits'] . '|PHP=' . PHP_VERSION;
                }
                ?>""".trimIndent()
            )
        }

        val testPort = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(18931)
        var getOk = false
        var getBody = ""
        var getCookieHeader = ""
        var postOk = false
        var postBody = ""
        if (httpServer != null) {
            val testSite = SiteEntity(
                id = "php_jni_verify_tmp",
                name = "PhpJniSelfTest",
                rootFolder = workDir.absolutePath,
                port = testPort,
                projectType = ProjectType.PHP_APP.name,
                runtimeVersion = version,
                entryFile = "index.php"
            )
            if (httpServer.startSite(testSite).isSuccess) {
                try {
                    val connGet = URL("http://127.0.0.1:$testPort/index.php?name=Abotalal").openConnection() as HttpURLConnection
                    connGet.connectTimeout = 4000
                    connGet.readTimeout = 4000
                    val codeGet = connGet.responseCode
                    getCookieHeader = connGet.headerFields["Set-Cookie"]?.joinToString("; ") ?: ""
                    getBody = runCatching { connGet.inputStream.bufferedReader().readText() }.getOrDefault("")
                    getOk = codeGet == 200 && getBody.contains("GET_OK:Abotalal")
                    connGet.disconnect()

                    val connPost = URL("http://127.0.0.1:$testPort/index.php").openConnection() as HttpURLConnection
                    connPost.requestMethod = "POST"
                    connPost.doOutput = true
                    connPost.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connPost.setRequestProperty("Cookie", "abt_ck=val123")
                    connPost.outputStream.use { it.write("msg=hello_post".toByteArray(Charsets.UTF_8)) }
                    val codePost = connPost.responseCode
                    postBody = runCatching { connPost.inputStream.bufferedReader().readText() }.getOrDefault("")
                    postOk = codePost == 200 && postBody.contains("POST_OK:hello_post|CK=val123")
                    connPost.disconnect()
                } catch (e: Exception) {
                    getBody = e.message ?: "HTTP error"
                } finally {
                    httpServer.stopSite("php_jni_verify_tmp")
                }
            }
        }
        steps.add(
            PhpCheckStep(
                7,
                "7. تنفيذ طلب GET عبر خادم HTTP المحلي",
                "GET /index.php?name=Abotalal",
                getOk,
                if (getOk) "$getBody (Cookies: ${getCookieHeader.take(60)})" else "فشل GET: $getBody"
            )
        )
        steps.add(
            PhpCheckStep(
                8,
                "8. تنفيذ طلب POST مع Cookie عبر خادم HTTP",
                "POST /index.php",
                postOk,
                if (postOk) postBody else "فشل POST: $postBody"
            )
        )
        steps.add(
            PhpCheckStep(
                9,
                "9. التحقق من استجابة المتصفح المحلي HTTP 200",
                "http://127.0.0.1:$testPort/",
                getOk && postOk,
                if (getOk && postOk) "استجابة HTTP 200 كاملة مع الجلسات والكوكيز" else "تعذر التحقق الكامل من HTTP"
            )
        )

        // 10. Stop & Restart verification
        AbotalalPhpNativeBridge.stopEngine(context, version)
        val stoppedOk = !AbotalalPhpNativeBridge.isEngineRunning(context, version)
        val restarted = AbotalalPhpNativeBridge.startEngine(context, version, iniFile.absolutePath)
        val postRestartResp = AbotalalPhpNativeBridge.executePhpScript(
            context, version, mathFile, publicDir, iniFile.absolutePath, sessDir, upDir,
            "GET", "/math.php", "", "/math.php", "127.0.0.1", 8080
        )
        val restartOk = stoppedOk && restarted && postRestartResp.success &&
            postRestartResp.bodyBytes.toString(Charsets.UTF_8).trim() == "SUM=100"
        steps.add(
            PhpCheckStep(
                10,
                "10. إيقاف المحرك وإعادة تشغيله والتحقق بعد الإقلاع",
                "stopEngine() -> startEngine()",
                restartOk,
                if (restartOk) "تم الإيقاف وإعادة التشغيل والتنفيذ بنجاح" else "فشلت دورة الإيقاف/إعادة التشغيل"
            )
        )

        workDir.deleteRecursively()
        val allPassed = steps.all { it.passed }
        val report = PhpVerificationReport(version, abi, steps, allPassed)
        _lastVerificationReport.value = report
        logDao.insert(
            AppLogEntity(
                level = if (allPassed) "INFO" else "WARN",
                source = "PHP_VERIFY",
                message = "نتيجة اختبار PHP Embedded SAPI ($version): ${steps.count { it.passed }}/10 خطوات ناجحة"
            )
        )
        return report
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
        val extSummary = extensions.distinct().joinToString(", ")
        return """
            ; Abotalal Hosting - PHP $version Configuration (Android Embedded SAPI)
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

            ; Built-in static extensions in Abotalal PHP Engine ($extSummary)
            [Session]
            session.use_cookies = 1
            session.use_only_cookies = 1
            session.gc_maxlifetime = 1440
        """.trimIndent()
    }
}
