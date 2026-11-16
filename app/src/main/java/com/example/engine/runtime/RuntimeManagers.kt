package com.example.engine.runtime

import android.content.Context
import android.net.Uri
import com.example.core.database.RuntimeConfigDao
import com.example.core.database.RuntimeConfigEntity
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.engine.core.ProcessProbe
import java.io.File
import java.util.zip.ZipInputStream

class PhpRuntimeManager(
    private val context: Context,
    private val runtimeConfigDao: RuntimeConfigDao
) {
    val supportedVersions = listOf("8.3", "8.2", "8.1")
    val standardExtensions = listOf(
        "pdo_sqlite",
        "sqlite3",
        "mysqli",
        "pdo_mysql",
        "mbstring",
        "curl",
        "openssl",
        "gd",
        "zip",
        "intl"
    )

    suspend fun ensureDefaultConfigs() {
        supportedVersions.forEach { ver ->
            val id = "php_$ver"
            if (runtimeConfigDao.getById(id) == null) {
                val iniFile = getPhpIniFile(ver)
                val defaultIni = buildDefaultPhpIni(ver, listOf("pdo_sqlite", "sqlite3", "mbstring", "curl", "openssl"))
                iniFile.parentFile?.mkdirs()
                if (!iniFile.exists()) iniFile.writeText(defaultIni)

                val bin = findPhpBinary(ver)
                runtimeConfigDao.upsert(
                    RuntimeConfigEntity(
                        id = id,
                        engineType = EngineType.PHP.name,
                        version = ver,
                        installPath = File(context.filesDir, "runtimes/php/$ver").absolutePath,
                        configText = iniFile.readText(),
                        extensionsCsv = "pdo_sqlite,sqlite3,mbstring,curl,openssl",
                        port = 9000,
                        isInstalled = bin != null
                    )
                )
            }
        }
    }

    fun getPhpIniFile(version: String): File =
        File(context.filesDir, "runtimes/php/$version/php.ini")

    fun findPhpBinary(version: String): File? =
        ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php/$version")
            ?: ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php")

    suspend fun probeStatus(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        var foundVersion: String? = null
        var foundBinary: File? = null
        var probeDetails = ""

        for (ver in supportedVersions) {
            val bin = findPhpBinary(ver)
            if (bin != null) {
                val (ok, output) = ProcessProbe.probeBinaryVersion(bin, "-v")
                foundBinary = bin
                foundVersion = if (ok) "$ver ($output)" else ver
                probeDetails = if (ok) "تم التحقق التنفيذي: $output" else "تعذر التنفيذ: $output"
                break
            }
        }

        val status = when {
            foundBinary != null && probeDetails.startsWith("تم التحقق") -> EngineStatus.STOPPED
            foundBinary != null -> EngineStatus.ERROR
            else -> EngineStatus.NOT_INSTALLED
        }

        return EngineInfo(
            type = EngineType.PHP,
            status = status,
            activeVersion = foundVersion ?: "غير مثبت",
            availableVersions = supportedVersions,
            port = 9000,
            binaryPath = foundBinary?.absolutePath,
            architecture = abi,
            verificationDetails = if (probeDetails.isNotBlank()) probeDetails else "لا يوجد ملف تنفيذي php / php-cgi متوافق مع $abi في مسار التشغيل.",
            requirementNotice = "لتشغيل مواقع PHP محلياً، يتطلب النظام حزمة ثنائية (php-cgi / php) مترجمة لمعمارية $abi ومصرحاً بتنفيذها ضمن سياسة النظام. إعدادات php.ini والإضافات محفوظة وجاهزة لكل إصدار.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    suspend fun savePhpIniAndExtensions(
        version: String,
        iniContent: String,
        enabledExtensions: List<String>
    ) {
        val iniFile = getPhpIniFile(version)
        iniFile.parentFile?.mkdirs()
        iniFile.writeText(iniContent)
        val existing = runtimeConfigDao.getById("php_$version")
        runtimeConfigDao.upsert(
            (existing ?: RuntimeConfigEntity(
                id = "php_$version",
                engineType = EngineType.PHP.name,
                version = version,
                installPath = File(context.filesDir, "runtimes/php/$version").absolutePath,
                configText = iniContent,
                extensionsCsv = "",
                port = 9000,
                isInstalled = findPhpBinary(version) != null
            )).copy(
                configText = iniContent,
                extensionsCsv = enabledExtensions.joinToString(","),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun importLocalRuntimeArchive(version: String, uri: Uri): Result<String> {
        return try {
            val targetDir = File(context.filesDir, "runtimes/php/$version").apply { mkdirs() }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(targetDir, entry.name).canonicalFile
                        if (!outFile.path.startsWith(targetDir.canonicalPath)) {
                            throw SecurityException("مسار غير آمن داخل الأرشيف")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                            if (outFile.name.startsWith("php")) {
                                outFile.setExecutable(true, true)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return Result.failure(IllegalStateException("تعذر فتح ملف الحزمة"))

            val bin = findPhpBinary(version)
            if (bin == null) {
                Result.failure(IllegalStateException("تم فك الحزمة لكن لم يتم العثور على الملف التنفيذي php أو php-cgi القابل للتشغيل على هذا النظام"))
            } else {
                val (ok, msg) = ProcessProbe.probeBinaryVersion(bin, "-v")
                if (ok) {
                    val existing = runtimeConfigDao.getById("php_$version")
                    if (existing != null) runtimeConfigDao.upsert(existing.copy(isInstalled = true))
                    Result.success("تم التثبيت والتحقق: $msg")
                } else {
                    Result.failure(IllegalStateException("النظام منع تنفيذ الملف الثنائي (سياسة W^X/SELinux): $msg"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildDefaultPhpIni(version: String, extensions: List<String>): String {
        val extLines = extensions.joinToString("\n") { "extension=$it.so" }
        return """
            ; Abotalal Hosting - PHP $version Configuration
            [PHP]
            engine = On
            short_open_tag = Off
            memory_limit = 256M
            max_execution_time = 60
            upload_max_filesize = 64M
            post_max_size = 64M
            display_errors = Off
            log_errors = On
            date.timezone = Asia/Riyadh
            cgi.fix_pathinfo = 1

            ; Enabled Extensions
            $extLines
        """.trimIndent()
    }
}

class PythonRuntimeManager(
    private val context: Context,
    private val runtimeConfigDao: RuntimeConfigDao
) {
    val supportedVersions = listOf("3.12", "3.11", "3.10")
    val frameworkPresets = mapOf(
        "Flask" to "flask>=3.0.0\ngunicorn>=22.0.0",
        "Django" to "django>=5.0\ngunicorn>=22.0.0",
        "FastAPI" to "fastapi>=0.115.0\nuvicorn>=0.30.0"
    )

    suspend fun ensureDefaultConfigs() {
        supportedVersions.forEach { ver ->
            val id = "python_$ver"
            if (runtimeConfigDao.getById(id) == null) {
                val reqFile = getRequirementsFile(ver)
                reqFile.parentFile?.mkdirs()
                val defaultReq = "# Abotalal Hosting Python $ver Packages\nflask>=3.0.0\nfastapi>=0.115.0\nuvicorn>=0.30.0\ndjango>=5.0\n"
                if (!reqFile.exists()) reqFile.writeText(defaultReq)

                runtimeConfigDao.upsert(
                    RuntimeConfigEntity(
                        id = id,
                        engineType = EngineType.PYTHON.name,
                        version = ver,
                        installPath = File(context.filesDir, "runtimes/python/$ver").absolutePath,
                        configText = reqFile.readText(),
                        extensionsCsv = "flask,django,fastapi,uvicorn,gunicorn",
                        port = 8000,
                        isInstalled = findPythonBinary(ver) != null
                    )
                )
            }
        }
    }

    fun getRequirementsFile(version: String): File =
        File(context.filesDir, "runtimes/python/$version/requirements.txt")

    fun findPythonBinary(version: String): File? =
        ProcessProbe.findBinary(context, listOf("python3", "python"), "python/$version")
            ?: ProcessProbe.findBinary(context, listOf("python3", "python"), "python")

    suspend fun probeStatus(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        var foundVersion: String? = null
        var foundBinary: File? = null
        var details = ""

        for (ver in supportedVersions) {
            val bin = findPythonBinary(ver)
            if (bin != null) {
                val (ok, output) = ProcessProbe.probeBinaryVersion(bin, "--version")
                foundBinary = bin
                foundVersion = if (ok) output else ver
                details = if (ok) "تم التحقق: $output" else "فشل التشغيل: $output"
                break
            }
        }

        val status = when {
            foundBinary != null && details.startsWith("تم التحقق") -> EngineStatus.STOPPED
            foundBinary != null -> EngineStatus.ERROR
            else -> EngineStatus.NOT_INSTALLED
        }

        return EngineInfo(
            type = EngineType.PYTHON,
            status = status,
            activeVersion = foundVersion ?: "غير مثبت",
            availableVersions = supportedVersions,
            port = 8000,
            binaryPath = foundBinary?.absolutePath,
            architecture = abi,
            verificationDetails = if (details.isNotBlank()) details else "مفسر Python غير مثبت لمعمارية $abi.",
            requirementNotice = "يتطلب تشغيل Flask / Django / FastAPI وجود مفسر python3 و pip متوافق مع $abi. يمكنك تحرير ملفات requirements.txt وتحديد نوع الإطار لكل مشروع.",
            lastCheckedAt = System.currentTimeMillis()
        )
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
                installPath = File(context.filesDir, "runtimes/python/$version").absolutePath,
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
