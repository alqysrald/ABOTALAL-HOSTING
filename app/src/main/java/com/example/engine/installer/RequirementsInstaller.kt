package com.example.engine.installer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.model.EngineType
import com.example.core.model.PackageInstallProgress
import com.example.core.model.TrustedEnginePackage
import com.example.core.security.SecurityHelper
import com.example.engine.core.ProcessProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class RequirementsInstaller(
    private val context: Context,
    private val logDao: AppLogDao
) {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val _installProgress = MutableStateFlow<PackageInstallProgress?>(null)
    val installProgress: StateFlow<PackageInstallProgress?> = _installProgress.asStateFlow()

    fun dismissProgress() {
        _installProgress.value = null
    }

    fun isInternetAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getCompatibleCatalog(): List<TrustedEnginePackage> {
        val abi = ProcessProbe.getDeviceAbi()
        return listOf(
            TrustedEnginePackage(
                id = "pkg_php_84_$abi",
                engineType = EngineType.PHP,
                version = "8.4.1",
                abi = abi,
                descriptionAr = "حزمة PHP 8.4 CGI + إضافات Sngine/WoWonder ($abi)",
                officialDownloadUrl = "",
                expectedSha256 = "",
                approxSizeLabel = "~18 MB"
            ),
            TrustedEnginePackage(
                id = "pkg_node_20_$abi",
                engineType = EngineType.NODEJS,
                version = "20.18 LTS",
                abi = abi,
                descriptionAr = "بيئة Node.js 20 LTS + Socket.io ($abi)",
                officialDownloadUrl = "",
                expectedSha256 = "",
                approxSizeLabel = "~24 MB"
            ),
            TrustedEnginePackage(
                id = "pkg_python_312_$abi",
                engineType = EngineType.PYTHON,
                version = "3.12.7",
                abi = abi,
                descriptionAr = "مفسر Python 3.12 + pip ($abi)",
                officialDownloadUrl = "",
                expectedSha256 = "",
                approxSizeLabel = "~22 MB"
            ),
            TrustedEnginePackage(
                id = "pkg_mariadb_106_$abi",
                engineType = EngineType.MYSQL_MARIADB,
                version = "MariaDB 10.6",
                abi = abi,
                descriptionAr = "خادم MariaDB 10.6 المحلي ($abi)",
                officialDownloadUrl = "",
                expectedSha256 = "",
                approxSizeLabel = "~35 MB"
            )
        )
    }

    /**
     * Downloads an engine archive ONLY from an explicit HTTPS source with mandatory SHA-256 integrity verification,
     * extracts safely, and verifies actual execution via ProcessProbe before claiming success.
     */
    suspend fun downloadAndVerifyPackage(
        pkg: TrustedEnginePackage,
        httpsUrl: String,
        expectedSha256Hex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = httpsUrl.trim()
        val cleanHash = expectedSha256Hex.trim().lowercase()

        if (!cleanUrl.startsWith("https://")) {
            val err = "تم الرفض: يجب أن يكون مصدر الحزمة رابط HTTPS موثوقاً."
            logDao.insert(AppLogEntity(level = "SECURITY", source = "REQ_INSTALLER", message = err))
            return@withContext Result.failure(SecurityException(err))
        }
        if (cleanHash.length != 64 || !cleanHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            val err = "تم الرفض: مطلوب بصمة SHA-256 صالحة (64 حرفاً) للتحقق من سلامة الحزمة."
            logDao.insert(AppLogEntity(level = "SECURITY", source = "REQ_INSTALLER", message = err))
            return@withContext Result.failure(SecurityException(err))
        }
        if (!isInternetAvailable()) {
            return@withContext Result.failure(IllegalStateException("لا يوجد اتصال بالإنترنت لتنزيل الحزمة. المواقع المحلية تعمل بدون إنترنت بعد التثبيت."))
        }

        val tempZip = File(context.cacheDir, "${pkg.id}.zip")
        return@withContext try {
            _installProgress.value = PackageInstallProgress(pkg.id, "جاري الاتصال بالمصدر الآمن...", 0.05f, 0L, 1L)
            val req = Request.Builder().url(cleanUrl).build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("فشل التنزيل: HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("الاستجابة فارغة")
                val total = body.contentLength().coerceAtLeast(1L)
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(32 * 1024)
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            downloaded += n
                            _installProgress.value = PackageInstallProgress(
                                packageId = pkg.id,
                                stageLabel = "تنزيل وفحص التجزئة...",
                                progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0.05f, 0.85f),
                                bytesDownloaded = downloaded,
                                totalBytes = total
                            )
                        }
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(cleanHash, ignoreCase = true)) {
                    tempZip.delete()
                    val msg = "فشل التحقق من سلامة الحزمة (SHA-256 غير مطابق). تم حذف الملف لحماية الجهاز."
                    logDao.insert(AppLogEntity(level = "SECURITY", source = "REQ_INSTALLER", message = msg))
                    throw SecurityException(msg)
                }
            }

            extractAndTestBinary(pkg, tempZip)
        } catch (e: Exception) {
            tempZip.delete()
            _installProgress.value = PackageInstallProgress(
                packageId = pkg.id,
                stageLabel = "فشل التثبيت",
                progress = 0f,
                bytesDownloaded = 0L,
                totalBytes = 1L,
                isFinished = true,
                isSuccess = false,
                errorMessage = e.message
            )
            Result.failure(e)
        }
    }

    /**
     * Imports a local verified .zip package via SAF, optionally verifies SHA-256, extracts, and runs an execution test.
     */
    suspend fun importLocalPackageAndTest(
        pkg: TrustedEnginePackage,
        uri: Uri,
        optionalSha256: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val tempZip = File(context.cacheDir, "${pkg.id}_local.zip")
        return@withContext try {
            _installProgress.value = PackageInstallProgress(pkg.id, "قراءة الحزمة والتحقق من SHA-256...", 0.25f, 0L, 1L)
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempZip).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة ملف الحزمة"))

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = optionalSha256?.trim()?.lowercase().orEmpty()
            if (expected.isNotBlank() && actualSha != expected) {
                tempZip.delete()
                val msg = "بصمة SHA-256 غير مطابقة! المحسوبة: ${actualSha.take(16)}..."
                logDao.insert(AppLogEntity(level = "SECURITY", source = "REQ_INSTALLER", message = msg))
                return@withContext Result.failure(SecurityException(msg))
            }

            extractAndTestBinary(pkg, tempZip)
        } catch (e: Exception) {
            tempZip.delete()
            Result.failure(e)
        }
    }

    private suspend fun extractAndTestBinary(pkg: TrustedEnginePackage, zipFile: File): Result<String> {
        val subDir = when (pkg.engineType) {
            EngineType.PHP -> "php/${pkg.version.substringBeforeLast('.')}"
            EngineType.PYTHON -> "python/${pkg.version.substringBeforeLast('.')}"
            EngineType.NODEJS -> "nodejs/${pkg.version.substringBefore('.')}"
            EngineType.MYSQL_MARIADB -> "mariadb"
            EngineType.POSTGRESQL -> "postgresql"
            else -> pkg.engineType.name.lowercase()
        }
        val targetDir = File(context.filesDir, "runtimes/$subDir").apply { mkdirs() }

        _installProgress.value = PackageInstallProgress(pkg.id, "فك ضغط الحزمة في المسار المعزول...", 0.9f, 1L, 1L)
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = SecurityHelper.resolveSafeChild(targetDir, entry.name)
                    ?: throw SecurityException("مسار غير آمن داخل الأرشيف: ${entry.name}")
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                    if (!outFile.name.contains('.')) {
                        outFile.setExecutable(true, true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zipFile.delete()

        val candidates = when (pkg.engineType) {
            EngineType.PHP -> listOf("php-cgi", "php")
            EngineType.PYTHON -> listOf("python3", "python")
            EngineType.NODEJS -> listOf("node", "nodejs")
            EngineType.MYSQL_MARIADB -> listOf("mariadbd", "mysqld")
            else -> listOf("postgres")
        }
        val flag = if (pkg.engineType == EngineType.PHP || pkg.engineType == EngineType.NODEJS) "-v" else "--version"
        val bin = ProcessProbe.findBinary(context, candidates, subDir)
            ?: return Result.failure(IllegalStateException("تم فك الحزمة لكن لم يُعثر على الملف التنفيذي المطلوب (${candidates.joinToString()})"))

        val (ok, output) = ProcessProbe.probeBinaryVersion(bin, flag)
        _installProgress.value = PackageInstallProgress(
            packageId = pkg.id,
            stageLabel = if (ok) "تم التثبيت واجتاز اختبار التشغيل" else "فشل اختبار التشغيل",
            progress = 1f,
            bytesDownloaded = 1L,
            totalBytes = 1L,
            isFinished = true,
            isSuccess = ok,
            errorMessage = if (ok) null else output
        )

        return if (ok) {
            logDao.insert(AppLogEntity(level = "INFO", source = "REQ_INSTALLER", message = "تم تثبيت ${pkg.engineType.titleAr} واختباره بنجاح: $output"))
            Result.success("تم التثبيت واجتاز الاختبار: $output")
        } else {
            logDao.insert(AppLogEntity(level = "ERROR", source = "REQ_INSTALLER", message = "فشل اختبار ${pkg.engineType.titleAr} بعد التثبيت: $output"))
            Result.failure(IllegalStateException("تم فك الملفات لكن نظام Android رفض التنفيذ (W^X/SELinux): $output"))
        }
    }
}
