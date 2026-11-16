package com.example.engine.installer

import android.content.Context
import android.net.Uri
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.ProjectInstallDao
import com.example.core.database.ProjectInstallEntity
import com.example.core.database.SiteEntity
import com.example.core.model.CheckSeverity
import com.example.core.model.CompatibilityCheckItem
import com.example.core.model.EngineType
import com.example.core.model.ProjectAnalysisReport
import com.example.core.model.ProjectType
import com.example.engine.core.ProcessProbe
import com.example.engine.http.EmbeddedHttpServer
import com.example.files.IsolatedFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Generic capability-based Project Requirements Detector & Installer.
 * Analyzes any uploaded or existing project by inspecting manifests, source imports,
 * config files, database connectors, upload paths, and rewrite rules without hardcoding script names.
 */
class ProjectInstaller(
    private val context: Context,
    private val fileManager: IsolatedFileManager,
    private val httpServer: EmbeddedHttpServer,
    private val installDao: ProjectInstallDao,
    private val logDao: AppLogDao
) {

    suspend fun importZipIntoSiteAndAnalyze(site: SiteEntity, zipUri: Uri): Result<ProjectAnalysisReport> {
        val siteRoot = File(site.rootFolder).apply { mkdirs() }
        val docRoot = fileManager.getSiteDocumentRoot(siteRoot)
        val extractRes = fileManager.importAndExtractZipFromUri(siteRoot, docRoot, zipUri)
        if (extractRes.isFailure) {
            return Result.failure(extractRes.exceptionOrNull() ?: IllegalStateException("فشل فك ضغط المشروع"))
        }
        val report = analyzeProjectDirectory(docRoot, site)
        logDao.insert(
            AppLogEntity(
                level = "INFO",
                source = "INSTALLER",
                message = "تم استيراد وتحليل مشروع '${site.name}': ${report.detectedScriptOrFramework}"
            )
        )
        return Result.success(report)
    }

    suspend fun analyzeProjectDirectory(projectDir: File, site: SiteEntity? = null): ProjectAnalysisReport =
        withContext(Dispatchers.IO) {
            val scanRoot = if (File(projectDir, "public_html").isDirectory &&
                findFileIgnoreCase(projectDir, "composer.json") == null &&
                findFileIgnoreCase(projectDir, "package.json") == null &&
                findFileIgnoreCase(projectDir, "requirements.txt") == null &&
                !File(projectDir, "index.php").exists() &&
                !File(projectDir, "index.html").exists() &&
                !File(projectDir, "app.py").exists() &&
                !File(projectDir, "manage.py").exists() &&
                !File(projectDir, "main.py").exists()
            ) {
                File(projectDir, "public_html")
            } else {
                projectDir
            }

            val composerFile = findFileIgnoreCase(scanRoot, "composer.json")
            val packageJsonFile = findFileIgnoreCase(scanRoot, "package.json")
            val requirementsFile = findFileIgnoreCase(scanRoot, "requirements.txt")
            val pyprojectFile = findFileIgnoreCase(scanRoot, "pyproject.toml")
            val htaccessFile = File(scanRoot, ".htaccess").takeIf { it.exists() }
            val envFile = File(scanRoot, ".env").takeIf { it.exists() }
                ?: site?.let { File(it.rootFolder, "config/.env").takeIf { f -> f.exists() } }

            var reqPhpVer: String? = null
            var reqPythonVer: String? = null
            var reqNodeVer: String? = null
            val phpExtensions = mutableSetOf<String>()
            val pythonPackages = mutableSetOf<String>()
            val nodePackages = mutableSetOf<String>()
            var requiresSocketIo = false
            val detectedDbs = mutableSetOf<String>()

            // 1. Inspect composer.json generically
            if (composerFile != null) {
                try {
                    val json = JSONObject(composerFile.readText())
                    val req = json.optJSONObject("require")
                    if (req != null) {
                        val keys = req.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k == "php") {
                                reqPhpVer = req.optString(k)
                            } else if (k.startsWith("ext-")) {
                                phpExtensions.add(k.removePrefix("ext-"))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Inspect package.json generically
            if (packageJsonFile != null) {
                try {
                    val json = JSONObject(packageJsonFile.readText())
                    val engines = json.optJSONObject("engines")
                    reqNodeVer = engines?.optString("node")?.takeIf { it.isNotBlank() } ?: "20.x"
                    val deps = json.optJSONObject("dependencies")
                    if (deps != null) {
                        val keys = deps.keys()
                        while (keys.hasNext()) {
                            val dep = keys.next()
                            nodePackages.add(dep)
                            if (dep == "socket.io" || dep == "ws") requiresSocketIo = true
                            if (dep.contains("mysql", true)) detectedDbs.add("MySQL / MariaDB")
                            if (dep == "pg" || dep.contains("postgres", true)) detectedDbs.add("PostgreSQL")
                            if (dep.contains("sqlite", true)) detectedDbs.add("SQLite")
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 3. Inspect Python requirements.txt / pyproject.toml / runtime.txt
            val runtimeTxt = File(scanRoot, "runtime.txt").takeIf { it.exists() }
            if (runtimeTxt != null) {
                reqPythonVer = runtimeTxt.readText().trim()
            } else if (requirementsFile != null || pyprojectFile != null) {
                reqPythonVer = "3.12"
            }

            if (requirementsFile != null) {
                requirementsFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { line ->
                        val pkg = line.substringBefore("==").substringBefore(">=").substringBefore("<=").substringBefore("[").trim()
                        if (pkg.isNotEmpty()) pythonPackages.add(pkg)
                        val lower = pkg.lowercase(Locale.ROOT)
                        if (lower.contains("psycopg") || lower.contains("asyncpg")) detectedDbs.add("PostgreSQL")
                        if (lower.contains("pymysql") || lower.contains("mysql")) detectedDbs.add("MySQL / MariaDB")
                        if (lower.contains("sqlite")) detectedDbs.add("SQLite")
                    }
            }

            // 4. Generic source & configuration scan (bounded depth/size for fast execution)
            val allFiles = scanRoot.walkTopDown().maxDepth(4).filter { it.isFile }.take(500).toList()
            val hasPhpFiles = allFiles.any { it.extension.equals("php", true) }
            val hasPyFiles = allFiles.any { it.extension.equals("py", true) }
            val hasJsServerFiles = allFiles.any {
                it.name.equals("server.js", true) || it.name.equals("app.js", true) || it.name.equals("index.mjs", true)
            }

            val configFiles = mutableListOf<String>()
            val configCandidates = allFiles.filter {
                val n = it.name.lowercase(Locale.ROOT)
                n == ".env" || n.contains("config") || n == "settings.py" || n == "database.php" || n == "init.php"
            }
            configCandidates.forEach { f ->
                val rel = f.relativeToOrSelf(scanRoot).path.replace('\\', '/')
                if (rel !in configFiles) configFiles.add(rel)
            }
            if (envFile != null && ".env" !in configFiles) configFiles.add(".env")

            // Scan PHP files for used extensions & DB connectors
            if (hasPhpFiles) {
                allFiles.filter { it.extension.equals("php", true) && it.length() <= 256 * 1024 }
                    .take(80)
                    .forEach { phpFile ->
                        val content = runCatching { phpFile.readText() }.getOrDefault("")
                        if (content.contains("mysqli", true) || content.contains("pdo_mysql", true)) {
                            phpExtensions.add("mysqli")
                            phpExtensions.add("pdo_mysql")
                            detectedDbs.add("MySQL / MariaDB")
                        }
                        if (content.contains("curl_init", true)) phpExtensions.add("curl")
                        if (content.contains("mb_", true)) phpExtensions.add("mbstring")
                        if (content.contains("imagecreate", true) || content.contains("gd_info", true)) phpExtensions.add("gd")
                        if (content.contains("ZipArchive", true)) phpExtensions.add("zip")
                        if (content.contains("openssl_", true)) phpExtensions.add("openssl")
                        if (content.contains("finfo_", true) || content.contains("mime_content_type", true)) phpExtensions.add("fileinfo")
                        if (content.contains("SQLite3", true) || content.contains("pdo_sqlite", true)) {
                            phpExtensions.add("sqlite3")
                            detectedDbs.add("SQLite")
                        }
                        if (content.contains("pg_connect", true) || content.contains("pdo_pgsql", true)) {
                            detectedDbs.add("PostgreSQL")
                        }
                    }
                if (reqPhpVer == null) reqPhpVer = "8.4 / 8.3"
            }

            // Scan Python files for imports & web frameworks
            var detectedPyFramework: String? = null
            if (hasPyFiles) {
                val isDjango = allFiles.any { it.name == "manage.py" } ||
                    pythonPackages.any { it.equals("django", true) }
                val isFastApi = pythonPackages.any { it.equals("fastapi", true) } ||
                    allFiles.filter { it.extension == "py" && it.length() < 128 * 1024 }
                        .any { it.readText().contains("FastAPI(") }
                val isFlask = pythonPackages.any { it.equals("flask", true) } ||
                    allFiles.filter { it.extension == "py" && it.length() < 128 * 1024 }
                        .any { it.readText().contains("Flask(") }
                detectedPyFramework = when {
                    isDjango -> "Django"
                    isFastApi -> "FastAPI"
                    isFlask -> "Flask"
                    else -> "Python"
                }
                if (reqPythonVer == null) reqPythonVer = "3.12"
            }

            // Scan config files & SQL dumps
            configCandidates.take(20).forEach { cfg ->
                val txt = runCatching { cfg.readText() }.getOrDefault("")
                if (txt.contains("mysql", true) || txt.contains("DB_HOST", true)) detectedDbs.add("MySQL / MariaDB")
                if (txt.contains("postgres", true) || txt.contains("pgsql", true)) detectedDbs.add("PostgreSQL")
                if (txt.contains("sqlite", true)) detectedDbs.add("SQLite")
            }
            val hasSqlDump = allFiles.any { it.extension.equals("sql", true) }
            if (hasSqlDump && detectedDbs.isEmpty()) detectedDbs.add("SQL (MySQL / MariaDB أو SQLite)")

            // Subdirectories for uploads/storage/cache
            val uploadDirs = listOf("uploads", "upload", "content/uploads", "storage", "media", "public/uploads", "var", "cache")
                .filter { File(scanRoot, it).exists() }

            val rewriteCount = htaccessFile?.readLines()
                ?.count { it.trim().startsWith("RewriteRule", ignoreCase = true) } ?: 0

            val primaryLanguage = when {
                hasPhpFiles || composerFile != null -> "PHP"
                hasPyFiles || requirementsFile != null || pyprojectFile != null -> "Python"
                packageJsonFile != null || hasJsServerFiles -> "Node.js / JavaScript"
                else -> "HTML / CSS / JS"
            }

            val detectedArchitectureLabel = buildString {
                append(
                    when {
                        primaryLanguage == "PHP" && composerFile != null -> "مشروع PHP (Composer)"
                        primaryLanguage == "PHP" && hasHtaccess(htaccessFile) -> "تطبيق PHP مع قواعد Rewrite"
                        primaryLanguage == "PHP" -> "تطبيق PHP"
                        detectedPyFramework != null -> "تطبيق Python ($detectedPyFramework)"
                        requiresSocketIo -> "خدمة Node.js + Socket.io"
                        packageJsonFile != null -> "مشروع Node.js"
                        else -> "موقع ويب ثابت (HTML/CSS/JS)"
                    }
                )
                if (requiresSocketIo && primaryLanguage == "PHP") {
                    append(" + خدمة Node.js الفورية")
                }
            }

            val recommendedType = when {
                primaryLanguage == "PHP" -> ProjectType.PHP_APP
                detectedPyFramework == "FastAPI" -> ProjectType.PYTHON_FASTAPI
                detectedPyFramework == "Django" -> ProjectType.PYTHON_DJANGO
                primaryLanguage == "Python" -> ProjectType.PYTHON_FLASK
                primaryLanguage.startsWith("Node") -> ProjectType.NODE_SOCKETIO
                else -> ProjectType.STATIC_HTML
            }

            // Build Compatibility Checks against actual device environment
            val checks = mutableListOf<CompatibilityCheckItem>()

            if (primaryLanguage == "PHP") {
                val phpBin = ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php/8.4")
                    ?: ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php")
                if (phpBin != null) {
                    val (ok, verOut) = ProcessProbe.probeBinaryVersion(phpBin, "-v")
                    checks.add(
                        CompatibilityCheckItem(
                            title = "بيئة تشغيل PHP (${reqPhpVer ?: "8.4"})",
                            detail = if (ok) "متحقق منه: $verOut" else "موجود لكن تعذر التنفيذ: $verOut",
                            severity = if (ok) CheckSeverity.OK else CheckSeverity.MISSING,
                            missingEngineType = if (ok) null else EngineType.PHP,
                            suggestedPackageQuery = if (ok) null else "php"
                        )
                    )
                } else {
                    checks.add(
                        CompatibilityCheckItem(
                            title = "بيئة تشغيل PHP (${reqPhpVer ?: "8.4"})",
                            detail = "غير مثبت لمعمارية ${ProcessProbe.getDeviceAbi()} — مطلوب لتشغيل ملفات PHP",
                            severity = CheckSeverity.MISSING,
                            missingEngineType = EngineType.PHP,
                            suggestedPackageQuery = "php"
                        )
                    )
                }

                if (phpExtensions.isNotEmpty()) {
                    checks.add(
                        CompatibilityCheckItem(
                            title = "إضافات PHP المطلوبة (${phpExtensions.size})",
                            detail = phpExtensions.sorted().joinToString(", "),
                            severity = if (phpBin != null) CheckSeverity.OK else CheckSeverity.WARNING,
                            missingEngineType = if (phpBin == null) EngineType.PHP else null
                        )
                    )
                }
            }

            if (primaryLanguage == "Python") {
                val pyBin = ProcessProbe.findBinary(context, listOf("python3", "python"), "python")
                checks.add(
                    CompatibilityCheckItem(
                        title = "مفسر Python (${reqPythonVer ?: "3.12"})",
                        detail = if (pyBin != null) "متاح في ${pyBin.absolutePath}" else "غير مثبت لمعمارية ${ProcessProbe.getDeviceAbi()}",
                        severity = if (pyBin != null) CheckSeverity.OK else CheckSeverity.MISSING,
                        missingEngineType = if (pyBin == null) EngineType.PYTHON else null,
                        suggestedPackageQuery = if (pyBin == null) "python" else null
                    )
                )
                if (pythonPackages.isNotEmpty()) {
                    checks.add(
                        CompatibilityCheckItem(
                            title = "مكتبات Python المكتشفة (${pythonPackages.size})",
                            detail = pythonPackages.sorted().joinToString(", "),
                            severity = if (pyBin != null) CheckSeverity.WARNING else CheckSeverity.MISSING,
                            missingEngineType = if (pyBin == null) EngineType.PYTHON else null
                        )
                    )
                }
            }

            if (requiresSocketIo || primaryLanguage.startsWith("Node")) {
                val nodeBin = ProcessProbe.findBinary(context, listOf("node", "nodejs"), "nodejs")
                checks.add(
                    CompatibilityCheckItem(
                        title = "محرك Node.js (${reqNodeVer ?: "20+"})",
                        detail = if (nodeBin != null) "متاح في ${nodeBin.absolutePath}" else "غير مثبت لمعمارية ${ProcessProbe.getDeviceAbi()}",
                        severity = if (nodeBin != null) CheckSeverity.OK else CheckSeverity.MISSING,
                        missingEngineType = if (nodeBin == null) EngineType.NODEJS else null,
                        suggestedPackageQuery = if (nodeBin == null) "nodejs-lts" else null
                    )
                )
            }

            if (detectedDbs.any { it.contains("MySQL") || it.contains("MariaDB") }) {
                val mysqlUp = ProcessProbe.isTcpPortListening("127.0.0.1", 3306, 200)
                checks.add(
                    CompatibilityCheckItem(
                        title = "خادم MySQL / MariaDB",
                        detail = if (mysqlUp) "الخادم يعمل ومستمع على المنفذ 3306" else "غير مشغل أو غير مثبت على المنفذ 3306",
                        severity = if (mysqlUp) CheckSeverity.OK else CheckSeverity.MISSING,
                        missingEngineType = if (mysqlUp) null else EngineType.MYSQL_MARIADB,
                        suggestedPackageQuery = if (mysqlUp) null else "mariadb"
                    )
                )
            }

            if (detectedDbs.any { it.contains("PostgreSQL") }) {
                val pgUp = ProcessProbe.isTcpPortListening("127.0.0.1", 5432, 200)
                checks.add(
                    CompatibilityCheckItem(
                        title = "خادم PostgreSQL",
                        detail = if (pgUp) "الخادم يعمل ومستمع على المنفذ 5432" else "غير مشغل أو غير مثبت على المنفذ 5432",
                        severity = if (pgUp) CheckSeverity.OK else CheckSeverity.MISSING,
                        missingEngineType = if (pgUp) null else EngineType.POSTGRESQL,
                        suggestedPackageQuery = if (pgUp) null else "postgresql"
                    )
                )
            }

            if (htaccessFile != null) {
                checks.add(
                    CompatibilityCheckItem(
                        title = "إعادة كتابة الروابط (.htaccess)",
                        detail = "تم اكتشاف $rewriteCount قاعدة إعادة كتابة — مدعومة عبر محرك HTTP Rewrite المحلي",
                        severity = CheckSeverity.OK
                    )
                )
            }

            if (uploadDirs.isNotEmpty()) {
                val allWritable = uploadDirs.all { File(scanRoot, it).canWrite() }
                checks.add(
                    CompatibilityCheckItem(
                        title = "مجلدات التخزين والرفع (${uploadDirs.joinToString(", ")})",
                        detail = if (allWritable) "قابلة للكتابة وآمنة" else "بعض المجلدات تحتاج ضبط الصلاحيات (755)",
                        severity = if (allWritable) CheckSeverity.OK else CheckSeverity.WARNING
                    )
                )
            }

            if (checks.isEmpty()) {
                checks.add(
                    CompatibilityCheckItem(
                        title = "توافق خادم الويب المحلي",
                        detail = "المشروع جاهز للتشغيل محلياً بدون إنترنت عبر محرك HTTP/1.1 المدمج",
                        severity = CheckSeverity.OK
                    )
                )
            }

            val fullyCompatible = checks.none { it.severity == CheckSeverity.MISSING }
            val report = ProjectAnalysisReport(
                projectPath = scanRoot.absolutePath,
                detectedScriptOrFramework = detectedArchitectureLabel,
                primaryLanguage = primaryLanguage,
                recommendedProjectType = recommendedType,
                requiredPhpVersion = reqPhpVer,
                requiredPythonVersion = reqPythonVer,
                requiredNodeVersion = reqNodeVer,
                hasComposerJson = composerFile != null,
                hasPackageJson = packageJsonFile != null,
                hasRequirementsTxt = requirementsFile != null,
                hasHtaccess = htaccessFile != null,
                requiresSocketIo = requiresSocketIo,
                detectedConfigFiles = configFiles,
                requiredPhpExtensions = phpExtensions.toList().sorted(),
                detectedPythonPackages = pythonPackages.toList().sorted(),
                detectedNodePackages = nodePackages.toList().sorted(),
                detectedDatabases = detectedDbs.toList(),
                detectedUploadDirs = uploadDirs,
                rewriteRulesCount = rewriteCount,
                checks = checks,
                isFullyCompatible = fullyCompatible
            )

            if (site != null) {
                installDao.upsert(
                    ProjectInstallEntity(
                        id = "inst_${site.id}",
                        siteId = site.id,
                        projectName = site.name,
                        detectedScript = detectedArchitectureLabel,
                        primaryLanguage = primaryLanguage,
                        backupFolderPath = "",
                        installStatus = "ANALYZED",
                        reportSummary = "${checks.count { it.severity == CheckSeverity.OK }} ناجح / ${checks.count { it.severity == CheckSeverity.MISSING }} ناقص",
                        verificationDetails = "تم التحليل قبل التثبيت — بانتظار موافقة المستخدم واختبار التشغيل"
                    )
                )
            }
            report
        }

    private fun hasHtaccess(file: File?): Boolean = file != null && file.exists()

    suspend fun createSafeBackup(site: SiteEntity): Result<File> = withContext(Dispatchers.IO) {
        return@withContext try {
            val siteRoot = File(site.rootFolder)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val siteBackupsRoot = File(siteRoot, "backups").apply { mkdirs() }
            val backupDir = File(siteBackupsRoot, stamp).apply { mkdirs() }
            val docRoot = fileManager.getSiteDocumentRoot(siteRoot)
            val targets = listOf(
                ".htaccess", ".env", "config.php", "includes/config.php",
                "composer.json", "package.json", "requirements.txt", "settings.py"
            )
            var copied = 0
            targets.forEach { rel ->
                val src = File(docRoot, rel).takeIf { it.exists() && it.isFile }
                    ?: File(siteRoot, "config/$rel").takeIf { it.exists() && it.isFile }
                if (src != null) {
                    val dst = File(backupDir, rel)
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    copied++
                }
            }
            if (copied == 0) {
                File(backupDir, "BACKUP_MARKER.txt").writeText("Snapshot created at $stamp for ${site.name}")
            }
            logDao.insert(
                AppLogEntity(
                    level = "INFO",
                    source = "INSTALLER",
                    message = "تم إنشاء نسخة احتياطية آمنة داخل backups/${backupDir.name} ($copied ملف)"
                )
            )
            Result.success(backupDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun runPostInstallVerification(
        site: SiteEntity,
        report: ProjectAnalysisReport,
        backupDir: File?
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val docRoot = fileManager.getSiteDocumentRoot(File(site.rootFolder))
        val entryExists = File(docRoot, site.entryFile).exists() ||
            File(docRoot, "index.html").exists() ||
            File(docRoot, "index.php").exists() ||
            File(site.rootFolder, site.entryFile).exists()
        if (!entryExists) {
            val msg = "فشل الاختبار: ملف البداية (${site.entryFile}) غير موجود في ${docRoot.name}"
            recordInstallOutcome(site, report, backupDir, "FAILED", msg)
            return@withContext false to msg
        }

        val startedHere = !httpServer.runningSiteIds.value.contains(site.id)
        if (startedHere) {
            val startRes = httpServer.startSite(site)
            if (startRes.isFailure) {
                val msg = "فشل تشغيل الخادم للاختبار: ${startRes.exceptionOrNull()?.message}"
                recordInstallOutcome(site, report, backupDir, "FAILED", msg)
                return@withContext false to msg
            }
        }

        var httpCode = -1
        try {
            val conn = URL("http://127.0.0.1:${site.port}/").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            httpCode = conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            val msg = "فشل اختبار الاتصال المحلي على المنفذ ${site.port}: ${e.message}"
            recordInstallOutcome(site, report, backupDir, "FAILED", msg)
            return@withContext false to msg
        }

        val missingChecks = report.checks.filter { it.severity == CheckSeverity.MISSING }
        val passed = httpCode in 200..399 && missingChecks.isEmpty()
        val detail = if (passed) {
            "اجتاز جميع الاختبارات: استجابة HTTP $httpCode على المنفذ ${site.port} وكافة المتطلبات متوفرة."
        } else {
            val reasons = buildList {
                if (httpCode !in 200..399) add("الخادم أرجع رمز HTTP $httpCode")
                missingChecks.forEach { add("ناقص: ${it.title}") }
            }.joinToString(" | ")
            "لم يُعتمد التثبيت بالكامل: $reasons"
        }

        recordInstallOutcome(
            site,
            report,
            backupDir,
            if (passed) "VERIFIED" else "FAILED",
            detail
        )
        passed to detail
    }

    private suspend fun recordInstallOutcome(
        site: SiteEntity,
        report: ProjectAnalysisReport,
        backupDir: File?,
        status: String,
        detail: String
    ) {
        installDao.upsert(
            ProjectInstallEntity(
                id = "inst_${site.id}_${UUID.randomUUID().toString().take(4)}",
                siteId = site.id,
                projectName = site.name,
                detectedScript = report.detectedScriptOrFramework,
                primaryLanguage = report.primaryLanguage,
                backupFolderPath = backupDir?.absolutePath ?: "",
                installStatus = status,
                reportSummary = "${report.checks.count { it.severity == CheckSeverity.OK }} ناجح / ${report.checks.count { it.severity == CheckSeverity.MISSING }} ناقص",
                verificationDetails = detail
            )
        )
        logDao.insert(
            AppLogEntity(
                level = if (status == "VERIFIED") "INFO" else "WARN",
                source = "INSTALLER",
                message = "[${site.name}] نتيجة اختبار التثبيت ($status): $detail"
            )
        )
    }

    private fun findFileIgnoreCase(root: File, fileName: String): File? {
        val direct = File(root, fileName)
        if (direct.exists()) return direct
        return root.listFiles()?.firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }
    }
}
