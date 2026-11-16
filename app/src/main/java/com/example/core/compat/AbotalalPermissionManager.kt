package com.example.core.compat

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.security.SecurityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PermissionHealthSnapshot(
    val apiLevel: Int,
    val storageStrategy: StorageStrategy,
    val requiresLegacyRuntimeStorage: Boolean,
    val legacyReadStorageGranted: Boolean,
    val legacyWriteStorageGranted: Boolean,
    val supportsManageAllFiles: Boolean,
    val allFilesAccessGranted: Boolean,
    val requiresPostNotificationsPermission: Boolean,
    val notificationsGranted: Boolean,
    val vpnConsentGranted: Boolean,
    val persistedSafTreesCount: Int,
    val persistedSafLabels: List<String>,
    val isolatedSitesWritable: Boolean
)

class AbotalalPermissionManager(
    private val context: Context,
    private val compatibilityLayer: AndroidCompatibilityLayer,
    private val logDao: AppLogDao,
    private val apiLevelProvider: () -> Int = { Build.VERSION.SDK_INT }
) {
    private val _snapshot = MutableStateFlow(computeSnapshot())
    val snapshot: StateFlow<PermissionHealthSnapshot> = _snapshot.asStateFlow()

    fun refreshSnapshot(): PermissionHealthSnapshot {
        val current = computeSnapshot()
        _snapshot.value = current
        return current
    }

    fun computeSnapshot(): PermissionHealthSnapshot {
        val api = apiLevelProvider()
        val profile = compatibilityLayer.collectDeviceProfile()

        val requiresLegacy = api in Build.VERSION_CODES.M..Build.VERSION_CODES.Q
        val legacyRead = if (api < Build.VERSION_CODES.M) {
            true
        } else if (api <= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // Replaced by Scoped Storage + SAF on API 30+
        }

        val legacyWrite = if (api < Build.VERSION_CODES.M) {
            true
        } else if (api <= Build.VERSION_CODES.P) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val supportsAllFiles = api >= Build.VERSION_CODES.R
        val allFilesGranted = if (supportsAllFiles) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            legacyWrite
        }

        val requiresPostNotif = api >= Build.VERSION_CODES.TIRAMISU
        val notifGranted = if (requiresPostNotif) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (api >= Build.VERSION_CODES.N && nm != null) {
                    nm.areNotificationsEnabled()
                } else {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }
            }.getOrDefault(true)
        }

        val vpnGranted = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

        val persisted = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        val labels = persisted.mapNotNull { perm ->
            runCatching {
                DocumentFile.fromTreeUri(context, perm.uri)?.name ?: perm.uri.lastPathSegment
            }.getOrNull() ?: perm.uri.toString()
        }

        val sitesDir = File(context.filesDir, "sites").apply { mkdirs() }
        val isolatedWritable = sitesDir.exists() && sitesDir.canWrite()

        return PermissionHealthSnapshot(
            apiLevel = api,
            storageStrategy = profile.storageStrategy,
            requiresLegacyRuntimeStorage = requiresLegacy,
            legacyReadStorageGranted = legacyRead,
            legacyWriteStorageGranted = legacyWrite,
            supportsManageAllFiles = supportsAllFiles,
            allFilesAccessGranted = allFilesGranted,
            requiresPostNotificationsPermission = requiresPostNotif,
            notificationsGranted = notifGranted,
            vpnConsentGranted = vpnGranted,
            persistedSafTreesCount = persisted.size,
            persistedSafLabels = labels,
            isolatedSitesWritable = isolatedWritable
        )
    }

    fun getRequiredLegacyPermissions(): Array<String> {
        val api = apiLevelProvider()
        return when {
            api in Build.VERSION_CODES.M..Build.VERSION_CODES.P -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            api == Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> emptyArray()
        }
    }

    fun buildManageAllFilesSettingsIntent(): Intent? {
        if (apiLevelProvider() < Build.VERSION_CODES.R) return null
        return try {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } catch (_: Exception) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun buildAppNotificationSettingsIntent(): Intent {
        val api = apiLevelProvider()
        return if (api >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    suspend fun persistSafTreePermission(treeUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }.onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            val label = DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.lastPathSegment ?: "SAF Tree"
            refreshSnapshot()
            logDao.insert(
                AppLogEntity(
                    level = "INFO",
                    source = "PERMISSIONS",
                    message = "تم حفظ إذن الوصول الدائم لمجلد SAF ($label) المتوافق مع الذاكرة الداخلية و SD و USB OTG"
                )
            )
            Result.success(label)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams an arbitrarily large directory tree (Internal Storage / SD Card / USB OTG) selected via SAF
     * into the strictly isolated workspace [targetDir] inside [siteRoot], enforcing Path Traversal checks.
     */
    suspend fun importSafTreeIntoWorkspace(
        siteRoot: File,
        targetDir: File,
        treeUri: Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext Result.failure(IllegalStateException("تعذر فتح المجلد عبر Storage Access Framework"))
        if (!rootDoc.exists() || !rootDoc.canRead()) {
            return@withContext Result.failure(SecurityException("لا توجد صلاحية قراءة للمجلد المحدد"))
        }

        persistSafTreePermission(treeUri)
        var copiedFiles = 0
        val buffer = ByteArray(64 * 1024)

        fun copyDocRecursive(doc: DocumentFile, currentTargetDir: File) {
            doc.listFiles().forEach { child ->
                val rawName = child.name ?: return@forEach
                if (rawName.contains("..") || rawName.contains('/') || rawName.contains('\\')) return@forEach
                val rel = File(currentTargetDir, rawName).relativeToOrSelf(siteRoot).path
                val safeTarget = SecurityHelper.resolveSafeChild(siteRoot, rel) ?: return@forEach

                if (child.isDirectory) {
                    safeTarget.mkdirs()
                    copyDocRecursive(child, safeTarget)
                } else if (child.isFile) {
                    safeTarget.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        FileOutputStream(safeTarget).use { output ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    copiedFiles++
                }
            }
        }

        return@withContext try {
            copyDocRecursive(rootDoc, targetDir)
            logDao.insert(
                AppLogEntity(
                    level = "INFO",
                    source = "SAF_STORAGE",
                    message = "تم استيراد $copiedFiles ملف من مجلد خارجي (${rootDoc.name ?: "SAF"}) عبر Storage Access Framework"
                )
            )
            Result.success(copiedFiles)
        } catch (e: Exception) {
            logDao.insert(
                AppLogEntity(
                    level = "ERROR",
                    source = "SAF_STORAGE",
                    message = "فشل استيراد مجلد SAF: ${e.message}"
                )
            )
            Result.failure(e)
        }
    }

    suspend fun copyLargeUriStreaming(sourceUri: Uri, destinationFile: File): Result<Long> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                destinationFile.parentFile?.mkdirs()
                var total = 0L
                val buf = ByteArray(64 * 1024)
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destinationFile).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                } ?: return@withContext Result.failure(IllegalStateException("تعذر فتح تدفق القراءة للملف"))
                Result.success(total)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun queryUriDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "").name
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "document"
    }
}
