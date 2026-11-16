package com.example.engine.runtime

import android.content.Context
import android.os.Build
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.RuntimeConfigDao
import com.example.core.database.RuntimeConfigEntity
import com.example.core.database.SiteEntity
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.core.model.PythonCheckStep
import com.example.core.model.PythonPackageInfo
import com.example.core.model.PythonVerificationReport
import com.example.engine.core.ArchiveExtractor
import com.example.engine.core.ManagedServiceSpec
import com.example.engine.core.PackageDownloader
import com.example.engine.core.ProcessProbe
import com.example.engine.core.RuntimeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Locale

class PythonRuntimeManager(
    private val context: Context,
    private val runtimeConfigDao: RuntimeConfigDao,
    private val runtimeEngine: RuntimeEngine,
    private val downloader: PackageDownloader,
    private val logDao: AppLogDao
) {
    val supportedVersions = listOf("3.13", "3.12", "3.11", "3.10", "3.9")

    private val _installedPackages = MutableStateFlow<List<PythonPackageInfo>>(emptyList())
    val installedPackages: StateFlow<List<PythonPackageInfo>> = _installedPackages.asStateFlow()

    private val _lastVerificationReport = MutableStateFlow<PythonVerificationReport?>(null)
    val lastVerificationReport: StateFlow<PythonVerificationReport?> = _lastVerificationReport.asStateFlow()

    private val _lastPipOutput = MutableStateFlow<String>("")
    val lastPipOutput: StateFlow<String> = _lastPipOutput.asStateFlow()

    suspend fun ensureDefaultConfigs() {
        supportedVersions.forEach { ver ->
            val id = "python_$ver"
            val reqFile = getRequirementsFile(ver)
            reqFile.parentFile?.mkdirs()
            if (!reqFile.exists()) {
                reqFile.writeText("# Abotalal Hosting Python $ver Requirements\nflask>=3.0.0\ndjango>=5.0\nfastapi>=0.115.0\nuvicorn>=0.30.0\n")
            }
            if (runtimeConfigDao.getById(id) == null) {
                runtimeConfigDao.upsert(
                    RuntimeConfigEntity(
                        id = id,
                        engineType = EngineType.PYTHON.name,
                        version = ver,
                        installPath = runtimeEngine.getRuntimeDir(EngineType.PYTHON, ver).absolutePath,
                        configText = reqFile.readText(),
                        extensionsCsv = "",
                        port = 8000,
                        isInstalled = findPythonBinary(ver) != null
                    )
                )
            }
        }
    }

    fun getRequirementsFile(version: String): File =
        File(runtimeEngine.getRuntimeDir(EngineType.PYTHON, version), "requirements.txt")

    fun findPythonBinary(version: String): File? =
        ProcessProbe.findBinary(context, listOf("python3", "python"), "python/$version")
            ?: ProcessProbe.findBinary(context, listOf("python3", "python"), "python")

    fun isArm64Compatible(): Boolean {
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.ROOT) }
        return abis.any { it == "arm64-v8a" || it == "aarch64" || it == "x86_64" }
    }

    suspend fun probeStatus(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        var foundVersion: String? = null
        var foundBinary: File? = null
        var details = ""
        var verified = false

        for (ver in supportedVersions) {
            val bin = findPythonBinary(ver)
            if (bin != null) {
                val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, ver)
                val res = runtimeEngine.executeCommand(listOf(bin.absolutePath, "--version"), bin.parentFile ?: runtimeEngine.runtimesRoot, env, 6L)
                foundBinary = bin
                val line = res.combinedOutput.lineSequence().firstOrNull().orEmpty()
                foundVersion = if (res.isSuccess && line.isNotBlank()) line else ver
                verified = res.isSuccess && (_lastVerificationReport.value?.allPassed ?: false)
                details = when {
                    res.isSuccess && verified -> "متحقق من Python بالكامل (اجتاز 7 اختبارات): $line"
                    res.isSuccess -> "مفسر Python متوفر ($line) — قم بتشغيل اختبار التحقق الكامل (pip / venv / Flask)"
                    else -> res.stderr.ifBlank { "فشل تشغيل مفسر Python" }
                }
                break
            }
        }

        val blockedAppData = if (foundBinary == null) {
            ProcessProbe.findBlockedAppDataBinary(context, listOf("python3", "python"), "python")
        } else null

        val anyServiceAlive = runtimeEngine.activeServices.value.values.any {
            it.spec.engineType == EngineType.PYTHON && it.isAlive
        }

        val status = when {
            anyServiceAlive -> EngineStatus.RUNNING
            foundBinary != null && !details.contains("فشل") && !details.contains("قيود") -> EngineStatus.STOPPED
            foundBinary != null || blockedAppData != null -> EngineStatus.ERROR
            else -> EngineStatus.NOT_INSTALLED
        }

        val finalDetails = when {
            details.isNotBlank() -> details
            blockedAppData != null ->
                "تم رصد ${blockedAppData.name} داخل filesDir، لكن Android API ${Build.VERSION.SDK_INT} (W^X/SELinux) يحظر تنفيذه (error=13). يتطلب libpython3.so في nativeLibraryDir."
            else ->
                "مفسر Python ARM64 غير مثبت في nativeLibraryDir لمعمارية $abi (Android API ${Build.VERSION.SDK_INT})."
        }

        return EngineInfo(
            type = EngineType.PYTHON,
            status = status,
            activeVersion = foundVersion ?: if (blockedAppData != null) "محظور (W^X/SELinux)" else "غير مثبت",
            availableVersions = supportedVersions,
            port = 8000,
            binaryPath = foundBinary?.absolutePath ?: blockedAppData?.absolutePath,
            architecture = abi,
            verificationDetails = finalDetails,
            requirementNotice = "يستهدف معمارية arm64-v8a ويدعم pip و venv وأطر Flask و Django و FastAPI مع فحص توافق C/C++ Wheels.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    /**
     * Queries PyPI JSON API to inspect actual Android ARM64 compatibility (pure-Python py3-none-any vs C/C++ native extensions).
     */
    suspend fun inspectPypiPackageCompatibility(packageName: String): Result<PythonPackageInfo> =
        withContext(Dispatchers.IO) {
            val cleanName = packageName.trim().lowercase(Locale.ROOT)
                .substringBefore("==").substringBefore(">=").substringBefore("<=").trim()
            if (cleanName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("يرجى إدخال اسم مكتبة Python صالح"))
            }
            val url = "https://pypi.org/pypi/$cleanName/json"
            val textRes = downloader.fetchText(url)
            val body = textRes.getOrElse {
                return@withContext Result.failure(IllegalStateException("تعذر الاستعلام من PyPI عن '$cleanName': ${it.message}"))
            }

            return@withContext try {
                val root = JSONObject(body)
                val info = root.getJSONObject("info")
                val version = info.optString("version")
                val summary = info.optString("summary")
                val reqArr = info.optJSONArray("requires_dist") ?: JSONArray()
                val deps = buildList {
                    for (i in 0 until reqArr.length()) {
                        val dep = reqArr.optString(i)
                        if (!dep.contains("extra ==")) add(dep.substringBefore(";").trim())
                    }
                }

                val urlsArr = root.optJSONArray("urls") ?: JSONArray()
                var pureOrAndroidWheelUrl = ""
                var pureOrAndroidSha = ""
                var wheelSize = 0L
                var isPureOrAndroid = false
                var hasNativeLinuxWheelsOnly = false

                for (i in 0 until urlsArr.length()) {
                    val u = urlsArr.optJSONObject(i) ?: continue
                    val pkgType = u.optString("packagetype")
                    val filename = u.optString("filename")
                    if (pkgType == "bdist_wheel") {
                        val isPure = filename.endsWith("-none-any.whl", ignoreCase = true)
                        val isAndroidArm64 = filename.contains("android", ignoreCase = true) &&
                            (filename.contains("arm64", true) || filename.contains("aarch64", true))
                        if (isPure || isAndroidArm64) {
                            isPureOrAndroid = true
                            pureOrAndroidWheelUrl = u.optString("url")
                            pureOrAndroidSha = u.optJSONObject("digests")?.optString("sha256") ?: ""
                            wheelSize = u.optLong("size", 0L)
                            break
                        } else {
                            hasNativeLinuxWheelsOnly = true
                        }
                    }
                }

                val abi = ProcessProbe.getDeviceAbi()
                val note = when {
                    isPureOrAndroid -> "متوافقة مع Android $abi (Pure-Python / Android Wheel)"
                    hasNativeLinuxWheelsOnly -> "تحذير: تحتوي على امتدادات C/C++ بدون عجلة متوافقة مع Android $abi (لا تُستخدم عجلات Linux x86/manylinux مباشرة)."
                    else -> "حزمة مصدرية (sdist .tar.gz) فقط — قد تتطلب مترجم C/C++ عند البناء على Android."
                }

                Result.success(
                    PythonPackageInfo(
                        name = cleanName,
                        version = version,
                        summary = summary,
                        isPurePythonWheel = isPureOrAndroid,
                        wheelUrl = pureOrAndroidWheelUrl,
                        sha256 = pureOrAndroidSha,
                        sizeBytes = wheelSize,
                        dependencies = deps.take(12),
                        compatibilityNote = note
                    )
                )
            } catch (e: Exception) {
                Result.failure(IllegalStateException("فشل تحليل استجابة PyPI لـ '$cleanName': ${e.message}"))
            }
        }

    /**
     * Real pip package installer:
     * 1) If native python3 binary executes on device, runs `python3 -m pip install` and verifies exit code.
     * 2) Also supports pure-Python wheel (.whl) download, SHA-256 verification, and extraction into isolated site-packages,
     *    while strictly refusing C/C++ packages lacking Android ARM64 wheels.
     */
    suspend fun pipInstallPackage(
        pythonVersion: String,
        packageSpec: String,
        site: SiteEntity? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanSpec = packageSpec.trim()
        if (cleanSpec.isBlank()) return@withContext Result.failure(IllegalArgumentException("يرجى تحديد اسم المكتبة"))

        val workDir = site?.let { File(it.rootFolder) } ?: runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion)
        val pyBin = findPythonBinary(pythonVersion)

        if (pyBin != null) {
            val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pythonVersion, site?.let { File(it.rootFolder) })
            val cmd = mutableListOf(pyBin.absolutePath, "-m", "pip", "install")
            val sitePkgDir = site?.let { File(it.rootFolder, "venv/lib/python$pythonVersion/site-packages") }
            if (sitePkgDir != null && sitePkgDir.exists()) {
                cmd.addAll(listOf("--target", sitePkgDir.absolutePath))
            }
            cmd.add(cleanSpec)

            val res = runtimeEngine.executeCommand(cmd, workDir, env, 120L)
            _lastPipOutput.value = res.combinedOutput.ifBlank { res.stderr }
            if (res.isSuccess) {
                logDao.insert(AppLogEntity(level = "INFO", source = "PIP", message = "تم تثبيت $cleanSpec عبر pip بنجاح"))
                refreshInstalledPackages(pythonVersion, site)
                return@withContext Result.success("تم تثبيت $cleanSpec عبر pip بنجاح")
            } else if (!res.blockedByAndroidWx) {
                logDao.insert(AppLogEntity(level = "ERROR", source = "PIP", message = "فشل تثبيت $cleanSpec: ${res.stderr}"))
                return@withContext Result.failure(IllegalStateException("فشل pip install $cleanSpec:\n${res.combinedOutput}"))
            }
        }

        // Pure-Python Wheel verified fallback via PyPI API when native pip cannot execute (or binary not installed)
        val pkgInfoRes = inspectPypiPackageCompatibility(cleanSpec)
        val pkgInfo = pkgInfoRes.getOrElse {
            _lastPipOutput.value = it.message ?: "فشل الفحص"
            return@withContext Result.failure(it)
        }

        if (!pkgInfo.isPurePythonWheel || pkgInfo.wheelUrl.isBlank()) {
            val err = "تعذر تثبيت ${pkgInfo.name} (${pkgInfo.version}): ${pkgInfo.compatibilityNote}"
            _lastPipOutput.value = err
            logDao.insert(AppLogEntity(level = "ERROR", source = "PIP", message = err))
            return@withContext Result.failure(IllegalStateException(err))
        }

        val sitePackagesDir = if (site != null) {
            File(site.rootFolder, "venv/lib/python$pythonVersion/site-packages").apply { mkdirs() }
        } else {
            File(runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion), "lib/python$pythonVersion/site-packages").apply { mkdirs() }
        }

        val wheelFile = File(context.cacheDir, "${pkgInfo.name}-${pkgInfo.version}.whl")
        val dlRes = downloader.downloadFileVerified(
            taskId = "pip_${pkgInfo.name}",
            packageId = "pypi_${pkgInfo.name}",
            title = "pip: ${pkgInfo.name} ${pkgInfo.version}",
            url = pkgInfo.wheelUrl,
            expectedSha256 = pkgInfo.sha256,
            destFile = wheelFile
        )
        if (dlRes.isFailure) {
            val msg = dlRes.exceptionOrNull()?.message ?: "فشل تنزيل العجلة من PyPI"
            _lastPipOutput.value = msg
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val extRes = ArchiveExtractor.extractArchive(wheelFile, sitePackagesDir)
        wheelFile.delete()
        if (extRes.isFailure) {
            val msg = extRes.exceptionOrNull()?.message ?: "فشل فك حزمة Wheel"
            _lastPipOutput.value = msg
            return@withContext Result.failure(IllegalStateException(msg))
        }

        downloader.updateInstallProgress(
            taskId = "pip_${pkgInfo.name}",
            stageLabel = "تم استخراج Pure-Python Wheel في site-packages",
            installProgress = 1f,
            isFinished = true,
            isVerified = pyBin != null,
            verificationResult = if (pyBin == null) {
                "تم تجهيز ملفات ${pkgInfo.name} في site-packages، لكن يلزم تثبيت مفسر python3 للتنفيذ الفعلي."
            } else {
                "تم تثبيت ${pkgInfo.name} (${pkgInfo.version}) بنجاح"
            }
        )
        refreshInstalledPackages(pythonVersion, site)
        val summaryMsg = "تم تجهيز ${pkgInfo.name} ${pkgInfo.version} (${extRes.getOrThrow().extractedFiles} ملف) في site-packages." +
            if (pyBin == null) " ملاحظة: مفسر python3 الثنائي مطلوب لتشغيل الكود." else ""
        _lastPipOutput.value = summaryMsg
        Result.success(summaryMsg)
    }

    suspend fun pipUninstallPackage(
        pythonVersion: String,
        packageName: String,
        site: SiteEntity? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = packageName.trim().lowercase(Locale.ROOT)
        val pyBin = findPythonBinary(pythonVersion)
        val workDir = site?.let { File(it.rootFolder) } ?: runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion)

        if (pyBin != null) {
            val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pythonVersion, site?.let { File(it.rootFolder) })
            val res = runtimeEngine.executeCommand(listOf(pyBin.absolutePath, "-m", "pip", "uninstall", "-y", clean), workDir, env, 30L)
            _lastPipOutput.value = res.combinedOutput
            if (res.isSuccess) {
                refreshInstalledPackages(pythonVersion, site)
                return@withContext Result.success("تم حذف $clean عبر pip")
            }
        }

        val sitePackagesDir = if (site != null) {
            File(site.rootFolder, "venv/lib/python$pythonVersion/site-packages")
        } else {
            File(runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion), "lib/python$pythonVersion/site-packages")
        }
        var removed = 0
        sitePackagesDir.listFiles()?.forEach { f ->
            if (f.name.lowercase(Locale.ROOT) == clean || f.name.lowercase(Locale.ROOT).startsWith("${clean.replace('-', '_')}-")) {
                if (if (f.isDirectory) f.deleteRecursively() else f.delete()) removed++
            }
        }
        refreshInstalledPackages(pythonVersion, site)
        if (removed > 0) Result.success("تمت إزالة حزمة $clean من site-packages")
        else Result.failure(IllegalStateException("لم يُعثر على الحزمة $clean"))
    }

    suspend fun pipUpgradePackage(pythonVersion: String, packageName: String, site: SiteEntity? = null): Result<String> {
        val pyBin = findPythonBinary(pythonVersion)
        if (pyBin != null) {
            val workDir = site?.let { File(it.rootFolder) } ?: runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion)
            val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pythonVersion, site?.let { File(it.rootFolder) })
            val res = runtimeEngine.executeCommand(listOf(pyBin.absolutePath, "-m", "pip", "install", "--upgrade", packageName), workDir, env, 90L)
            _lastPipOutput.value = res.combinedOutput
            if (res.isSuccess) {
                refreshInstalledPackages(pythonVersion, site)
                return Result.success("تم تحديث $packageName بنجاح")
            }
        }
        return pipInstallPackage(pythonVersion, packageName, site)
    }

    suspend fun installFromRequirementsFile(pythonVersion: String, reqFile: File, site: SiteEntity? = null): Result<String> {
        if (!reqFile.exists()) return Result.failure(IllegalStateException("ملف requirements.txt غير موجود"))
        val pyBin = findPythonBinary(pythonVersion)
        if (pyBin != null) {
            val workDir = site?.let { File(it.rootFolder) } ?: runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion)
            val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pythonVersion, site?.let { File(it.rootFolder) })
            val res = runtimeEngine.executeCommand(listOf(pyBin.absolutePath, "-m", "pip", "install", "-r", reqFile.absolutePath), workDir, env, 180L)
            _lastPipOutput.value = res.combinedOutput
            if (res.isSuccess) {
                refreshInstalledPackages(pythonVersion, site)
                return Result.success("تم تثبيت كافة متطلبات requirements.txt عبر pip")
            } else if (!res.blockedByAndroidWx) {
                return Result.failure(IllegalStateException("فشل تثبيت requirements.txt:\n${res.combinedOutput}"))
            }
        }

        val lines = reqFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val failures = mutableListOf<String>()
        var installedCount = 0
        lines.forEach { dep ->
            val res = pipInstallPackage(pythonVersion, dep, site)
            if (res.isSuccess) installedCount++ else failures.add("$dep: ${res.exceptionOrNull()?.message}")
        }
        return if (failures.isEmpty()) {
            Result.success("تم تجهيز $installedCount مكتبة من requirements.txt")
        } else {
            Result.failure(IllegalStateException("تم تجهيز $installedCount وفشل ${failures.size}:\n${failures.joinToString("\n")}"))
        }
    }

    suspend fun refreshInstalledPackages(pythonVersion: String, site: SiteEntity? = null): List<PythonPackageInfo> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<PythonPackageInfo>()
            val pyBin = findPythonBinary(pythonVersion)
            val workDir = site?.let { File(it.rootFolder) } ?: runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion)

            if (pyBin != null) {
                val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pythonVersion, site?.let { File(it.rootFolder) })
                val res = runtimeEngine.executeCommand(listOf(pyBin.absolutePath, "-m", "pip", "list", "--format=json"), workDir, env, 15L)
                if (res.isSuccess && res.stdout.trim().startsWith("[")) {
                    try {
                        val arr = JSONArray(res.stdout.trim())
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            list.add(
                                PythonPackageInfo(
                                    name = obj.optString("name"),
                                    version = obj.optString("version"),
                                    isPurePythonWheel = true,
                                    isInstalled = true,
                                    compatibilityNote = "مثبت عبر pip الفعلي"
                                )
                            )
                        }
                        _installedPackages.value = list
                        return@withContext list
                    } catch (_: Exception) {
                    }
                }
            }

            // Fallback: inspect *.dist-info/METADATA inside site-packages
            val sitePackagesDir = if (site != null) {
                File(site.rootFolder, "venv/lib/python$pythonVersion/site-packages")
            } else {
                File(runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion), "lib/python$pythonVersion/site-packages")
            }
            if (sitePackagesDir.exists()) {
                sitePackagesDir.listFiles { f -> f.isDirectory && f.name.endsWith(".dist-info") }?.forEach { dist ->
                    val meta = File(dist, "METADATA")
                    var pkgName = dist.name.substringBefore('-')
                    var pkgVer = dist.name.substringAfter('-').removeSuffix(".dist-info")
                    if (meta.exists()) {
                        meta.readLines().forEach { line ->
                            if (line.startsWith("Name:")) pkgName = line.substringAfter(':').trim()
                            if (line.startsWith("Version:")) pkgVer = line.substringAfter(':').trim()
                        }
                    }
                    list.add(
                        PythonPackageInfo(
                            name = pkgName,
                            version = pkgVer,
                            isPurePythonWheel = true,
                            isInstalled = true,
                            compatibilityNote = "مثبت في site-packages المعزول"
                        )
                    )
                }
            }
            _installedPackages.value = list
            list
        }

    suspend fun createVirtualEnv(site: SiteEntity, pythonVersion: String): Result<String> = withContext(Dispatchers.IO) {
        val venvDir = File(site.rootFolder, "venv")
        val pyBin = findPythonBinary(pythonVersion)
        if (pyBin != null) {
            val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pythonVersion, File(site.rootFolder))
            val res = runtimeEngine.executeCommand(listOf(pyBin.absolutePath, "-m", "venv", venvDir.absolutePath), File(site.rootFolder), env, 45L)
            if (res.isSuccess) {
                logDao.insert(AppLogEntity(level = "INFO", source = "VENV", message = "تم إنشاء بيئة venv للموقع ${site.name}"))
                return@withContext Result.success("تم إنشاء بيئة Python venv بنجاح في ${venvDir.name}")
            } else if (!res.blockedByAndroidWx) {
                return@withContext Result.failure(IllegalStateException("فشل إنشاء venv: ${res.combinedOutput}"))
            }
        }
        // Create compliant venv directory layout for isolated wheels while noting native binary requirement
        File(venvDir, "bin").mkdirs()
        File(venvDir, "lib/python$pythonVersion/site-packages").mkdirs()
        File(venvDir, "pyvenv.cfg").writeText("home = ${runtimeEngine.getRuntimeDir(EngineType.PYTHON, pythonVersion).absolutePath}\ninclude-system-site-packages = false\nversion = $pythonVersion\n")
        Result.success("تم تجهيز هيكل البيئة الافتراضية venv (يُربط بمفسر Python عند توفره)")
    }

    suspend fun startPythonWebApp(site: SiteEntity, framework: String): Result<String> = withContext(Dispatchers.IO) {
        val pyVersion = site.runtimeVersion.ifBlank { "3.12" }
        val pyBin = findPythonBinary(pyVersion)
            ?: return@withContext Result.failure(IllegalStateException("مفسر Python ($pyVersion) غير مثبت لمعمارية ${ProcessProbe.getDeviceAbi()}"))

        val siteRoot = File(site.rootFolder)
        val workDir = if (File(siteRoot, "public_html/${site.entryFile}").exists()) File(siteRoot, "public_html") else siteRoot
        val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pyVersion, siteRoot)

        val command = when (framework.uppercase(Locale.ROOT)) {
            "PYTHON_DJANGO", "DJANGO" -> {
                // Run Django migrations before starting server
                val migrateRes = runtimeEngine.executeCommand(
                    listOf(pyBin.absolutePath, "manage.py", "migrate", "--noinput"),
                    workDir,
                    env,
                    45L
                )
                if (!migrateRes.isSuccess) {
                    return@withContext Result.failure(IllegalStateException("فشل تنفيذ Django migrations: ${migrateRes.combinedOutput}"))
                }
                listOf(pyBin.absolutePath, "manage.py", "runserver", "127.0.0.1:${site.port}", "--noreload")
            }
            "PYTHON_FASTAPI", "FASTAPI" -> {
                val moduleName = site.entryFile.removeSuffix(".py")
                listOf(pyBin.absolutePath, "-m", "uvicorn", "$moduleName:app", "--host", "127.0.0.1", "--port", site.port.toString())
            }
            else -> {
                listOf(pyBin.absolutePath, "-m", "flask", "--app", site.entryFile, "run", "--host=127.0.0.1", "--port=${site.port}")
            }
        }

        val startRes = runtimeEngine.startService(
            ManagedServiceSpec(
                serviceId = "py_site_${site.id}",
                engineType = EngineType.PYTHON,
                version = pyVersion,
                command = command,
                workingDir = workDir,
                env = env,
                listenPort = site.port
            )
        )
        startRes.map { "تم تشغيل تطبيق $framework على http://127.0.0.1:${site.port}" }
    }

    /**
     * Mandatory 7-step real Python verification test suite:
     * 1. python3 --version
     * 2. pip3 --version
     * 3. Core stdlib imports
     * 4. Real pip install test
     * 5. Launch sample Flask app
     * 6. Local HTTP loopback probe
     * 7. Stop & restart service + re-verify HTTP
     */
    suspend fun runPythonVerificationSuite(pythonVersion: String): PythonVerificationReport =
        withContext(Dispatchers.IO) {
            val steps = mutableListOf<PythonCheckStep>()
            val abi = ProcessProbe.getDeviceAbi()
            val pyBin = findPythonBinary(pythonVersion)
            val workDir = File(context.cacheDir, "py_verify_test").apply {
                deleteRecursively()
                mkdirs()
            }
            val env = runtimeEngine.buildEnvironment(EngineType.PYTHON, pythonVersion)

            if (pyBin == null) {
                val failSteps = listOf(
                    "1. python3 --version",
                    "2. pip3 --version",
                    "3. استيراد المكتبات الأساسية",
                    "4. تثبيت مكتبة عبر pip",
                    "5. تشغيل مشروع Flask تجريبي",
                    "6. الوصول للمشروع محلياً عبر HTTP",
                    "7. إيقاف الخدمة وإعادة تشغيلها"
                ).mapIndexed { idx, title ->
                    PythonCheckStep(
                        stepNumber = idx + 1,
                        title = title,
                        probeTarget = "python3",
                        passed = false,
                        detail = "لم يتم العثور على مفسر python3 متوافق مع معمارية $abi"
                    )
                }
                val report = PythonVerificationReport(pythonVersion, abi, failSteps, allPassed = false)
                _lastVerificationReport.value = report
                return@withContext report
            }

            // Step 1: python3 --version
            val s1 = runtimeEngine.executeCommand(listOf(pyBin.absolutePath, "--version"), workDir, env, 8L)
            steps.add(PythonCheckStep(1, "python3 --version", "${pyBin.name} --version", s1.isSuccess, s1.combinedOutput.ifBlank { s1.stderr }))

            // Step 2: pip3 --version
            val s2 = runtimeEngine.executeCommand(listOf(pyBin.absolutePath, "-m", "pip", "--version"), workDir, env, 10L)
            steps.add(PythonCheckStep(2, "pip3 --version", "python3 -m pip --version", s2.isSuccess, s2.combinedOutput.ifBlank { s2.stderr }))

            // Step 3: Core imports
            val s3 = runtimeEngine.executeCommand(
                listOf(pyBin.absolutePath, "-c", "import sys, os, ssl, sqlite3, json, venv; print('STDLIB_OK')"),
                workDir, env, 10L
            )
            steps.add(PythonCheckStep(3, "استيراد المكتبات الأساسية", "sys, os, ssl, sqlite3, json, venv", s3.isSuccess && s3.stdout.contains("STDLIB_OK"), s3.combinedOutput))

            // Step 4: pip install flask
            val s4 = runtimeEngine.executeCommand(
                listOf(pyBin.absolutePath, "-m", "pip", "install", "flask"),
                workDir, env, 90L
            )
            steps.add(PythonCheckStep(4, "اختبار تثبيت مكتبة عبر pip", "pip install flask", s4.isSuccess, s4.combinedOutput.takeLast(300)))

            // Step 5 & 6: Flask launch + HTTP loopback probe
            val testPort = findFreePort()
            File(workDir, "app.py").writeText(
                """
                from flask import Flask
                app = Flask(__name__)
                @app.route("/")
                def home():
                    return "ABOTALAL_PYTHON_OK"
                if __name__ == "__main__":
                    app.run(host="127.0.0.1", port=$testPort)
                """.trimIndent()
            )
            val svcId = "py_verify_flask"
            val startRes = runtimeEngine.startService(
                ManagedServiceSpec(
                    serviceId = svcId,
                    engineType = EngineType.PYTHON,
                    version = pythonVersion,
                    command = listOf(pyBin.absolutePath, "app.py"),
                    workingDir = workDir,
                    env = env,
                    listenPort = testPort
                )
            )
            steps.add(
                PythonCheckStep(
                    5,
                    "تشغيل مشروع Flask تجريبي",
                    "python3 app.py (:$testPort)",
                    startRes.isSuccess,
                    startRes.fold(onSuccess = { "بدأ على المنفذ $testPort" }, onFailure = { it.message ?: "فشل التشغيل" })
                )
            )

            val httpOk1 = if (startRes.isSuccess) probeHttp200("http://127.0.0.1:$testPort/") else false to "الخادم لم يعمل"
            steps.add(PythonCheckStep(6, "اختبار الوصول المحلي عبر HTTP", "GET http://127.0.0.1:$testPort/", httpOk1.first, httpOk1.second))

            // Step 7: Stop + restart + HTTP probe
            val restartOk = if (startRes.isSuccess) {
                val restartRes = runtimeEngine.restartService(svcId)
                val httpOk2 = if (restartRes.isSuccess) probeHttp200("http://127.0.0.1:$testPort/") else false to "فشلت إعادة التشغيل"
                runtimeEngine.stopService(svcId)
                (restartRes.isSuccess && httpOk2.first) to httpOk2.second
            } else {
                false to "تعذر الاختبار لأن الخدمة لم تعمل في الخطوة 5"
            }
            steps.add(PythonCheckStep(7, "إيقاف الخدمة وإعادة تشغيلها", "stop -> start -> GET /", restartOk.first, restartOk.second))

            workDir.deleteRecursively()
            val allPassed = steps.all { it.passed }
            val report = PythonVerificationReport(pythonVersion, abi, steps, allPassed)
            _lastVerificationReport.value = report
            logDao.insert(
                AppLogEntity(
                    level = if (allPassed) "INFO" else "WARN",
                    source = "PYTHON_VERIFY",
                    message = "نتيجة اختبار Python $pythonVersion: ${steps.count { it.passed }}/7 خطوات ناجحة"
                )
            )
            report
        }

    private fun probeHttp200(urlStr: String): Pair<Boolean, String> {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            (code == 200 && body.contains("ABOTALAL_PYTHON_OK")) to "HTTP $code: $body"
        } catch (e: Exception) {
            false to (e.message ?: "فشل الاتصال")
        }
    }

    private fun findFreePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (_: Exception) {
        18910
    }

    suspend fun saveRequirements(version: String, requirementsText: String) {
        val reqFile = getRequirementsFile(version)
        reqFile.parentFile?.mkdirs()
        reqFile.writeText(requirementsText)
        val pkgList = requirementsText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(",") { it.substringBefore("=").substringBefore(">").substringBefore("<").trim() }

        val existing = runtimeConfigDao.getById("python_$version")
        runtimeConfigDao.upsert(
            (existing ?: RuntimeConfigEntity(
                id = "python_$version",
                engineType = EngineType.PYTHON.name,
                version = version,
                installPath = runtimeEngine.getRuntimeDir(EngineType.PYTHON, version).absolutePath,
                configText = requirementsText,
                extensionsCsv = pkgList,
                port = 8000,
                isInstalled = findPythonBinary(version) != null
            )).copy(
                configText = requirementsText,
                extensionsCsv = pkgList,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
