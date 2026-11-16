package com.example.engine.db

import android.content.Context
import android.net.Uri
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.SiteEntity
import com.example.core.model.EngineType
import com.example.core.model.PhpMyAdminRelease
import com.example.core.model.PhpMyAdminStatus
import com.example.core.model.ProjectType
import com.example.engine.core.ArchiveExtractor
import com.example.engine.core.PackageDownloader
import com.example.engine.core.ProcessProbe
import com.example.engine.core.RuntimeEngine
import com.example.engine.http.EmbeddedHttpServer
import com.example.engine.runtime.PhpRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

class PhpMyAdminManager(
    private val context: Context,
    private val downloader: PackageDownloader,
    private val runtimeEngine: RuntimeEngine,
    private val phpRuntimeManager: PhpRuntimeManager,
    private val httpServer: EmbeddedHttpServer,
    private val logDao: AppLogDao
) {
    private val pmaSiteId = "internal_phpmyadmin_8090"
    private val pmaPort = 8090
    private val requiredExtensions = listOf("mysqli", "mbstring", "openssl", "json", "zip")

    private val _status = MutableStateFlow(PhpMyAdminStatus())
    val status: StateFlow<PhpMyAdminStatus> = _status.asStateFlow()

    val pmaDir: File
        get() = runtimeEngine.getRuntimeDir(EngineType.PHPMYADMIN, "").apply { mkdirs() }

    suspend fun refreshStatus(
        selectedPhpVersion: String = _status.value.selectedPhpVersion,
        mysqlHost: String = _status.value.mysqlHost,
        mysqlPort: Int = _status.value.mysqlPort
    ): PhpMyAdminStatus = withContext(Dispatchers.IO) {
        val docRoot = resolvePmaDocRoot()
        val installed = File(docRoot, "index.php").exists()
        val running = httpServer.runningSiteIds.value.contains(pmaSiteId)
        val mysqlUp = ProcessProbe.isTcpPortListening(mysqlHost, mysqlPort, 250)

        val phpBin = phpRuntimeManager.findPhpBinary(selectedPhpVersion)
        val missingExts = if (phpBin != null) {
            val loaded = phpRuntimeManager.probeLoadedModules(selectedPhpVersion).getOrNull()?.map { it.lowercase() }
            if (loaded != null) {
                requiredExtensions.filter { req -> req !in loaded }
            } else {
                requiredExtensions
            }
        } else {
            requiredExtensions
        }

        val currentReleases = _status.value.availableReleases
        val versionFile = File(pmaDir, ".pma_version.txt")
        val installedVer = if (installed && versionFile.exists()) versionFile.readText().trim() else if (installed) "5.2.x" else "غير مثبت"

        val loginVerified = if (installed && running && phpBin != null) {
            probeLoginPage(mysqlUp)
        } else {
            false
        }

        val details = buildString {
            append(if (installed) "الملفات مثبتة ($installedVer). " else "phpMyAdmin غير مثبت. ")
            append(if (phpBin != null) "PHP $selectedPhpVersion متوفر. " else "يتطلب تثبيت PHP $selectedPhpVersion. ")
            if (phpBin != null && missingExts.isNotEmpty()) {
                append("إضافات ناقصة: ${missingExts.joinToString()}. ")
            }
            append(if (mysqlUp) "MySQL/MariaDB متصل على $mysqlHost:$mysqlPort. " else "خادم MySQL/MariaDB غير مشغل على $mysqlHost:$mysqlPort. ")
            if (loginVerified) append("تم التحقق من فتح صفحة تسجيل الدخول والاتصال.")
        }

        val updated = PhpMyAdminStatus(
            isInstalled = installed,
            isRunning = running,
            isVerified = installed && running && mysqlUp && loginVerified && missingExts.isEmpty(),
            installedVersion = installedVer,
            selectedPhpVersion = selectedPhpVersion,
            mysqlHost = mysqlHost,
            mysqlPort = mysqlPort,
            localUrl = "http://127.0.0.1:$pmaPort",
            missingPhpExtensions = missingExts,
            mysqlReachable = mysqlUp,
            loginPageVerified = loginVerified,
            verificationDetails = details,
            availableReleases = currentReleases
        )
        _status.value = updated
        updated
    }

    suspend fun fetchAvailableReleases(): Result<List<PhpMyAdminRelease>> = withContext(Dispatchers.IO) {
        val res = downloader.fetchText("https://www.phpmyadmin.net/downloads/list.json")
        val body = res.getOrElse {
            return@withContext Result.failure(IllegalStateException("تعذر جلب إصدارات phpMyAdmin الرسمية: ${it.message}"))
        }
        return@withContext try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("releases") ?: return@withContext Result.failure(IllegalStateException("قائمة الإصدارات فارغة"))
            val releases = mutableListOf<PhpMyAdminRelease>()
            for (i in 0 until arr.length()) {
                val rel = arr.optJSONObject(i) ?: continue
                val version = rel.optString("version")
                val date = rel.optString("date")
                val phpMin = rel.optString("php_versions", ">= 7.2")
                val files = rel.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val f = files.optJSONObject(j) ?: continue
                    val name = f.optString("name")
                    if (name.endsWith("-all-languages.zip")) {
                        val url = f.optString("url")
                        val sha = f.optString("sha256")
                        val size = f.optLong("size", 0L)
                        if (url.startsWith("https://") && sha.length == 64) {
                            releases.add(PhpMyAdminRelease(version, url, sha, size, phpMin, date))
                            break
                        }
                    }
                }
            }
            _status.value = _status.value.copy(availableReleases = releases)
            Result.success(releases)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadAndInstallRelease(
        release: PhpMyAdminRelease,
        phpVersion: String,
        mysqlHost: String,
        mysqlPort: Int
    ): Result<PhpMyAdminStatus> = withContext(Dispatchers.IO) {
        val cacheZip = File(context.cacheDir, "phpmyadmin-${release.version}.zip")
        val taskId = "pma_${release.version}"
        val dlRes = downloader.downloadFileVerified(
            taskId = taskId,
            packageId = "pma_${release.version}",
            title = "phpMyAdmin ${release.version}",
            url = release.downloadUrl,
            expectedSha256 = release.sha256,
            destFile = cacheZip
        )
        if (dlRes.isFailure) {
            return@withContext Result.failure(dlRes.exceptionOrNull() ?: IllegalStateException("فشل تنزيل phpMyAdmin"))
        }

        downloader.updateInstallProgress(taskId, "فك ضغط phpMyAdmin وإعداد config.inc.php...", 0.5f)
        pmaDir.deleteRecursively()
        pmaDir.mkdirs()

        val extRes = ArchiveExtractor.extractArchive(cacheZip, pmaDir)
        cacheZip.delete()
        if (extRes.isFailure) {
            val err = extRes.exceptionOrNull()?.message ?: "فشل فك ضغط phpMyAdmin"
            downloader.updateInstallProgress(taskId, "فشل الفك", 0f, isFinished = true, errorDetails = err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        flattenNestedFolderIfNeeded()
        File(pmaDir, ".pma_version.txt").writeText(release.version)
        writeConfigIncPhp(mysqlHost, mysqlPort)

        downloader.updateInstallProgress(taskId, "تشغيل واختبار واجهة phpMyAdmin...", 0.9f)
        val startStatus = startAndVerify(phpVersion, mysqlHost, mysqlPort)
        val verified = startStatus.getOrNull()?.isVerified == true
        downloader.updateInstallProgress(
            taskId = taskId,
            stageLabel = if (verified) "تم التثبيت والتحقق بنجاح" else "تم فك الملفات (يتطلب PHP و MySQL للتحقق)",
            installProgress = 1f,
            isFinished = true,
            isVerified = verified,
            errorDetails = if (verified) null else startStatus.getOrNull()?.verificationDetails ?: startStatus.exceptionOrNull()?.message,
            verificationResult = startStatus.getOrNull()?.verificationDetails
        )
        startStatus
    }

    suspend fun importLocalZip(
        uri: Uri,
        phpVersion: String = _status.value.selectedPhpVersion,
        mysqlHost: String = _status.value.mysqlHost,
        mysqlPort: Int = _status.value.mysqlPort
    ): Result<String> = withContext(Dispatchers.IO) {
        val tempZip = File(context.cacheDir, "pma_local.zip")
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempZip).use { input.copyTo(it) }
            } ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة ملف phpMyAdmin"))

            pmaDir.deleteRecursively()
            pmaDir.mkdirs()
            val extRes = ArchiveExtractor.extractArchive(tempZip, pmaDir)
            tempZip.delete()
            if (extRes.isFailure) {
                return@withContext Result.failure(extRes.exceptionOrNull() ?: IllegalStateException("فشل فك الضغط"))
            }
            flattenNestedFolderIfNeeded()
            if (!File(pmaDir, "index.php").exists()) {
                return@withContext Result.failure(IllegalStateException("الأرشيف لا يحتوي على index.php الخاص بـ phpMyAdmin"))
            }
            File(pmaDir, ".pma_version.txt").writeText("5.2.x (Local)")
            writeConfigIncPhp(mysqlHost, mysqlPort)

            val status = refreshStatus(phpVersion, mysqlHost, mysqlPort)
            logDao.insert(AppLogEntity(level = "INFO", source = "PHPMYADMIN", message = status.verificationDetails))
            Result.success(status.verificationDetails)
        } catch (e: Exception) {
            tempZip.delete()
            Result.failure(e)
        }
    }

    fun writeConfigIncPhp(mysqlHost: String, mysqlPort: Int) {
        val docRoot = resolvePmaDocRoot()
        val blowfish = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val configPhp = """
            <?php
            ${'$'}cfg['blowfish_secret'] = '$blowfish';
            ${'$'}i = 0;
            ${'$'}i++;
            ${'$'}cfg['Servers'][${'$'}i]['auth_type'] = 'cookie';
            ${'$'}cfg['Servers'][${'$'}i]['host'] = '$mysqlHost';
            ${'$'}cfg['Servers'][${'$'}i]['port'] = '$mysqlPort';
            ${'$'}cfg['Servers'][${'$'}i]['compress'] = false;
            ${'$'}cfg['Servers'][${'$'}i]['AllowNoPassword'] = false;
        """.trimIndent()
        File(docRoot, "config.inc.php").writeText(configPhp)
    }

    suspend fun startAndVerify(
        phpVersion: String = _status.value.selectedPhpVersion,
        mysqlHost: String = _status.value.mysqlHost,
        mysqlPort: Int = _status.value.mysqlPort
    ): Result<PhpMyAdminStatus> = withContext(Dispatchers.IO) {
        val docRoot = resolvePmaDocRoot()
        if (!File(docRoot, "index.php").exists()) {
            return@withContext Result.failure(IllegalStateException("ملفات phpMyAdmin غير مثبتة بعد"))
        }
        writeConfigIncPhp(mysqlHost, mysqlPort)

        val pmaSite = SiteEntity(
            id = pmaSiteId,
            name = "phpMyAdmin",
            rootFolder = docRoot.absolutePath,
            port = pmaPort,
            projectType = ProjectType.PHP_APP.name,
            runtimeVersion = phpVersion,
            entryFile = "index.php",
            directoryListingEnabled = false,
            allowLanAccess = false
        )
        val startRes = httpServer.startSite(pmaSite)
        if (startRes.isFailure) {
            return@withContext Result.failure(startRes.exceptionOrNull() ?: IllegalStateException("فشل تشغيل خادم phpMyAdmin على المنفذ $pmaPort"))
        }

        val st = refreshStatus(phpVersion, mysqlHost, mysqlPort)
        logDao.insert(
            AppLogEntity(
                level = if (st.isVerified) "INFO" else "WARN",
                source = "PHPMYADMIN",
                message = st.verificationDetails
            )
        )
        Result.success(st)
    }

    suspend fun stopPhpMyAdmin(): PhpMyAdminStatus {
        httpServer.stopSite(pmaSiteId)
        return refreshStatus()
    }

    private fun probeLoginPage(mysqlUp: Boolean): Boolean {
        if (!mysqlUp) return false
        return try {
            val conn = URL("http://127.0.0.1:$pmaPort/index.php").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val code = conn.responseCode
            val body = runCatching { conn.inputStream.bufferedReader().readText() }.getOrDefault("")
            conn.disconnect()
            code == 200 && (body.contains("phpMyAdmin", ignoreCase = true) || body.contains("pma_username", ignoreCase = true))
        } catch (_: Exception) {
            false
        }
    }

    private fun resolvePmaDocRoot(): File {
        if (File(pmaDir, "index.php").exists()) return pmaDir
        val nested = pmaDir.listFiles()?.firstOrNull { it.isDirectory && File(it, "index.php").exists() }
        return nested ?: pmaDir
    }

    private fun flattenNestedFolderIfNeeded() {
        if (File(pmaDir, "index.php").exists()) return
        val nested = pmaDir.listFiles()?.firstOrNull { it.isDirectory && File(it, "index.php").exists() } ?: return
        nested.listFiles()?.forEach { child ->
            val target = File(pmaDir, child.name)
            if (!target.exists()) child.renameTo(target)
        }
        nested.deleteRecursively()
    }
}
