package com.example.engine.core

import android.system.Os
import com.example.core.security.SecurityHelper
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ArchiveExtractor {

    data class ExtractResult(
        val extractedFiles: Int,
        val extractedBytes: Long,
        val format: String
    )

    fun extractArchive(
        archiveFile: File,
        targetDir: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<ExtractResult> {
        if (!archiveFile.exists()) {
            return Result.failure(IllegalArgumentException("ملف الأرشيف غير موجود"))
        }
        targetDir.mkdirs()
        val nameLower = archiveFile.name.lowercase()
        return try {
            when {
                nameLower.endsWith(".zip") || nameLower.endsWith(".whl") ->
                    extractZip(archiveFile, targetDir, onProgress)
                nameLower.endsWith(".tar.gz") || nameLower.endsWith(".tgz") ->
                    extractTarStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))), targetDir, "tar.gz", onProgress)
                nameLower.endsWith(".tar.xz") || nameLower.endsWith(".txz") ->
                    extractTarStream(XZInputStream(BufferedInputStream(FileInputStream(archiveFile))), targetDir, "tar.xz", onProgress)
                nameLower.endsWith(".tar") ->
                    extractTarStream(BufferedInputStream(FileInputStream(archiveFile)), targetDir, "tar", onProgress)
                nameLower.endsWith(".deb") ->
                    extractDeb(archiveFile, targetDir, onProgress)
                else -> {
                    // Sniff magic bytes: PK (zip), !<arch> (deb/ar), 1F 8B (gzip)
                    val head = ByteArray(8)
                    FileInputStream(archiveFile).use { it.read(head) }
                    when {
                        head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() ->
                            extractZip(archiveFile, targetDir, onProgress)
                        String(head, 0, 7, Charsets.US_ASCII) == "!<arch>" ->
                            extractDeb(archiveFile, targetDir, onProgress)
                        head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() ->
                            extractTarStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))), targetDir, "tar.gz", onProgress)
                        else -> Result.failure(IllegalArgumentException("صيغة أرشيف غير مدعومة: ${archiveFile.name}"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZip(
        zipFile: File,
        targetDir: File,
        onProgress: (Float, String) -> Unit
    ): Result<ExtractResult> {
        var count = 0
        var totalBytes = 0L
        val buf = ByteArray(32 * 1024)
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = SecurityHelper.resolveSafeChild(targetDir, entry.name)
                    ?: throw SecurityException("مسار غير آمن داخل ZIP: ${entry.name}")
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            bos.write(buf, 0, n)
                            totalBytes += n
                        }
                    }
                    if (isExecutablePath(outFile)) {
                        outFile.setExecutable(true, true)
                    }
                    count++
                    if (count % 25 == 0) {
                        onProgress(0.5f, entry.name)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        onProgress(1f, "اكتمل فك ضغط ZIP")
        return Result.success(ExtractResult(count, totalBytes, "zip"))
    }

    private fun extractDeb(
        debFile: File,
        targetDir: File,
        onProgress: (Float, String) -> Unit
    ): Result<ExtractResult> {
        ArArchiveInputStream(BufferedInputStream(FileInputStream(debFile))).use { ais ->
            var entry = ais.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name.startsWith("data.tar.gz") -> {
                        return extractTarStream(GzipCompressorInputStream(ais), targetDir, "deb/tar.gz", onProgress)
                    }
                    name.startsWith("data.tar.xz") -> {
                        return extractTarStream(XZInputStream(ais), targetDir, "deb/tar.xz", onProgress)
                    }
                    name.startsWith("data.tar.zst") -> {
                        return Result.failure(
                            IllegalStateException("حزمة .deb تستخدم ضغط zstd (data.tar.zst) غير المدعوم محلياً بدون مفكك zstd. استخدم حزمة tar.xz / tar.gz / zip.")
                        )
                    }
                    name == "data.tar" -> {
                        return extractTarStream(ais, targetDir, "deb/tar", onProgress)
                    }
                }
                entry = ais.nextEntry
            }
        }
        return Result.failure(IllegalStateException("لم يُعثر على data.tar داخل حزمة .deb"))
    }

    private fun extractTarStream(
        rawStream: InputStream,
        targetDir: File,
        format: String,
        onProgress: (Float, String) -> Unit
    ): Result<ExtractResult> {
        var count = 0
        var totalBytes = 0L
        val buf = ByteArray(32 * 1024)
        val symlinks = mutableListOf<Pair<File, String>>()

        TarArchiveInputStream(BufferedInputStream(rawStream)).use { tis ->
            var entry: TarArchiveEntry? = tis.nextEntry
            while (entry != null) {
                val relPath = normalizeAndroidPackagePath(entry.name)
                if (relPath.isNotEmpty()) {
                    val outFile = SecurityHelper.resolveSafeChild(targetDir, relPath)
                        ?: throw SecurityException("مسار غير آمن داخل TAR: ${entry.name}")
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            symlinks.add(outFile to entry.linkName)
                        }
                        entry.isFile -> {
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                while (true) {
                                    val n = tis.read(buf)
                                    if (n <= 0) break
                                    bos.write(buf, 0, n)
                                    totalBytes += n
                                }
                            }
                            val mode = entry.mode
                            if ((mode and 0b001_000_000) != 0 || isExecutablePath(outFile)) {
                                outFile.setExecutable(true, false)
                                try {
                                    Os.chmod(outFile.absolutePath, 0b111_101_101)
                                } catch (_: Exception) {
                                }
                            }
                            count++
                            if (count % 25 == 0) {
                                onProgress(0.6f, relPath)
                            }
                        }
                    }
                }
                entry = tis.nextEntry
            }
        }

        // Create symlinks where supported by Android filesystem
        symlinks.forEach { (linkFile, target) ->
            try {
                linkFile.parentFile?.mkdirs()
                if (!linkFile.exists()) {
                    Os.symlink(target, linkFile.absolutePath)
                }
            } catch (_: Exception) {
            }
        }

        onProgress(1f, "اكتمل فك الحزمة ($format)")
        return Result.success(ExtractResult(count, totalBytes, format))
    }

    /**
     * Strips Termux/Android prefix paths (/data/data/com.termux/files/usr/) so unpacked packages
     * land cleanly inside the runtime's isolated bin/ and lib/ directories.
     */
    private fun normalizeAndroidPackagePath(rawName: String): String {
        var cleaned = rawName.trimStart('.', '/')
        val prefixes = listOf(
            "data/data/com.termux/files/usr/",
            "data/data/com.termux/files/",
            "usr/local/",
            "usr/"
        )
        for (p in prefixes) {
            if (cleaned.startsWith(p)) {
                cleaned = cleaned.removePrefix(p)
                break
            }
        }
        return cleaned.trimStart('/')
    }

    private fun isExecutablePath(file: File): Boolean {
        val path = file.absolutePath
        return path.contains("/bin/") || path.contains("/sbin/") ||
            file.name.endsWith(".so") || file.name.contains(".so.")
    }
}
