package com.example.core.compat

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StatFs
import com.example.core.model.EngineType
import java.io.File
import java.util.Locale

enum class StorageStrategy(val labelAr: String) {
    LEGACY_API_23_28("تخزين مباشر تقليدي (Android 6–9) + SAF"),
    SCOPED_TRANSITION_API_29("تخزين معزول + طلب Legacy Storage الانتقالي (Android 10)"),
    SCOPED_SAF_API_30_PLUS("Scoped Storage + SAF / USB OTG (Android 11–17+)")
}

enum class FgsStrategy(val labelAr: String) {
    BACKGROUND_SERVICE_API_23_25("خدمة خلفية مباشرة (API 23–25)"),
    FOREGROUND_SERVICE_API_26_28("خدمة أمامية قياسية عبر Notification Channel (API 26–28)"),
    FOREGROUND_DATA_SYNC_API_29_33("خدمة أمامية dataSync (API 29–33)"),
    STRICT_TYPED_FGS_API_34_PLUS("خدمة أمامية dataSync إلزامية النوع + إذن الإشعارات (API 34–36+)")
}

enum class ExecutionBackend(val labelAr: String) {
    IN_PROCESS_JVM("مدمج داخل التطبيق (JVM / Java Socket / SQLite)"),
    BUNDLED_NATIVE_LIB("مكتبة أصلية مجمعة في nativeLibraryDir (APK jniLibs)"),
    LEGACY_APP_DATA_EXEC("تشغيل محلي داخل مجلد البيانات (API <= 28 فقط)"),
    BLOCKED_WX_SELINUX("محظور بواسطة W^X / SELinux app_data_file (API 29+)"),
    UNSUPPORTED_ABI("معمارية CPU غير متوافقة")
}

data class DeviceCompatibilityProfile(
    val androidRelease: String,
    val apiLevel: Int,
    val targetSdk: Int,
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val totalRamMb: Long,
    val availRamMb: Long,
    val isLowRamDevice: Boolean,
    val freeStorageBytes: Long,
    val canExecuteInAppData: Boolean,
    val extractNativeLibsEnabled: Boolean,
    val nativeLibraryDir: String,
    val storageStrategy: StorageStrategy,
    val fgsStrategy: FgsStrategy
)

data class RuntimeStrategyDecision(
    val engineType: EngineType,
    val backend: ExecutionBackend,
    val isExecutableOnDevice: Boolean,
    val executablePath: String?,
    val reasonAr: String
)

class AndroidCompatibilityLayer(
    private val context: Context,
    private val apiLevelProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val abisProvider: () -> List<String> = { Build.SUPPORTED_ABIS.toList().ifEmpty { listOf("arm64-v8a") } }
) {

    fun collectDeviceProfile(): DeviceCompatibilityProfile {
        val api = apiLevelProvider()
        val appInfo = context.applicationInfo
        val targetSdk = appInfo?.targetSdkVersion ?: Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val extractNativeLibs = ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0
        val nativeDir = appInfo?.nativeLibraryDir ?: ""
        val abis = abisProvider()
        val primaryAbi = abis.firstOrNull() ?: "arm64-v8a"

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRam = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
        val availRam = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L)
        val lowRam = am?.isLowRamDevice ?: (totalRam in 1..1536)

        val freeBytes = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }

        // Android 10 (API 29+) with targetSdk >= 29 strictly enforces W^X and SELinux neverallow on app_data_file.
        val canExecAppData = api < Build.VERSION_CODES.Q || targetSdk < Build.VERSION_CODES.Q

        val storageStrategy = when {
            api <= Build.VERSION_CODES.P -> StorageStrategy.LEGACY_API_23_28
            api == Build.VERSION_CODES.Q -> StorageStrategy.SCOPED_TRANSITION_API_29
            else -> StorageStrategy.SCOPED_SAF_API_30_PLUS
        }

        val fgsStrategy = when {
            api < Build.VERSION_CODES.O -> FgsStrategy.BACKGROUND_SERVICE_API_23_25
            api < Build.VERSION_CODES.Q -> FgsStrategy.FOREGROUND_SERVICE_API_26_28
            api < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> FgsStrategy.FOREGROUND_DATA_SYNC_API_29_33
            else -> FgsStrategy.STRICT_TYPED_FGS_API_34_PLUS
        }

        return DeviceCompatibilityProfile(
            androidRelease = Build.VERSION.RELEASE ?: api.toString(),
            apiLevel = api,
            targetSdk = targetSdk,
            primaryAbi = primaryAbi,
            supportedAbis = abis,
            totalRamMb = totalRam,
            availRamMb = availRam,
            isLowRamDevice = lowRam,
            freeStorageBytes = freeBytes,
            canExecuteInAppData = canExecAppData,
            extractNativeLibsEnabled = extractNativeLibs,
            nativeLibraryDir = nativeDir,
            storageStrategy = storageStrategy,
            fgsStrategy = fgsStrategy
        )
    }

    /**
     * Resolves Android-compliant execution backend for any engine without bypassing SELinux or faking state.
     */
    fun evaluateEngineExecution(
        engineType: EngineType,
        bundledNativeBinary: File? = null,
        downloadedAppDataBinary: File? = null
    ): RuntimeStrategyDecision {
        val profile = collectDeviceProfile()

        if (engineType == EngineType.HTTP_SERVER || engineType == EngineType.SQLITE) {
            return RuntimeStrategyDecision(
                engineType = engineType,
                backend = ExecutionBackend.IN_PROCESS_JVM,
                isExecutableOnDevice = true,
                executablePath = "in-process",
                reasonAr = "يعمل مباشرة داخل بيئة التطبيق المتوافقة مع API ${profile.apiLevel} دون قيود W^X."
            )
        }

        if (engineType == EngineType.PHPMYADMIN) {
            return RuntimeStrategyDecision(
                engineType = engineType,
                backend = ExecutionBackend.IN_PROCESS_JVM,
                isExecutableOnDevice = bundledNativeBinary != null || profile.canExecuteInAppData,
                executablePath = bundledNativeBinary?.absolutePath,
                reasonAr = "تطبيق ويب PHP يتطلب محرك PHP تنفيذي متوافق مع ${profile.primaryAbi} بالإضافة لخادم MySQL/MariaDB."
            )
        }

        val abiSupported = profile.supportedAbis.any {
            val lower = it.lowercase(Locale.ROOT)
            lower == "arm64-v8a" || lower == "armeabi-v7a" || lower == "x86_64" || lower == "x86"
        }
        if (!abiSupported) {
            return RuntimeStrategyDecision(
                engineType = engineType,
                backend = ExecutionBackend.UNSUPPORTED_ABI,
                isExecutableOnDevice = false,
                executablePath = null,
                reasonAr = "معمارية المعالج ${profile.primaryAbi} غير مدعومة."
            )
        }

        if (bundledNativeBinary != null && bundledNativeBinary.exists() && bundledNativeBinary.canExecute()) {
            return RuntimeStrategyDecision(
                engineType = engineType,
                backend = ExecutionBackend.BUNDLED_NATIVE_LIB,
                isExecutableOnDevice = true,
                executablePath = bundledNativeBinary.absolutePath,
                reasonAr = "ثنائي متوافق مُدرج في nativeLibraryDir (${bundledNativeBinary.name}) ومسموح بتشغيله على Android ${profile.androidRelease}."
            )
        }

        if (!profile.canExecuteInAppData) {
            val pathNote = downloadedAppDataBinary?.let { " (تم العثور على ${it.name} داخل مجلد البيانات لكنه محظور)" } ?: ""
            return RuntimeStrategyDecision(
                engineType = engineType,
                backend = ExecutionBackend.BLOCKED_WX_SELINUX,
                isExecutableOnDevice = false,
                executablePath = null,
                reasonAr = "Android ${profile.androidRelease} (API ${profile.apiLevel}, targetSdk=${profile.targetSdk}) يفرض سياسة W^X و SELinux التي تمنع execve للملفات المحمّلة داخل app_data_file (error=13 Permission denied)$pathNote. يتطلب مكتبة lib*.so مجمّعة في jniLibs/$nativeAbiHint."
            )
        }

        if (downloadedAppDataBinary != null && downloadedAppDataBinary.exists() && downloadedAppDataBinary.canExecute()) {
            return RuntimeStrategyDecision(
                engineType = engineType,
                backend = ExecutionBackend.LEGACY_APP_DATA_EXEC,
                isExecutableOnDevice = true,
                executablePath = downloadedAppDataBinary.absolutePath,
                reasonAr = "يعمل عبر التوافقية القديمة لنظام Android ${profile.androidRelease} (API ${profile.apiLevel} < 29)."
            )
        }

        return RuntimeStrategyDecision(
            engineType = engineType,
            backend = if (profile.canExecuteInAppData) ExecutionBackend.LEGACY_APP_DATA_EXEC else ExecutionBackend.BLOCKED_WX_SELINUX,
            isExecutableOnDevice = false,
            executablePath = null,
            reasonAr = "غير مثبت في nativeLibraryDir أو مسارات النظام المتوافقة لمعمارية ${profile.primaryAbi}."
        )
    }

    private val nativeAbiHint: String
        get() = abisProvider().firstOrNull() ?: "arm64-v8a"
}
