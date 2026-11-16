package com.example.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.model.FileEntry
import com.example.core.model.TransferProgress
import com.example.core.security.SecurityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class IsolatedFileManager(
    private val context: Context,
    private val logDao: AppLogDao
) {
    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    val transferProgress: StateFlow<TransferProgress?> = _transferProgress.asStateFlow()

    fun dismissProgress() {
        _transferProgress.value = null
    }

    /**
     * Ensures every hosted site has its own strictly isolated directory structure:
     * - public_html/
     * - config/
     * - logs/
     * - backups/
     * - runtime_settings.json
     */
    fun ensureSiteWorkspace(
        siteRoot: File,
        siteId: String,
        siteName: String,
        projectType: String,
        runtimeVersion: String,
        port: Int,
        entryFile: String
    ): File {
        siteRoot.mkdirs()
        val publicHtml = File(siteRoot, "public_html").apply { mkdirs() }
        val configDir = File(siteRoot, "config").apply { mkdirs() }
        File(siteRoot, "logs").mkdirs()
        File(siteRoot, "backups").mkdirs()

        val settingsFile = File(siteRoot, "runtime_settings.json")
        if (!settingsFile.exists()) {
            val json = """
                {
                  "siteId": "$siteId",
                  "siteName": "$siteName",
                  "projectType": "$projectType",
                  "runtimeVersion": "$runtimeVersion",
                  "port": $port,
                  "entryFile": "$entryFile",
                  "documentRoot": "public_html",
                  "isolated": true
                }
            """.trimIndent()
            settingsFile.writeText(json)
        }
        val siteEnv = File(configDir, ".env")
        if (!siteEnv.exists()) {
            siteEnv.writeText("APP_NAME=\"$siteName\"\nAPP_PORT=$port\n")
        }
        return publicHtml
    }

    fun getSiteDocumentRoot(siteRoot: File): File {
        val publicHtml = File(siteRoot, "public_html")
        return if (publicHtml.exists() && publicHtml.isDirectory) publicHtml else siteRoot
    }

    suspend fun listDirectory(jailRoot: File, currentDir: File): List<FileEntry> = withContext(Dispatchers.IO) {
        val safeDir = SecurityHelper.resolveSafeChild(jailRoot, currentDir.relativeToOrSelf(jailRoot).path)
            ?: jailRoot
        if (!safeDir.exists()) safeDir.mkdirs()

        val children = safeDir.listFiles() ?: emptyArray()
        children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).map { file ->
            val (octal, symbolic) = readUnixPermissions(file)
            FileEntry(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) countDirSizeQuick(file) else file.length(),
                lastModified = file.lastModified(),
                permissionsOctal = octal,
                permissionsSymbolic = symbolic,
                canRead = file.canRead(),
                canWrite = file.canWrite(),
                canExecute = file.canExecute()
            )
        }
    }

    suspend fun createFolder(siteRoot: File, parentDir: File, folderName: String): Result<File> =
        withContext(Dispatchers.IO) {
            val safe = SecurityHelper.resolveSafeChild(
                siteRoot,
                File(parentDir, folderName.trim()).relativeToOrSelf(siteRoot).path
            ) ?: return@withContext Result.failure(SecurityException("مسار خارج النطاق المعزول"))
            if (safe.exists()) return@withContext Result.failure(IllegalStateException("المجلد موجود بالفعل"))
            if (safe.mkdirs()) Result.success(safe)
            else Result.failure(IllegalStateException("تعذر إنشاء المجلد"))
        }

    suspend fun createFile(siteRoot: File, parentDir: File, fileName: String, initialContent: String = ""): Result<File> =
        withContext(Dispatchers.IO) {
            val safe = SecurityHelper.resolveSafeChild(
                siteRoot,
                File(parentDir, fileName.trim()).relativeToOrSelf(siteRoot).path
            ) ?: return@withContext Result.failure(SecurityException("مسار خارج النطاق المعزول"))
            if (safe.exists()) return@withContext Result.failure(IllegalStateException("الملف موجود بالفعل"))
            safe.parentFile?.mkdirs()
            safe.writeText(initialContent)
            Result.success(safe)
        }

    suspend fun renameEntry(siteRoot: File, entry: FileEntry, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val source = File(entry.path)
            val parent = source.parentFile ?: return@withContext Result.failure(IllegalArgumentException("مسار غير صالح"))
            val target = SecurityHelper.resolveSafeChild(
                siteRoot,
                File(parent, newName.trim()).relativeToOrSelf(siteRoot).path
            ) ?: return@withContext Result.failure(SecurityException("اسم غير مسموح خارج النطاق المعزول"))
            if (target.exists()) return@withContext Result.failure(IllegalStateException("يوجد ملف أو مجلد بنفس الاسم"))
            if (source.renameTo(target)) Result.success(Unit)
            else Result.failure(IllegalStateException("تعذرت إعادة التسمية"))
        }

    suspend fun deleteEntry(siteRoot: File, entry: FileEntry): Result<Unit> = withContext(Dispatchers.IO) {
        val target = SecurityHelper.resolveSafeChild(
            siteRoot,
            File(entry.path).relativeToOrSelf(siteRoot).path
        ) ?: return@withContext Result.failure(SecurityException("غير مسموح بالحذف خارج مجلد الموقع"))
        if (target.canonicalFile == siteRoot.canonicalFile) {
            return@withContext Result.failure(SecurityException("لا يمكن حذف المجلد الجذري للموقع"))
        }
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (ok) Result.success(Unit) else Result.failure(IllegalStateException("فشل الحذف"))
    }

    suspend fun copyOrMoveEntry(
        siteRoot: File,
        sourcePath: String,
        targetDirPath: String,
        isMove: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val src = SecurityHelper.resolveSafeChild(
            siteRoot,
            File(sourcePath).relativeToOrSelf(siteRoot).path
        ) ?: return@withContext Result.failure(SecurityException("المصدر خارج المجلد المعزول"))
        val dstDir = SecurityHelper.resolveSafeChild(
            siteRoot,
            File(targetDirPath).relativeToOrSelf(siteRoot).path
        ) ?: return@withContext Result.failure(SecurityException("الوجهة خارج المجلد المعزول"))

        val dst = File(dstDir, src.name)
        if (dst.canonicalPath == src.canonicalPath) {
            return@withContext Result.failure(IllegalStateException("الوجهة مطابقة للمصدر"))
        }
        return@withContext try {
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
            } else {
                src.copyTo(dst, overwrite = true)
            }
            if (isMove) {
                if (src.isDirectory) src.deleteRecursively() else src.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readTextFile(siteRoot: File, filePath: String): Result<String> = withContext(Dispatchers.IO) {
        val safe = SecurityHelper.resolveSafeChild(
            siteRoot,
            File(filePath).relativeToOrSelf(siteRoot).path
        ) ?: return@withContext Result.failure(SecurityException("الوصول مرفوض"))
        if (safe.length() > 2 * 1024 * 1024) {
            return@withContext Result.failure(IllegalStateException("حجم الملف أكبر من 2 ميجابايت للمحرر النصي"))
        }
        try {
            Result.success(safe.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun writeTextFile(siteRoot: File, filePath: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val safe = SecurityHelper.resolveSafeChild(
                siteRoot,
                File(filePath).relativeToOrSelf(siteRoot).path
            ) ?: return@withContext Result.failure(SecurityException("الوصول مرفوض"))
            try {
                safe.writeText(content, Charsets.UTF_8)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun uploadMultipleFiles(
        siteRoot: File,
        targetDir: File,
        uris: List<Uri>
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext Result.success(0)
        val metas = uris.map { uri ->
            val (name, size) = queryDisplayNameAndSize(uri)
            Triple(uri, name, size.coerceAtLeast(1L))
        }
        val totalBytes = metas.sumOf { it.third }.coerceAtLeast(1L)
        var processedBytes = 0L
        var uploadedCount = 0
        val buffer = ByteArray(32 * 1024)

        return@withContext try {
            for ((idx, item) in metas.withIndex()) {
                val (uri, fileName, _) = item
                val safeTarget = SecurityHelper.resolveSafeChild(
                    siteRoot,
                    File(targetDir, fileName).relativeToOrSelf(siteRoot).path
                ) ?: continue

                safeTarget.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(safeTarget).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            processedBytes += read
                            _transferProgress.value = TransferProgress(
                                operationLabel = "رفع الملفات إلى المشروع",
                                currentFileName = fileName,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                                processedCount = idx + 1,
                                totalCount = metas.size,
                                progress = (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                uploadedCount++
            }
            _transferProgress.value = TransferProgress(
                operationLabel = "اكتمل الرفع",
                currentFileName = "تم رفع $uploadedCount ملف",
                processedBytes = totalBytes,
                totalBytes = totalBytes,
                processedCount = uploadedCount,
                totalCount = metas.size,
                progress = 1f,
                isCompleted = true
            )
            Result.success(uploadedCount)
        } catch (e: Exception) {
            _transferProgress.value = TransferProgress(
                operationLabel = "فشل الرفع",
                currentFileName = "",
                processedBytes = processedBytes,
                totalBytes = totalBytes,
                processedCount = uploadedCount,
                totalCount = metas.size,
                progress = 0f,
                isCompleted = true,
                error = e.message
            )
            Result.failure(e)
        }
    }

    suspend fun importAndExtractZipFromUri(
        siteRoot: File,
        targetDir: File,
        zipUri: Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        val (zipName, zipTotalSize) = queryDisplayNameAndSize(zipUri)
        val safeTotal = zipTotalSize.coerceAtLeast(1L)
        var extractedFiles = 0
        var bytesReadCompressed = 0L
        val buffer = ByteArray(32 * 1024)

        return@withContext try {
            // First pass to count entries for accurate progress
            var totalEntries = 0
            context.contentResolver.openInputStream(zipUri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    while (zis.nextEntry != null) {
                        totalEntries++
                        zis.closeEntry()
                    }
                }
            }
            val safeEntries = totalEntries.coerceAtLeast(1)

            context.contentResolver.openInputStream(zipUri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    var idx = 0
                    while (entry != null) {
                        idx++
                        val relTarget = File(targetDir, entry.name).relativeToOrSelf(siteRoot).path
                        val outFile = SecurityHelper.resolveSafeChild(siteRoot, relTarget)
                            ?: throw SecurityException("تم حظر مسار Zip-Slip غير آمن: ${entry.name}")

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                while (true) {
                                    val count = zis.read(buffer)
                                    if (count <= 0) break
                                    bos.write(buffer, 0, count)
                                    bytesReadCompressed += count
                                }
                            }
                            extractedFiles++
                        }

                        val progressVal = (idx.toFloat() / safeEntries.toFloat()).coerceIn(0f, 1f)
                        _transferProgress.value = TransferProgress(
                            operationLabel = "فك ضغط $zipName",
                            currentFileName = entry.name,
                            processedBytes = bytesReadCompressed,
                            totalBytes = safeTotal,
                            processedCount = idx,
                            totalCount = safeEntries,
                            progress = progressVal
                        )
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            _transferProgress.value = TransferProgress(
                operationLabel = "اكتمل فك الضغط",
                currentFileName = "تم استخراج $extractedFiles ملف",
                processedBytes = bytesReadCompressed,
                totalBytes = bytesReadCompressed.coerceAtLeast(1L),
                processedCount = extractedFiles,
                totalCount = extractedFiles,
                progress = 1f,
                isCompleted = true
            )
            logDao.insert(
                AppLogEntity(
                    level = "INFO",
                    source = "FILES",
                    message = "تم استخراج الأرشيف $zipName ($extractedFiles ملف) بنجاح"
                )
            )
            Result.success(extractedFiles)
        } catch (e: Exception) {
            logDao.insert(
                AppLogEntity(
                    level = "ERROR",
                    source = "FILES",
                    message = "فشل فك ضغط $zipName: ${e.message}"
                )
            )
            _transferProgress.value = TransferProgress(
                operationLabel = "خطأ في فك الضغط",
                currentFileName = zipName,
                processedBytes = bytesReadCompressed,
                totalBytes = safeTotal,
                processedCount = extractedFiles,
                totalCount = 1,
                progress = 0f,
                isCompleted = true,
                error = e.message
            )
            Result.failure(e)
        }
    }

    suspend fun extractLocalZip(siteRoot: File, zipEntry: FileEntry, targetDir: File): Result<Int> {
        return importAndExtractZipFromUri(siteRoot, targetDir, Uri.fromFile(File(zipEntry.path)))
    }

    suspend fun compressToZip(
        siteRoot: File,
        sourceFiles: List<File>,
        outputZipFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val safeOut = SecurityHelper.resolveSafeChild(
            siteRoot,
            outputZipFile.relativeToOrSelf(siteRoot).path
        ) ?: return@withContext Result.failure(SecurityException("مسار غير مسموح"))

        val allFiles = mutableListOf<Pair<File, String>>()
        sourceFiles.forEach { root ->
            val baseParent = root.parentFile ?: siteRoot
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    allFiles.add(f to f.relativeTo(baseParent).path.replace('\\', '/'))
                }
            } else if (root.isFile) {
                allFiles.add(root to root.name)
            }
        }
        val totalBytes = allFiles.sumOf { it.first.length() }.coerceAtLeast(1L)
        var processed = 0L
        val buffer = ByteArray(32 * 1024)

        return@withContext try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(safeOut))).use { zos ->
                allFiles.forEachIndexed { idx, (file, zipPath) ->
                    zos.putNextEntry(ZipEntry(zipPath))
                    BufferedInputStream(FileInputStream(file)).use { bis ->
                        while (true) {
                            val count = bis.read(buffer)
                            if (count <= 0) break
                            zos.write(buffer, 0, count)
                            processed += count
                            _transferProgress.value = TransferProgress(
                                operationLabel = "ضغط الملفات إلى ZIP",
                                currentFileName = file.name,
                                processedBytes = processed,
                                totalBytes = totalBytes,
                                processedCount = idx + 1,
                                totalCount = allFiles.size,
                                progress = (processed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            )
                        }
                    }
                    zos.closeEntry()
                }
            }
            _transferProgress.value = TransferProgress(
                operationLabel = "تم الضغط بنجاح",
                currentFileName = safeOut.name,
                processedBytes = totalBytes,
                totalBytes = totalBytes,
                processedCount = allFiles.size,
                totalCount = allFiles.size,
                progress = 1f,
                isCompleted = true
            )
            Result.success(safeOut)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setUnixPermissions(siteRoot: File, filePath: String, octalMode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val safe = SecurityHelper.resolveSafeChild(
                siteRoot,
                File(filePath).relativeToOrSelf(siteRoot).path
            ) ?: return@withContext Result.failure(SecurityException("الوصول مرفوض"))
            return@withContext try {
                val modeInt = octalMode.toInt(8)
                Os.chmod(safe.absolutePath, modeInt)
                logDao.insert(
                    AppLogEntity(
                        level = if (octalMode == "777") "WARN" else "INFO",
                        source = "PERMISSIONS",
                        message = "تم تعديل صلاحيات ${safe.name} إلى $octalMode"
                    )
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(IllegalStateException("نظام الملفات لا يدعم تعديل الصلاحيات أو رفض العملية: ${e.message}"))
            }
        }

    /**
     * Restores secure production permissions after installation:
     * Directories -> 755, Sensitive Configs (.env, config.php) -> 700, Regular Files -> 644.
     * Never applies 777 globally.
     */
    suspend fun restoreSafePermissions(siteRoot: File): Result<Int> = withContext(Dispatchers.IO) {
        if (!siteRoot.exists()) return@withContext Result.failure(IllegalStateException("المجلد غير موجود"))
        var updatedCount = 0
        return@withContext try {
            val sensitiveNames = setOf(".env", "config.php", "wp-config.php", ".htaccess")
            siteRoot.walkTopDown().forEach { f ->
                val mode = when {
                    f.isDirectory -> "755"
                    f.name.lowercase() in sensitiveNames -> "700"
                    else -> "644"
                }
                try {
                    Os.chmod(f.absolutePath, mode.toInt(8))
                    updatedCount++
                } catch (_: Exception) {
                }
            }
            logDao.insert(
                AppLogEntity(
                    level = "SECURITY",
                    source = "PERMISSIONS",
                    message = "تمت استعادة الصلاحيات الآمنة (755 مجلدات / 644 ملفات / 700 إعدادات حساسة) على $updatedCount عنصر"
                )
            )
            Result.success(updatedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readUnixPermissions(file: File): Pair<String, String> {
        return try {
            val stat = Os.stat(file.absolutePath)
            val mode = stat.st_mode and 0b111_111_111
            val octal = String.format("%03o", mode)
            val chars = charArrayOf(
                if (mode and 256 != 0) 'r' else '-',
                if (mode and 128 != 0) 'w' else '-',
                if (mode and 64 != 0) 'x' else '-',
                if (mode and 32 != 0) 'r' else '-',
                if (mode and 16 != 0) 'w' else '-',
                if (mode and 8 != 0) 'x' else '-',
                if (mode and 4 != 0) 'r' else '-',
                if (mode and 2 != 0) 'w' else '-',
                if (mode and 1 != 0) 'x' else '-'
            )
            octal to String(chars)
        } catch (_: Exception) {
            val r = if (file.canRead()) 'r' else '-'
            val w = if (file.canWrite()) 'w' else '-'
            val x = if (file.canExecute()) 'x' else '-'
            "---" to "$r$w$x------"
        }
    }

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "uploaded_file_${System.currentTimeMillis()}"
        var size = 0L
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            return f.name to f.length()
        }
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }

    private fun countDirSizeQuick(dir: File): Long {
        var sum = 0L
        dir.listFiles()?.forEach { f ->
            sum += if (f.isFile) f.length() else 0L
        }
        return sum
    }
}
