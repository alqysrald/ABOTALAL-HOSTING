package com.example.engine.core

import android.content.Context
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.model.DownloadTaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PackageDownloader(
    private val context: Context,
    private val logDao: AppLogDao
) {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()

    private val _downloadTask = MutableStateFlow<DownloadTaskState?>(null)
    val downloadTask: StateFlow<DownloadTaskState?> = _downloadTask.asStateFlow()

    fun dismissTask() {
        _downloadTask.value = null
    }

    fun cancelDownload(taskId: String) {
        cancelledTasks.add(taskId)
        activeCalls[taskId]?.cancel()
        val current = _downloadTask.value
        if (current != null && current.taskId == taskId) {
            _downloadTask.value = current.copy(
                stageLabel = "تم إلغاء التنزيل بواسطة المستخدم",
                isDownloading = false,
                isInstalling = false,
                isCancelled = true,
                isFinished = true,
                errorDetails = "تم إلغاء المهمة قبل الاكتمال"
            )
        }
    }

    fun updateInstallProgress(
        taskId: String,
        stageLabel: String,
        installProgress: Float,
        isFinished: Boolean = false,
        isVerified: Boolean = false,
        errorDetails: String? = null,
        verificationResult: String? = null
    ) {
        val current = _downloadTask.value ?: return
        if (current.taskId != taskId) return
        _downloadTask.value = current.copy(
            stageLabel = stageLabel,
            installProgress = installProgress.coerceIn(0f, 1f),
            isDownloading = false,
            isInstalling = !isFinished,
            isFinished = isFinished,
            isVerified = isVerified,
            errorDetails = errorDetails,
            verificationResult = verificationResult
        )
    }

    suspend fun downloadFileVerified(
        taskId: String,
        packageId: String,
        title: String,
        url: String,
        expectedSha256: String,
        destFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        val cleanHash = expectedSha256.trim().lowercase()
        cancelledTasks.remove(taskId)

        val isLoopbackTest = cleanUrl.startsWith("http://127.0.0.1") || cleanUrl.startsWith("http://localhost")
        if (!cleanUrl.startsWith("https://") && !isLoopbackTest) {
            val err = "تم الرفض: يتطلب التنزيل رابط HTTPS موثوقاً."
            _downloadTask.value = DownloadTaskState(
                taskId = taskId,
                packageId = packageId,
                title = title,
                stageLabel = "فشل التحقق من المصدر",
                isFinished = true,
                errorDetails = err,
                retryUrl = cleanUrl,
                retrySha256 = cleanHash
            )
            logDao.insert(AppLogEntity(level = "SECURITY", source = "DOWNLOADER", message = err))
            return@withContext Result.failure(SecurityException(err))
        }

        if (cleanHash.isNotBlank() && (cleanHash.length != 64 || !cleanHash.all { it in '0'..'9' || it in 'a'..'f' })) {
            val err = "بصمة SHA-256 غير صالحة (يجب أن تكون 64 حرفاً ست عشرية)."
            _downloadTask.value = DownloadTaskState(
                taskId = taskId,
                packageId = packageId,
                title = title,
                stageLabel = "بصمة SHA-256 غير صالحة",
                isFinished = true,
                errorDetails = err,
                retryUrl = cleanUrl,
                retrySha256 = cleanHash
            )
            return@withContext Result.failure(SecurityException(err))
        }

        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()

        _downloadTask.value = DownloadTaskState(
            taskId = taskId,
            packageId = packageId,
            title = title,
            stageLabel = "جاري الاتصال بالخادم...",
            isDownloading = true,
            retryUrl = cleanUrl,
            retrySha256 = cleanHash
        )

        val request = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", "AbotalalHosting-Android/1.0")
            .build()
        val call = okHttp.newCall(request)
        activeCalls[taskId] = call

        return@withContext try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ خادم التنزيل: HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("استجابة التنزيل فارغة")
                val totalBytes = body.contentLength().coerceAtLeast(1L)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                var downloaded = 0L
                val startNs = System.nanoTime()
                var lastUiUpdateMs = 0L

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        while (true) {
                            if (cancelledTasks.contains(taskId)) {
                                throw CancellationException("تم إلغاء التنزيل")
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read

                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastUiUpdateMs >= 120L || downloaded == totalBytes) {
                                lastUiUpdateMs = nowMs
                                val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                                val speed = (downloaded / elapsedSec).toLong()
                                _downloadTask.value = DownloadTaskState(
                                    taskId = taskId,
                                    packageId = packageId,
                                    title = title,
                                    stageLabel = "جاري التنزيل...",
                                    downloadProgress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                                    bytesDownloaded = downloaded,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = speed,
                                    isDownloading = true,
                                    retryUrl = cleanUrl,
                                    retrySha256 = cleanHash
                                )
                            }
                        }
                    }
                }

                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (cleanHash.isNotBlank() && !actualSha.equals(cleanHash, ignoreCase = true)) {
                    destFile.delete()
                    val mismatch = "فشل التحقق من SHA-256! المتوقع: ${cleanHash.take(12)}... الفعلي: ${actualSha.take(12)}..."
                    logDao.insert(AppLogEntity(level = "SECURITY", source = "DOWNLOADER", message = mismatch))
                    throw SecurityException(mismatch)
                }

                _downloadTask.value = DownloadTaskState(
                    taskId = taskId,
                    packageId = packageId,
                    title = title,
                    stageLabel = "اكتمل التنزيل وتم التحقق من SHA-256 (${actualSha.take(10)}...)",
                    downloadProgress = 1f,
                    installProgress = 0.1f,
                    bytesDownloaded = downloaded,
                    totalBytes = downloaded.coerceAtLeast(totalBytes),
                    speedBytesPerSec = 0L,
                    isDownloading = false,
                    isInstalling = true,
                    retryUrl = cleanUrl,
                    retrySha256 = cleanHash
                )
                logDao.insert(
                    AppLogEntity(
                        level = "INFO",
                        source = "DOWNLOADER",
                        message = "تم تنزيل '$title' (${downloaded} بايت) والتحقق من بصمته بنجاح"
                    )
                )
                Result.success(destFile)
            }
        } catch (e: Exception) {
            destFile.delete()
            val isCancel = cancelledTasks.contains(taskId) || e is CancellationException
            val msg = if (isCancel) "تم إلغاء التنزيل" else (e.message ?: "خطأ غير معروف أثناء التنزيل")
            _downloadTask.value = DownloadTaskState(
                taskId = taskId,
                packageId = packageId,
                title = title,
                stageLabel = if (isCancel) "ملغى" else "فشل التنزيل",
                isDownloading = false,
                isInstalling = false,
                isCancelled = isCancel,
                isFinished = true,
                isVerified = false,
                errorDetails = msg,
                retryUrl = cleanUrl,
                retrySha256 = cleanHash
            )
            logDao.insert(
                AppLogEntity(
                    level = if (isCancel) "WARN" else "ERROR",
                    source = "DOWNLOADER",
                    message = "[$title] $msg"
                )
            )
            Result.failure(e)
        } finally {
            activeCalls.remove(taskId)
        }
    }

    suspend fun fetchText(url: String): Result<String> = withContext(Dispatchers.IO) {
        val clean = url.trim()
        val isLoopback = clean.startsWith("http://127.0.0.1") || clean.startsWith("http://localhost")
        if (!clean.startsWith("https://") && !isLoopback) {
            return@withContext Result.failure(SecurityException("يُسمح فقط بطلبات HTTPS للمستودعات الرسمية"))
        }
        return@withContext try {
            val req = Request.Builder()
                .url(clean)
                .header("User-Agent", "AbotalalHosting-Android/1.0")
                .build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("فشل جلب الفهرس: HTTP ${resp.code}")
                }
                val text = resp.body?.string() ?: throw IllegalStateException("محتوى الاستجابة فارغ")
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
