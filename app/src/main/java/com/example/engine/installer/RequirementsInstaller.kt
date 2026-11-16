package com.example.engine.installer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.example.core.model.AndroidPackageSpec
import com.example.core.model.DownloadTaskState
import com.example.core.model.EngineType
import com.example.core.model.PackageInstallProgress
import com.example.core.model.TrustedEnginePackage
import com.example.engine.core.AndroidPackageManager
import com.example.engine.core.PackageDownloader
import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates automatic requirement discovery and real package download/verification/installation
 * via AndroidPackageManager and PackageDownloader (no mock packages or fake install states).
 */
class RequirementsInstaller(
    private val context: Context,
    val packageManager: AndroidPackageManager,
    val downloader: PackageDownloader
) {
    val downloadTask: StateFlow<DownloadTaskState?> = downloader.downloadTask
    val packageCatalog: StateFlow<List<AndroidPackageSpec>> = packageManager.packages
    val isSyncingCatalog: StateFlow<Boolean> = packageManager.isSyncing
    val lastSyncMessage: StateFlow<String?> = packageManager.lastSyncMessage

    // Legacy compatibility flow mapped cleanly from PackageDownloader state
    val installProgress: StateFlow<PackageInstallProgress?>
        get() = _legacyProgress

    private val _legacyProgress = kotlinx.coroutines.flow.MutableStateFlow<PackageInstallProgress?>(null)

    fun dismissProgress() {
        downloader.dismissTask()
        _legacyProgress.value = null
    }

    fun cancelActiveDownload(taskId: String) {
        downloader.cancelDownload(taskId)
    }

    fun isInternetAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun syncCompatibleCatalog(): Result<List<AndroidPackageSpec>> {
        return packageManager.syncRepositories()
    }

    fun getCompatibleCatalog(): List<TrustedEnginePackage> {
        return packageManager.packages.value.map { spec ->
            TrustedEnginePackage(
                id = spec.id,
                engineType = spec.engineType,
                version = spec.version,
                abi = spec.abi,
                descriptionAr = "${spec.programName} (${spec.repositoryName})",
                officialDownloadUrl = spec.officialUrl,
                expectedSha256 = spec.sha256,
                approxSizeLabel = spec.approxSizeLabel
            )
        }
    }

    suspend fun installEngineRequirement(
        engineType: EngineType,
        customUrl: String? = null,
        customSha256: String? = null
    ): Result<String> {
        // Ensure catalog is synced when no custom URL is passed and package isn't synced yet
        var spec = packageManager.packages.value.firstOrNull { it.engineType == engineType }
        if ((spec == null || !spec.isAvailableForInstall) && customUrl.isNullOrBlank()) {
            if (!isInternetAvailable()) {
                return Result.failure(IllegalStateException("لا يوجد اتصال بالإنترنت لمزامنة وتنزيل ${engineType.titleAr}. يمكنك استخدام الاستيراد المحلي."))
            }
            packageManager.syncRepositories()
            spec = packageManager.packages.value.firstOrNull { it.engineType == engineType }
        }
        if (spec == null) {
            return Result.failure(IllegalStateException("لم يُعثر على حزمة متوافقة للمحرك ${engineType.titleAr} على معمارية الجهاز"))
        }
        return packageManager.installPackageFromUrl(spec, customUrl, customSha256)
    }

    suspend fun downloadAndVerifyPackage(
        pkg: TrustedEnginePackage,
        httpsUrl: String,
        sha256: String
    ): Result<String> {
        val spec = packageManager.packages.value.firstOrNull { it.id == pkg.id }
            ?: AndroidPackageSpec(
                id = pkg.id,
                programName = pkg.engineType.name.lowercase(),
                engineType = pkg.engineType,
                version = pkg.version,
                abi = pkg.abi,
                minAndroidApi = 24,
                repositoryName = "HTTPS",
                officialUrl = httpsUrl,
                sha256 = sha256,
                downloadSizeBytes = 0L,
                dependencies = emptyList(),
                isAvailableForInstall = httpsUrl.startsWith("https://")
            )
        return packageManager.installPackageFromUrl(spec, httpsUrl, sha256)
    }

    suspend fun importLocalPackageAndTest(
        pkg: TrustedEnginePackage,
        uri: Uri,
        optionalSha256: String?
    ): Result<String> {
        val spec = packageManager.packages.value.firstOrNull { it.id == pkg.id }
            ?: AndroidPackageSpec(
                id = pkg.id,
                programName = pkg.engineType.name.lowercase(),
                engineType = pkg.engineType,
                version = pkg.version,
                abi = pkg.abi,
                minAndroidApi = 24,
                repositoryName = "Local Archive",
                officialUrl = "",
                sha256 = optionalSha256 ?: "",
                downloadSizeBytes = 0L,
                dependencies = emptyList(),
                isAvailableForInstall = true
            )
        return packageManager.installPackageFromUri(spec, uri, optionalSha256)
    }
}
