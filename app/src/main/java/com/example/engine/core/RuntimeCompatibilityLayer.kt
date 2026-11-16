package com.example.engine.core

import android.content.Context
import android.system.Os
import com.example.core.compat.AndroidCompatibilityLayer
import com.example.core.compat.ExecutionBackend
import com.example.core.model.AndroidPackageSpec
import com.example.core.model.EngineType
import com.example.core.model.PhpCheckStep
import com.example.core.model.PhpVerificationReport
import java.io.File
import java.util.Locale

data class EightPointCheckItem(
    val index: Int,
    val title: String,
    val passed: Boolean,
    val detail: String
)

data class EngineEightPointReport(
    val engineType: EngineType,
    val binaryPath: String?,
    val apiLevel: Int,
    val primaryAbi: String,
    val backend: ExecutionBackend,
    val checks: List<EightPointCheckItem>,
    val isExecutionPermitted: Boolean,
    val summaryAr: String
)

data class PackageInstallEligibility(
    val allowed: Boolean,
    val isPureInterpretedOrArchive: Boolean,
    val reasonAr: String
)

class RuntimeCompatibilityLayer(
    private val context: Context,
    private val compatibilityLayer: AndroidCompatibilityLayer
) {

    fun nativeLibNamesForEngine(engineType: EngineType): List<String> = when (engineType) {
        EngineType.PHP -> listOf("libphp_cgi.so", "libphp.so")
        EngineType.PYTHON -> listOf("libpython3.so", "libpython.so")
        EngineType.NODEJS -> listOf("libnode.so", "libnodejs.so")
        EngineType.MYSQL_MARIADB -> listOf("libmariadbd.so", "libmysqld.so")
        EngineType.POSTGRESQL -> listOf("libpostgres.so")
        else -> emptyList()
    }

    fun bundledBinaryNames(engineType: EngineType): List<String> = when (engineType) {
        EngineType.PHP -> listOf("libphp_cgi.so", "libphp.so", "php-cgi", "php")
        EngineType.PYTHON -> listOf("libpython3.so", "libpython.so", "python3", "python")
        EngineType.NODEJS -> listOf("libnode.so", "libnodejs.so", "node", "nodejs")
        EngineType.MYSQL_MARIADB -> listOf("libmariadbd.so", "libmysqld.so", "mariadbd", "mysqld")
        EngineType.POSTGRESQL -> listOf("libpostgres.so", "postgres")
        else -> emptyList()
    }

    fun subDirForEngine(engineType: EngineType): String = when (engineType) {
        EngineType.PHP -> "php"
        EngineType.PYTHON -> "python"
        EngineType.NODEJS -> "nodejs"
        EngineType.MYSQL_MARIADB -> "mariadb"
        EngineType.POSTGRESQL -> "postgresql"
        EngineType.PHPMYADMIN -> "phpmyadmin"
        else -> engineType.name.lowercase(Locale.ROOT)
    }

    fun findBundledNativeBinary(engineType: EngineType): File? {
        val nativeDir = context.applicationInfo?.nativeLibraryDir?.let { File(it) } ?: return null
        if (!nativeDir.exists() || !nativeDir.isDirectory) return null
        for (name in nativeLibNamesForEngine(engineType)) {
            val f = File(nativeDir, name)
            if (f.exists() && f.isFile && f.canExecute()) return f
        }
        return null
    }

    fun findAppDataBinaryAnyMode(engineType: EngineType, subDirOverride: String? = null): File? {
        val sub = subDirOverride ?: subDirForEngine(engineType)
        val runtimeBase = File(context.filesDir, "runtimes/$sub")
        val rawNames = when (engineType) {
            EngineType.PHP -> listOf("php-cgi", "php")
            EngineType.PYTHON -> listOf("python3", "python")
            EngineType.NODEJS -> listOf("node", "nodejs")
            EngineType.MYSQL_MARIADB -> listOf("mariadbd", "mysqld")
            EngineType.POSTGRESQL -> listOf("postgres", "pg_ctl")
            else -> emptyList()
        }
        val dirs = buildList {
            add(File(runtimeBase, "bin"))
            add(File(runtimeBase, "usr/bin"))
            add(runtimeBase)
            if (runtimeBase.exists() && runtimeBase.isDirectory) {
                runtimeBase.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.forEach { v ->
                    add(File(v, "bin"))
                    add(File(v, "usr/bin"))
                    add(v)
                }
            }
        }
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            for (name in rawNames) {
                val f = File(dir, name)
                if (f.exists() && f.isFile) return f
            }
        }
        return null
    }

    /**
     * Evaluates whether a package archive (remote APT/Termux ELF archive or pure-PHP/pure-Python archive)
     * is genuinely installable AND executable on this Android device before downloading it.
     */
    fun evaluatePackageInstallEligibility(spec: AndroidPackageSpec): PackageInstallEligibility {
        val profile = compatibilityLayer.collectDeviceProfile()

        if (spec.engineType == EngineType.PHPMYADMIN) {
            return PackageInstallEligibility(
                allowed = true,
                isPureInterpretedOrArchive = true,
                reasonAr = "أرشيف PHP نقي مستقل عن المعمارية (يعمل فور توفر محرك PHP + MySQL)."
            )
        }

        if (profile.freeStorageBytes in 1L until 25L * 1024L * 1024L) {
            return PackageInstallEligibility(
                allowed = false,
                isPureInterpretedOrArchive = false,
                reasonAr = "المساحة الحرة غير كافية لتثبيت ${spec.programName}."
            )
        }

        val bundled = findBundledNativeBinary(spec.engineType)
        if (bundled != null) {
            return PackageInstallEligibility(
                allowed = true,
                isPureInterpretedOrArchive = false,
                reasonAr = "متوافق: تتوفر نواة تنفيذية مجمعة في nativeLibraryDir (${bundled.name})."
            )
        }

        if (!profile.canExecuteInAppData) {
            val isTermuxArchive = spec.repositoryName.contains("Termux", ignoreCase = true) ||
                spec.officialUrl.endsWith(".deb", ignoreCase = true)
            val detail = if (isTermuxArchive) {
                "حظر التنفيذ (W^X / SELinux on Android ${profile.androidRelease} API ${profile.apiLevel}): مستودعات Termux .deb تضع ملفات ELF في app_data_file حيث يمنع النواة execve (error=13 Permission denied). يتطلب ثنائيات lib*.so داخل nativeLibraryDir (APK jniLibs)."
            } else {
                "Android ${profile.androidRelease} (API ${profile.apiLevel}) يمنع تشغيل ملفات تنفيذية محمّلة داخل filesDir/runtimes (سياسة W^X و SELinux)."
            }
            return PackageInstallEligibility(
                allowed = false,
                isPureInterpretedOrArchive = false,
                reasonAr = detail
            )
        }

        return PackageInstallEligibility(
            allowed = true,
            isPureInterpretedOrArchive = false,
            reasonAr = "متوافق مع معمارية ${profile.primaryAbi} على Android ${profile.androidRelease} (API ${profile.apiLevel})."
        )
    }

    /**
     * Performs complete 8-point runtime diagnostics required for debugging execution errors (including error=13):
     * 1. Executable bits (`0755`)
     * 2. Parent directory search (`x`) permissions
     * 3. CPU ABI match (`arm64-v8a` / `armeabi-v7a` / `x86_64`)
     * 4. Bionic libc vs Linux glibc check
     * 5. ELF Interpreter (`PT_INTERP`) & `DT_NEEDED` shared libs
     * 6. SELinux context (`app_data_file` vs `apk_data_file`)
     * 7. Android 10+ W^X (`targetSdk >= 29` noexec data dir)
     * 8. `ProcessBuilder` / `nativeLibraryDir` path resolution
     */
    fun runEightPointDiagnostics(engineType: EngineType): EngineEightPointReport {
        val profile = compatibilityLayer.collectDeviceProfile()
        val bundled = findBundledNativeBinary(engineType)
        val appDataBin = findAppDataBinaryAnyMode(engineType)
        val activeTarget = bundled ?: appDataBin
        val decision = compatibilityLayer.evaluateEngineExecution(engineType, bundled, appDataBin)

        if (engineType == EngineType.HTTP_SERVER || engineType == EngineType.SQLITE) {
            val checks = listOf(
                EightPointCheckItem(1, "محرك مدمج داخل التطبيق", true, "يعمل داخل عملية التطبيق (JVM / SDK)"),
                EightPointCheckItem(2, "سياسات SELinux و W^X", true, "لا يعتمد على execve خارجي داخل filesDir"),
                EightPointCheckItem(3, "توافق API", true, "متوافق مع API ${profile.apiLevel} (${profile.androidRelease})")
            )
            return EngineEightPointReport(
                engineType = engineType,
                binaryPath = "in-process",
                apiLevel = profile.apiLevel,
                primaryAbi = profile.primaryAbi,
                backend = ExecutionBackend.IN_PROCESS_JVM,
                checks = checks,
                isExecutionPermitted = true,
                summaryAr = "محرك مدمج متوافق بالكامل دون ملفات ثنائية خارجية."
            )
        }

        // 1. Executable permission bits
        val step1 = if (activeTarget != null) {
            val modeOct = runCatching {
                String.format(Locale.US, "%03o", Os.stat(activeTarget.absolutePath).st_mode and 0b111_111_111)
            }.getOrDefault("---")
            EightPointCheckItem(
                1,
                "1. صلاحيات الملف التنفيذي (chmod)",
                activeTarget.canExecute(),
                "${activeTarget.name}: mode=$modeOct, canExecute=${activeTarget.canExecute()}"
            )
        } else {
            EightPointCheckItem(1, "1. صلاحيات الملف التنفيذي (chmod)", false, "لا يوجد ملف ثنائي مثبت لفحصه")
        }

        // 2. Parent directories permission
        val step2 = if (activeTarget != null) {
            val parent = activeTarget.parentFile
            val parentOk = parent != null && parent.canExecute() && parent.canRead()
            EightPointCheckItem(
                2,
                "2. صلاحيات المجلدات الأب",
                parentOk,
                "المجلد: ${parent?.name ?: "-"} (canRead=${parent?.canRead()}, canTraverse=${parent?.canExecute()})"
            )
        } else {
            EightPointCheckItem(2, "2. صلاحيات المجلدات الأب", true, "مجلد runtimes يملك صلاحيات مالك التطبيق (0700)")
        }

        // 3, 4, 5: ELF header inspection
        val libSearchDirs = buildList {
            if (profile.nativeLibraryDir.isNotBlank()) add(File(profile.nativeLibraryDir))
            activeTarget?.parentFile?.parentFile?.let {
                add(File(it, "lib"))
                add(File(it, "usr/lib"))
            }
        }
        val elfInfo = activeTarget?.let { ElfBinaryInspector.inspect(it, profile.supportedAbis, libSearchDirs) }

        val step3 = if (elfInfo != null && elfInfo.isValidElf) {
            val abiOk = profile.supportedAbis.any { it.equals(elfInfo.abiName, ignoreCase = true) }
            EightPointCheckItem(3, "3. توافق معمارية ${profile.primaryAbi}", abiOk, "${elfInfo.machineArch} -> ${elfInfo.abiName}")
        } else {
            EightPointCheckItem(
                3,
                "3. توافق معمارية ${profile.primaryAbi}",
                bundled != null,
                elfInfo?.diagnosticSummary ?: "الجهاز يتطلب ثنائيات ${profile.primaryAbi} مجمعة في APK jniLibs"
            )
        }

        val step4 = if (elfInfo != null && elfInfo.isValidElf) {
            EightPointCheckItem(
                4,
                "4. مكتبة C القياسية (Bionic مقابل glibc)",
                elfInfo.isBionicLinker && !elfInfo.isGlibcLinker,
                if (elfInfo.isGlibcLinker) "خطأ: الملف مبني بـ Linux glibc (${elfInfo.neededLibraries.firstOrNull { it.contains("libc") }})"
                else "Android Bionic libc متوافق (${elfInfo.neededLibraries.take(3).joinToString()})"
            )
        } else {
            EightPointCheckItem(4, "4. مكتبة C القياسية (Bionic مقابل glibc)", false, "يتطلب حزمة مبنية على Android NDK / Bionic (ممنوع glibc)")
        }

        val step5 = if (elfInfo != null && elfInfo.isValidElf) {
            val ok = elfInfo.missingLibraries.isEmpty() &&
                (elfInfo.interpreterPath == null || elfInfo.interpreterPath.startsWith("/system/bin/linker"))
            val termuxRunpath = elfInfo.runpathEntries.any { it.contains("com.termux") }
            val runpathMsg = if (termuxRunpath) " • تحذير: DT_RUNPATH يشير لمسار com.termux غير المتاح للتطبيقات المعزولة!" else ""
            EightPointCheckItem(
                5,
                "5. ELF Interpreter والمكتبات المشتركة",
                ok && !termuxRunpath,
                "PT_INTERP=${elfInfo.interpreterPath ?: "none"} • مفقود=${elfInfo.missingLibraries.size}${if (elfInfo.missingLibraries.isNotEmpty()) " [${elfInfo.missingLibraries.take(4).joinToString()}]" else ""}$runpathMsg"
            )
        } else {
            EightPointCheckItem(5, "5. ELF Interpreter والمكتبات المشتركة", false, "المطلوب: PT_INTERP=/system/bin/linker64 وحزم .so بدون مسار Termux")
        }

        // 6. SELinux enforcement
        val inAppData = activeTarget?.absolutePath?.startsWith(context.filesDir.absolutePath) == true
        val selinuxPass = bundled != null || profile.canExecuteInAppData
        val selinuxDetail = when {
            bundled != null -> "يقع داخل nativeLibraryDir (تسمية apk_data_file / system_lib_file مسموح بتشغيلها)"
            !profile.canExecuteInAppData && inAppData ->
                "مرفوض: سياق app_data_file (getFilesDir) محظور من execute في نطاق untrusted_app على API ${profile.apiLevel}"
            !profile.canExecuteInAppData ->
                "سياسة SELinux على API ${profile.apiLevel} تسمح بالتنفيذ فقط من nativeLibraryDir داخل APK (extractNativeLibs=${profile.extractNativeLibsEnabled})"
            else -> "مسموح على API ${profile.apiLevel} (< 29)"
        }
        val step6 = EightPointCheckItem(6, "6. قيود SELinux (untrusted_app)", selinuxPass, selinuxDetail)

        // 7. Android 10+ W^X policy
        val wxPass = bundled != null || profile.canExecuteInAppData
        val wxDetail = if (profile.canExecuteInAppData) {
            "API ${profile.apiLevel} لا يفرض حظر W^X الصارم على app_data (أقل من Android 10)"
        } else if (bundled != null) {
            "يعمل من مجلد القراءة فقط nativeLibraryDir المتوافق تماماً مع W^X"
        } else {
            "سياسة W^X (Android 10+ / targetSdk=${profile.targetSdk}): المجلدات القابلة للكتابة غير قابلة للتنفيذ — سبب خطأ error=13 Permission denied"
        }
        val step7 = EightPointCheckItem(7, "7. سياسة W^X في Android 10+", wxPass, wxDetail)

        // 8. ProcessBuilder & path resolution
        val step8Pass = decision.isExecutableOnDevice
        val step8Detail = if (step8Pass) {
            "مسار ProcessBuilder المعتمد: ${decision.executablePath} (LD_LIBRARY_PATH=${profile.nativeLibraryDir})"
        } else {
            "لا يُشغَّل عبر ProcessBuilder من filesDir/runtimes؛ يجب استخدام lib*.so في nativeLibraryDir أو التثبيت ضمن الحزمة."
        }
        val step8 = EightPointCheckItem(8, "8. آلية ProcessBuilder ومسارات التشغيل", step8Pass, step8Detail)

        val checks = listOf(step1, step2, step3, step4, step5, step6, step7, step8)
        return EngineEightPointReport(
            engineType = engineType,
            binaryPath = activeTarget?.absolutePath,
            apiLevel = profile.apiLevel,
            primaryAbi = profile.primaryAbi,
            backend = decision.backend,
            checks = checks,
            isExecutionPermitted = decision.isExecutableOnDevice,
            summaryAr = decision.reasonAr
        )
    }

    /**
     * Inspects Composer / pip availability strictly and honestly (supports bundled native PHP/Python or API <= 28 runtimes).
     */
    fun inspectToolingStatus(): Pair<String, String> {
        val phpDecision = compatibilityLayer.evaluateEngineExecution(
            EngineType.PHP,
            bundledNativeBinary = findBundledNativeBinary(EngineType.PHP),
            downloadedAppDataBinary = findAppDataBinaryAnyMode(EngineType.PHP)
        )
        val composerPhar = File(context.filesDir, "runtimes/php/composer.phar").exists() ||
            File(context.filesDir, "runtimes/php/bin/composer").exists()
        val composerStatus = when {
            phpDecision.isExecutableOnDevice && composerPhar -> "Composer جاهز للتنفيذ مع PHP (${phpDecision.backend.labelAr})"
            phpDecision.isExecutableOnDevice -> "PHP قابل للتشغيل؛ يمكن تحليل composer.json أو استيراد vendor/"
            else -> "تحليل composer.json متاح بالكامل، أما تنفيذ composer CLI فيتطلب نواة PHP متوافقة مع W^X في nativeLibraryDir."
        }

        val pyDecision = compatibilityLayer.evaluateEngineExecution(
            EngineType.PYTHON,
            bundledNativeBinary = findBundledNativeBinary(EngineType.PYTHON),
            downloadedAppDataBinary = findAppDataBinaryAnyMode(EngineType.PYTHON)
        )
        val pipStatus = if (pyDecision.isExecutableOnDevice) {
            "pip3 قابل للتشغيل عبر Python (${pyDecision.backend.labelAr})"
        } else {
            "فحص PyPI وتجهيز حزم Pure-Python (.whl) متاح بالكامل؛ أما python3 -m pip التنفيذي فيتطلب نواة libpython3.so في nativeLibraryDir."
        }
        return composerStatus to pipStatus
    }

    /**
     * Generates a 7-step PHP self-test failure report when W^X or missing binary blocks PHP execution.
     */
    fun buildBlockedPhpVerificationReport(phpVersion: String, reason: String): PhpVerificationReport {
        val abi = compatibilityLayer.collectDeviceProfile().primaryAbi
        val titles = listOf(
            "1. فحص ثنائي PHP وترويسة ELF/W^X",
            "2. تشغيل php -v وقراءة الإصدار",
            "3. جاهزية php-cgi أو خادم التطوير المدمج",
            "4. فحص الإضافات المحملة (php -m)",
            "5. ربط PHP مع خادم HTTP المحلي",
            "6. تنفيذ ملف index.php حقيقي",
            "7. التحقق من الاستجابة في المتصفح المحلي"
        )
        val steps = titles.mapIndexed { idx, title ->
            PhpCheckStep(
                stepNumber = idx + 1,
                title = title,
                probeTarget = "libphp_cgi.so / php-cgi",
                passed = false,
                detail = reason
            )
        }
        return PhpVerificationReport(
            phpVersion = phpVersion,
            abi = abi,
            steps = steps,
            allPassed = false
        )
    }
}
