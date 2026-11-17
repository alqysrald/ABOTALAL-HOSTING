package com.example.engine.core

import android.content.Context
import android.os.Build
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

interface HostingEngineModule {
    val engineType: EngineType
    suspend fun probeEngine(): EngineInfo
    suspend fun start(): Result<EngineInfo>
    suspend fun stop(): Result<EngineInfo>
    suspend fun restart(): Result<EngineInfo> = stop().let { start() }
}

object ProcessProbe {

    fun getDeviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    fun canExecuteInAppData(context: Context): Boolean {
        val targetSdk = context.applicationInfo?.targetSdkVersion ?: Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || targetSdk < Build.VERSION_CODES.Q
    }

    fun mapBinaryNameToNativeLib(name: String): String = when (name.lowercase()) {
        "php-cgi" -> "libphp_cgi.so"
        "php" -> "libphp.so"
        "python3", "python" -> "libpython3.so"
        "node", "nodejs" -> "libnode.so"
        "mariadbd" -> "libmariadbd.so"
        "mysqld" -> "libmysqld.so"
        "postgres" -> "libpostgres.so"
        "pg_ctl" -> "libpg_ctl.so"
        "composer" -> "libcomposer.so"
        else -> if (name.startsWith("lib") && name.endsWith(".so")) name else "lib${name.replace('-', '_')}.so"
    }

    /**
     * Searches read-only APK nativeLibraryDir FIRST (Android 10–17+ W^X & SELinux compliant),
     * and only searches writable filesDir/runtimes when API < 29 or targetSdk < 29 permits execve in app_data_file.
     */
    fun findBinary(context: Context, binaryNames: List<String>, subDir: String): File? {
        val nativeDir = context.applicationInfo?.nativeLibraryDir?.let { File(it) }
        if (nativeDir != null && nativeDir.exists() && nativeDir.isDirectory) {
            val mappedCandidates = binaryNames.flatMap { listOf(mapBinaryNameToNativeLib(it), it) }.distinct()
            for (name in mappedCandidates) {
                val candidate = File(nativeDir, name)
                if (candidate.exists() && candidate.isFile && candidate.canExecute()) {
                    return candidate
                }
            }
        }

        val allowAppData = canExecuteInAppData(context)
        val searchDirs = buildList {
            if (allowAppData) {
                val runtimeBase = File(context.filesDir, "runtimes/$subDir")
                add(File(runtimeBase, "bin"))
                add(File(runtimeBase, "usr/bin"))
                add(runtimeBase)
                if (runtimeBase.exists() && runtimeBase.isDirectory) {
                    runtimeBase.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.forEach { verDir ->
                        add(File(verDir, "bin"))
                        add(File(verDir, "usr/bin"))
                        add(verDir)
                    }
                }
            }
            add(File("/system/bin"))
        }
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            for (name in binaryNames) {
                val candidate = File(dir, name)
                if (candidate.exists() && candidate.isFile && candidate.canExecute()) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Checks whether a binary exists in writable filesDir/runtimes that Android 10+ W^X / SELinux blocks from executing.
     */
    fun findBlockedAppDataBinary(context: Context, binaryNames: List<String>, subDir: String): File? {
        if (canExecuteInAppData(context)) return null
        val runtimeBase = File(context.filesDir, "runtimes/$subDir")
        val searchDirs = buildList {
            add(File(runtimeBase, "bin"))
            add(File(runtimeBase, "usr/bin"))
            add(runtimeBase)
            if (runtimeBase.exists() && runtimeBase.isDirectory) {
                runtimeBase.listFiles()?.filter { it.isDirectory }?.forEach { verDir ->
                    add(File(verDir, "bin"))
                    add(File(verDir, "usr/bin"))
                    add(verDir)
                }
            }
        }
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            for (name in binaryNames) {
                val candidate = File(dir, name)
                if (candidate.exists() && candidate.isFile) return candidate
            }
        }
        return null
    }

    fun probeBinaryVersion(binary: File, flag: String = "--version"): Pair<Boolean, String> {
        return try {
            val libDir = binary.parentFile?.parentFile?.let { File(it, "lib") }
            val parentDir = binary.parentFile
            val pb = ProcessBuilder(binary.absolutePath, flag).redirectErrorStream(true)
            val oldLd = pb.environment()["LD_LIBRARY_PATH"]
            val ldJoined = listOfNotNull(
                parentDir?.takeIf { it.exists() }?.absolutePath,
                libDir?.takeIf { it.exists() }?.absolutePath,
                oldLd
            ).distinct().joinToString(":")
            if (ldJoined.isNotBlank()) {
                pb.environment()["LD_LIBRARY_PATH"] = ldJoined
            }
            val process = pb.start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false to "انتهت مهلة فحص المحرك"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.exitValue()
            if (exitCode == 0 && output.isNotBlank()) {
                true to output.lineSequence().firstOrNull().orEmpty()
            } else {
                false to "رمز الخروج $exitCode: $output"
            }
        } catch (e: Exception) {
            false to (e.message ?: "تعذر تنفيذ الملف الثنائي على هذا الجهاز")
        }
    }

    fun isTcpPortListening(host: String = "127.0.0.1", port: Int, timeoutMs: Int = 300): Boolean {
        if (port <= 0) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

class ExternalTcpEngineModule(
    private val context: Context,
    private val runtimeEngine: RuntimeEngine,
    override val engineType: EngineType,
    private val binaryCandidates: List<String>,
    private val subDir: String,
    private val defaultPort: Int,
    private val requirementNoticeText: String,
    private val buildLaunchCommand: (binary: File, dataDir: File, port: Int) -> List<String> = { bin, dataDir, port ->
        when (engineType) {
            EngineType.MYSQL_MARIADB -> listOf(
                bin.absolutePath,
                "--datadir=${dataDir.absolutePath}",
                "--port=$port",
                "--bind-address=127.0.0.1"
            )
            EngineType.POSTGRESQL -> listOf(
                bin.absolutePath,
                "-D", dataDir.absolutePath,
                "-p", port.toString(),
                "-h", "127.0.0.1"
            )
            else -> listOf(bin.absolutePath)
        }
    }
) : HostingEngineModule {

    private val serviceId = "engine_${engineType.name.lowercase()}"

    override suspend fun probeEngine(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        val binary = ProcessProbe.findBinary(context, binaryCandidates, subDir)
        val blockedAppDataBin = ProcessProbe.findBlockedAppDataBinary(context, binaryCandidates, subDir)
        val portListening = ProcessProbe.isTcpPortListening("127.0.0.1", defaultPort)
        val managedAlive = runtimeEngine.isServiceAlive(serviceId)

        if (portListening) {
            val verText = binary?.let { ProcessProbe.probeBinaryVersion(it).second }
                ?: "خدمة نشطة على المنفذ $defaultPort"
            return EngineInfo(
                type = engineType,
                status = EngineStatus.RUNNING,
                activeVersion = verText,
                availableVersions = defaultVersionsFor(engineType),
                port = defaultPort,
                binaryPath = binary?.absolutePath,
                architecture = abi,
                verificationDetails = "تم التحقق عبر اتصال TCP ناجح على 127.0.0.1:$defaultPort" +
                    if (managedAlive) " (عملية مُدارة نشطة)" else "",
                requirementNotice = requirementNoticeText,
                lastCheckedAt = System.currentTimeMillis()
            )
        }

        if (binary != null) {
            val (ok, versionLine) = ProcessProbe.probeBinaryVersion(binary)
            return EngineInfo(
                type = engineType,
                status = if (ok) EngineStatus.STOPPED else EngineStatus.ERROR,
                activeVersion = if (ok) versionLine else "غير متحقق",
                availableVersions = defaultVersionsFor(engineType),
                port = defaultPort,
                binaryPath = binary.absolutePath,
                architecture = abi,
                verificationDetails = if (ok) "الحزمة التنفيذية موجودة ومتوقفة: $versionLine" else "فشل التحقق التنفيذي: $versionLine",
                requirementNotice = requirementNoticeText,
                lastCheckedAt = System.currentTimeMillis()
            )
        }

        if (blockedAppDataBin != null) {
            return EngineInfo(
                type = engineType,
                status = EngineStatus.ERROR,
                activeVersion = "محظور بواسطة W^X / SELinux",
                availableVersions = defaultVersionsFor(engineType),
                port = defaultPort,
                binaryPath = blockedAppDataBin.absolutePath,
                architecture = abi,
                verificationDetails = "Android API ${Build.VERSION.SDK_INT} (W^X/SELinux) يمنع تنفيذ ${blockedAppDataBin.name} من app_data_file (error=13). يتطلب حزمة lib*.so في nativeLibraryDir.",
                requirementNotice = requirementNoticeText,
                lastCheckedAt = System.currentTimeMillis()
            )
        }

        return EngineInfo(
            type = engineType,
            status = EngineStatus.NOT_INSTALLED,
            activeVersion = "غير مثبت",
            availableVersions = defaultVersionsFor(engineType),
            port = defaultPort,
            binaryPath = null,
            architecture = abi,
            verificationDetails = "لم يتم العثور على ثنائي متوافق في nativeLibraryDir لمعمارية $abi أو منفذ نشط ($defaultPort).",
            requirementNotice = requirementNoticeText,
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    override suspend fun start(): Result<EngineInfo> {
        val current = probeEngine()
        if (current.status == EngineStatus.RUNNING) {
            return Result.success(current)
        }
        val bin = ProcessProbe.findBinary(context, binaryCandidates, subDir)
            ?: return Result.failure(
                IllegalStateException(
                    if (current.status == EngineStatus.ERROR) {
                        current.verificationDetails
                    } else {
                        "المحرك غير مثبت في nativeLibraryDir لمعمارية ${current.architecture}. لا يتوفر خادم ${engineType.titleAr} للتشغيل."
                    }
                )
            )

        val runtimeDir = runtimeEngine.getRuntimeDir(engineType, "default")
        val dataDir = File(runtimeDir, "data").apply { mkdirs() }
        val env = runtimeEngine.buildEnvironment(engineType, "default")

        // Initialize PostgreSQL / MariaDB data directory if empty and init binary exists
        if (engineType == EngineType.MYSQL_MARIADB && File(dataDir, "mysql").exists().not()) {
            val initBin = ProcessProbe.findBinary(context, listOf("mariadb-install-db", "mysql_install_db"), subDir)
            if (initBin != null) {
                runtimeEngine.executeCommand(
                    listOf(initBin.absolutePath, "--datadir=${dataDir.absolutePath}"),
                    runtimeDir,
                    env,
                    45L
                )
            }
        } else if (engineType == EngineType.POSTGRESQL && File(dataDir, "PG_VERSION").exists().not()) {
            val initDbBin = ProcessProbe.findBinary(context, listOf("initdb"), subDir)
            if (initDbBin != null) {
                runtimeEngine.executeCommand(
                    listOf(initDbBin.absolutePath, "-D", dataDir.absolutePath, "-U", "postgres"),
                    runtimeDir,
                    env,
                    45L
                )
            }
        }

        val cmd = buildLaunchCommand(bin, dataDir, defaultPort)
        val startRes = runtimeEngine.startService(
            ManagedServiceSpec(
                serviceId = serviceId,
                engineType = engineType,
                version = current.activeVersion,
                command = cmd,
                workingDir = runtimeDir,
                env = env,
                listenPort = defaultPort
            )
        )

        return startRes.fold(
            onSuccess = { Result.success(probeEngine()) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun stop(): Result<EngineInfo> {
        runtimeEngine.stopService(serviceId)
        return Result.success(probeEngine())
    }

    override suspend fun restart(): Result<EngineInfo> {
        runtimeEngine.stopService(serviceId)
        return start()
    }

    private fun defaultVersionsFor(type: EngineType): List<String> = when (type) {
        EngineType.PHP -> listOf("8.4", "8.3", "8.2", "8.1")
        EngineType.PYTHON -> listOf("3.13", "3.12", "3.11", "3.10", "3.9")
        EngineType.MYSQL_MARIADB -> listOf("MariaDB 11.4", "MariaDB 10.11")
        EngineType.POSTGRESQL -> listOf("PostgreSQL 16", "PostgreSQL 15")
        EngineType.PHPMYADMIN -> listOf("5.2.2")
        else -> listOf("1.0")
    }
}
