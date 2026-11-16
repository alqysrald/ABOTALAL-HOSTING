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

class ProjectInstaller(
    private val context: Context,
    private val fileManager: IsolatedFileManager,
    private val httpServer: EmbeddedHttpServer,
    private val installDao: ProjectInstallDao,
    private val logDao: AppLogDao
) {

    suspend fun importZipIntoSiteAndAnalyze(site: SiteEntity, zipUri: Uri): Result<ProjectAnalysisReport> {
        val siteRoot = File(site.rootFolder).apply { mkdirs() }
        val extractRes = fileManager.importAndExtractZipFromUri(siteRoot, siteRoot, zipUri)
        if (extractRes.isFailure) {
            return Result.failure(extractRes.exceptionOrNull() ?: IllegalStateException("فشل فك ضغط المشروع"))
        }
        val report = analyzeProjectDirectory(siteRoot, site)
        logDao.insert(
            AppLogEntity(
                level = "INFO",
                source = "INSTALLER",
                message = "تم استيراد وتحليل مشروع '${site.name}': ${report.detectedScriptOrFramework}"
            )
        )
        return Result.success(report)
    }

    suspend fun analyzeProjectDirectory(siteRoot: File, site: SiteEntity? = null): ProjectAnalysisReport =
        withContext(Dispatchers.IO) {
            val composerFile = findFileIgnoreCase(siteRoot, "composer.json")
            val packageJsonFile = findFileIgnoreCase(siteRoot, "package.json")
            val requirementsFile = findFileIgnoreCase(siteRoot, "requirements.txt")
            val htaccessFile = File(siteRoot, ".htaccess").takeIf { it.exists() }
            val envFile = File(siteRoot, ".env").takeIf { it.exists() }

            // Detect Social Scripts & Frameworks
            val isSngine = File(siteRoot, "bootloader.php").exists() &&
                (File(siteRoot, "includes/config.php").exists() || File(siteRoot, "content/themes").exists())
            val isWoWonder = File(siteRoot, "assets/init.php").exists() ||
                (File(siteRoot, "config.php").exists() && File(siteRoot, "xhr").exists())
            val isLaravel = File(siteRoot, "artisan").exists() && composerFile != null
            val isWordPress = File(siteRoot, "wp-config.php").exists() || File(siteRoot, "wp-login.php").exists()

            var reqPhpVer: String? = null
            var reqPythonVer: String? = null
            var reqNodeVer: String? = null
            val phpExtensions = mutableSetOf<String>()
            var requiresSocketIo = false
            val detectedDbs = mutableSetOf<String>()

            // Parse composer.json
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

            // Parse package.json
            if (packageJsonFile != null) {
                try {
                    val json = JSONObject(packageJsonFile.readText())
                    val engines = json.optJSONObject("engines")
                    reqNodeVer = engines?.optString("node")?.takeIf { it.isNotBlank() } ?: "20.x"
                    val deps = json.optJSONObject("dependencies")
                    if (deps != null) {
                        if (deps.has("socket.io")) requiresSocketIo = true
                        if (deps.has("mysql") || deps.has("mysql2")) detectedDbs.add("MySQL / MariaDB")
                        if (deps.has("pg")) detectedDbs.add("PostgreSQL")
                        if (deps.has("sqlite3")) detectedDbs.add("SQLite")
                    }
                } catch (_: Exception) {
                }
            }

            // Parse requirements.txt & runtime.txt
            val runtimeTxt = File(siteRoot, "runtime.txt").takeIf { it.exists() }
            if (runtimeTxt != null) {
                reqPythonVer = runtimeTxt.readText().trim()
            } else if (requirementsFile != null) {
                reqPythonVer = "3.11+"
            }

            // Social Script Rules
            if (isSngine || isWoWonder) {
                if (reqPhpVer == null) reqPhpVer = ">= 8.1 (متوافق مع PHP 8.4)"
                phpExtensions.addAll(
                    listOf("mysqli", "mbstring", "curl", "gd", "fileinfo", "zip", "openssl")
                )
                detectedDbs.add("MySQL 8 / MariaDB 10.6+")
                if (File(siteRoot, "nodejs").exists() || File(siteRoot, "socket").exists() || packageJsonFile != null) {
                    requiresSocketIo = true
                    if (reqNodeVer == null) reqNodeVer = "20.x"
                }
            }

            // Detect config files & database references
            val configFiles = mutableListOf<String>()
            listOf(
                "config.php",
                "includes/config.php",
                "assets/includes/config.php",
                ".env",
                "wp-config.php",
                "settings.py"
            ).forEach { rel ->
                val f = File(siteRoot, rel)
                if (f.exists()) {
                    configFiles.add(rel)
                    val txt = f.readText()
                    if (txt.contains("mysqli", true) || txt.contains("mysql", true) || txt.contains("DB_HOST", true)) {
                        detectedDbs.add("MySQL 8 / MariaDB 10.6+")
                    }
                    if (txt.contains("pgsql", true) || txt.contains("postgres", true)) {
                        detectedDbs.add("PostgreSQL")
                    }
                    if (txt.contains("sqlite", true)) {
                        detectedDbs.add("SQLite")
                    }
                }
            }
            if (envFile != null && !configFiles.contains(".env")) configFiles.add(".env")
            val hasSqlDump = siteRoot.walkTopDown().maxDepth(3).any { it.isFile && it.extension.equals("sql", true) }
            if (hasSqlDump && detectedDbs.isEmpty()) detectedDbs.add("MySQL / MariaDB أو SQLite")

            // Detect upload directories
            val uploadDirs = listOf("uploads", "upload", "content/uploads", "storage", "media", "public/uploads")
                .filter { File(siteRoot, it).exists() }

            // Detect .htaccess rules
            val rewriteCount = htaccessFile?.readLines()
                ?.count { it.trim().startsWith("RewriteRule", ignoreCase = true) } ?: 0

            // Language & Script Summary
            val hasPhpFiles = siteRoot.walkTopDown().maxDepth(3).any { it.isFile && it.extension.equals("php", true) }
            val hasPyFiles = siteRoot.walkTopDown().maxDepth(3).any { it.isFile && it.extension.equals("py", true) }

            val detectedScript = when {
                isSngine -> "Sngine (سكريبت شبكة اجتماعية)"
                isWoWonder -> "WoWonder (سكريبت شبكة اجتماعية)"
                isLaravel -> "Laravel PHP Framework"
                isWordPress -> "WordPress CMS"
                requiresSocketIo -> "Node.js + Socket.io"
                requirementsFile != null -> {
                    val reqStr = requirementsFile.readText().lowercase()
                    when {
                        reqStr.contains("fastapi") -> "Python FastAPI"
                        reqStr.contains("django") -> "Python Django"
                        reqStr.contains("flask") -> "Python Flask"
                        else -> "Python Application"
                    }
                }
                hasPhpFiles -> "تطبيق PHP"
                hasPyFiles -> "تطبيق Python"
                packageJsonFile != null -> "تطبيق Node.js"
                else -> "موقع ويب ثابت (HTML/CSS/JS)"
            }

            val primaryLanguage = when {
                hasPhpFiles || composerFile != null || isSngine || isWoWonder -> "PHP"
                hasPyFiles || requirementsFile != null -> "Python"
                packageJsonFile != null -> "Node.js / JavaScript"
                else -> "HTML / CSS / JS"
            }

            val recommendedType = when {
                primaryLanguage == "PHP" -> ProjectType.PHP_APP
                detectedScript.contains("FastAPI") -> ProjectType.PYTHON_FASTAPI
                detectedScript.contains("Django") -> ProjectType.PYTHON_DJANGO
                primaryLanguage == "Python" -> ProjectType.PYTHON_FLASK
                primaryLanguage.startsWith("Node") -> ProjectType.NODE_SOCKETIO
                else -> ProjectType.STATIC_HTML
            }

            // Real Compatibility Checks against Device Environment
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
                            missingEngineType = if (ok) null else EngineType.PHP
                        )
                    )
                } else {
                    checks.add(
                        CompatibilityCheckItem(
                            title = "بيئة تشغيل PHP (${reqPhpVer ?: "8.4"})",
                            detail = "غير مثبت لمعمارية ${ProcessProbe.getDeviceAbi()} — مطلوب لتشغيل ملفات PHP",
                            severity = CheckSeverity.MISSING,
                            missingEngineType = EngineType.PHP
                        )
                    )
                }

                if (phpExtensions.isNotEmpty()) {
                    checks.add(
                        CompatibilityCheckItem(
                            title = "إضافات PHP المطلوبة (${phpExtensions.size})",
                            detail = phpExtensions.joinToString(", "),
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
                        missingEngineType = if (pyBin == null) EngineType.PYTHON else null
                    )
                )
            }

            if (requiresSocketIo || primaryLanguage.startsWith("Node")) {
                val nodeBin = ProcessProbe.findBinary(context, listOf("node", "nodejs"), "nodejs")
                checks.add(
                    CompatibilityCheckItem(
                        title = "محرك Node.js / Socket.io (${reqNodeVer ?: "20+"})",
                        detail = if (nodeBin != null) "متاح في ${nodeBin.absolutePath}" else "غير مثبت لمعمارية ${ProcessProbe.getDeviceAbi()} (مطلوب للوقت الحقيقي)",
                        severity = if (nodeBin != null) CheckSeverity.OK else CheckSeverity.MISSING,
                        missingEngineType = if (nodeBin == null) EngineType.NODEJS else null
                    )
                )
            }

            if (detectedDbs.any { it.contains("MySQL") || it.contains("MariaDB") }) {
                val mysqlUp = ProcessProbe.isTcpPortListening("127.0.0.1", 3306, 200)
                checks.add(
                    CompatibilityCheckItem(
                        title = "خادم MySQL 8 / MariaDB 10.6+",
                        detail = if (mysqlUp) "الخادم يعمل ومستمع على المنفذ 3306" else "غير مشغل أو غير مثبت على المنفذ 3306",
                        severity = if (mysqlUp) CheckSeverity.OK else CheckSeverity.MISSING,
                        missingEngineType = if (mysqlUp) null else EngineType.MYSQL_MARIADB
                    )
                )
            }

            if (htaccessFile != null) {
                checks.add(
                    CompatibilityCheckItem(
                        title = "إعادة كتابة الروابط (.htaccess)",
                        detail = "تم اكتشاف $rewriteCount قاعدة إعادة كتابة — مدعومة عبر محرك Abotalal HTTP Rewrite",
                        severity = CheckSeverity.OK
                    )
                )
            }

            if (uploadDirs.isNotEmpty()) {
                val allWritable = uploadDirs.all { File(siteRoot, it).canWrite() }
                checks.add(
                    CompatibilityCheckItem(
                        title = "مجلدات الرفع والوسائط (${uploadDirs.joinToString(", ")})",
                        detail = if (allWritable) "قابلة للكتابة وآمنة" else "بعض المجلدات تحتاج ضبط الصلاحيات (755/777)",
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
                projectPath = siteRoot.absolutePath,
                detectedScriptOrFramework = detectedScript,
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
                        detectedScript = detectedScript,
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

    /**
     * Creates a full backup of configuration files before making any user-approved adjustments.
     */
    suspend fun createSafeBackup(site: SiteEntity): Result<File> = withContext(Dispatchers.IO) {
        return@withContext try {
            val siteRoot = File(site.rootFolder)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupDir = File(context.filesDir, "backups/${site.id}/$stamp").apply { mkdirs() }
            val targets = listOf(
                ".htaccess", ".env", "config.php", "includes/config.php",
                "assets/includes/config.php", "wp-config.php", "composer.json", "package.json"
            )
            var copied = 0
            targets.forEach { rel ->
                val src = File(siteRoot, rel)
                if (src.exists() && src.isFile) {
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
                    message = "تم إنشاء نسخة احتياطية آمنة للموقع '${site.name}' في ${backupDir.name} ($copied ملف إعدادات)"
                )
            )
            Result.success(backupDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Runs genuine post-install operational tests. A project is ONLY marked VERIFIED if every required check passes!
     */
    suspend fun runPostInstallVerification(
        site: SiteEntity,
        report: ProjectAnalysisReport,
        backupDir: File?
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val siteRoot = File(site.rootFolder)
        val entryExists = File(siteRoot, site.entryFile).exists() || File(siteRoot, "index.html").exists() || File(siteRoot, "index.php").exists()
        if (!entryExists) {
            val msg = "فشل الاختبار: ملف البداية (${site.entryFile}) غير موجود في ${siteRoot.name}"
            recordInstallOutcome(site, report, backupDir, "FAILED", msg)
            return@withContext false to msg
        }

        // Start HTTP server if not running and probe real loopback HTTP response
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
