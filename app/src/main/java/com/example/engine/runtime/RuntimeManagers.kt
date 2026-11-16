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
    val supportedVersions = listOf("8.4", "8.3", "8.2", "8.1")
    val standardExtensions = listOf(
        "mysqli",
        "pdo_mysql",
        "mbstring",
        "curl",
        "gd",
        "fileinfo",
        "zip",
        "openssl",
        "pdo_sqlite",
        "sqlite3",
        "intl"
    )

    val socialScriptExtensions = listOf(
        "mysqli", "pdo_mysql", "mbstring", "curl", "gd", "fileinfo", "zip", "openssl"
    )

    suspend fun ensureDefaultConfigs() {
        supportedVersions.forEach { ver ->
            val id = "php_$ver"
            if (runtimeConfigDao.getById(id) == null) {
                val iniFile = getPhpIniFile(ver)
                val defaultIni = buildDefaultPhpIni(ver, socialScriptExtensions + listOf("pdo_sqlite", "sqlite3"))
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
                        extensionsCsv = (socialScriptExtensions + listOf("pdo_sqlite", "sqlite3")).joinToString(","),
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
            requirementNotice = "يدعم PHP 8.4 و 8.3 و 8.2 و 8.1 وإضافات Sngine و WoWonder (mysqli, mbstring, cURL, GD, fileinfo, ZIP, OpenSSL, allow_url_fopen, mail()).",
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

    suspend fun applySocialScriptsPreset(version: String): String {
        val ini = buildDefaultPhpIni(version, socialScriptExtensions + listOf("pdo_sqlite", "sqlite3"))
        savePhpIniAndExtensions(version, ini, socialScriptExtensions + listOf("pdo_sqlite", "sqlite3"))
        return ini
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
        val extLines = extensions.distinct().joinToString("\n") { "extension=$it.so" }
        return """
            ; Abotalal Hosting - PHP $version Configuration (Social Scripts Ready)
            [PHP]
            engine = On
            short_open_tag = On
            memory_limit = 512M
            max_execution_time = 120
            max_input_time = 120
            file_uploads = On
            upload_max_filesize = 128M
            post_max_size = 128M
            allow_url_fopen = On
            allow_url_include = Off
            display_errors = Off
            log_errors = On
            date.timezone = Asia/Riyadh
            cgi.fix_pathinfo = 1

            [mail function]
            ; Configure local SMTP/sendmail relay when available
            sendmail_path = /system/bin/sendmail -t -i

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
            requirementNotice = "يتطلب تشغيل Flask / Django / FastAPI وجود مفسر python3 و pip متوافق مع $abi.",
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

class NodeRuntimeManager(
    private val context: Context,
    private val runtimeConfigDao: RuntimeConfigDao
) {
    val supportedVersions = listOf("22", "20", "18")

    suspend fun ensureDefaultConfigs() {
        supportedVersions.forEach { ver ->
            val id = "node_$ver"
            if (runtimeConfigDao.getById(id) == null) {
                val pkgFile = getPackageJsonFile(ver)
                pkgFile.parentFile?.mkdirs()
                val defaultPkg = """
                    {
                      "name": "abotalal-realtime-service",
                      "version": "1.0.0",
                      "description": "Node.js + Socket.io Runtime for Sngine/WoWonder Realtime",
                      "main": "server.js",
                      "dependencies": {
                        "socket.io": "^4.7.5",
                        "express": "^4.19.2",
                        "mysql2": "^3.11.0"
                      }
                    }
                """.trimIndent()
                if (!pkgFile.exists()) pkgFile.writeText(defaultPkg)

                runtimeConfigDao.upsert(
                    RuntimeConfigEntity(
                        id = id,
                        engineType = EngineType.NODEJS.name,
                        version = ver,
                        installPath = File(context.filesDir, "runtimes/nodejs/$ver").absolutePath,
                        configText = pkgFile.readText(),
                        extensionsCsv = "socket.io,express,mysql2",
                        port = 3000,
                        isInstalled = findNodeBinary(ver) != null
                    )
                )
            }
        }
    }

    fun getPackageJsonFile(version: String): File =
        File(context.filesDir, "runtimes/nodejs/$version/package.json")

    fun findNodeBinary(version: String): File? =
        ProcessProbe.findBinary(context, listOf("node", "nodejs"), "nodejs/$version")
            ?: ProcessProbe.findBinary(context, listOf("node", "nodejs"), "nodejs")

    suspend fun probeStatus(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        var foundVersion: String? = null
        var foundBinary: File? = null
        var details = ""

        for (ver in supportedVersions) {
            val bin = findNodeBinary(ver)
            if (bin != null) {
                val (ok, output) = ProcessProbe.probeBinaryVersion(bin, "-v")
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
            type = EngineType.NODEJS,
            status = status,
            activeVersion = foundVersion ?: "غير مثبت",
            availableVersions = supportedVersions,
            port = 3000,
            binaryPath = foundBinary?.absolutePath,
            architecture = abi,
            verificationDetails = if (details.isNotBlank()) details else "بيئة Node.js غير مثبتة لمعمارية $abi.",
            requirementNotice = "ضرورية لخدمات الوقت الحقيقي Socket.io في مشاريع Sngine و WoWonder وواجهات Node.js.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    suspend fun savePackageJson(version: String, content: String) {
        val file = getPackageJsonFile(version)
        file.parentFile?.mkdirs()
        file.writeText(content)
        val existing = runtimeConfigDao.getById("node_$version")
        runtimeConfigDao.upsert(
            (existing ?: RuntimeConfigEntity(
                id = "node_$version",
                engineType = EngineType.NODEJS.name,
                version = version,
                installPath = File(context.filesDir, "runtimes/nodejs/$version").absolutePath,
                configText = content,
                extensionsCsv = "socket.io,express",
                port = 3000,
                isInstalled = findNodeBinary(version) != null
            )).copy(
                configText = content,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
