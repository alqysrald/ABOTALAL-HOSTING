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

    fun findBinary(context: Context, binaryNames: List<String>, subDir: String): File? {
        val runtimeBase = File(context.filesDir, "runtimes/$subDir")
        val searchDirs = buildList {
            add(File(runtimeBase, "bin"))
            add(File(runtimeBase, "usr/bin"))
            add(runtimeBase)
            // Scan installed version subdirectories (e.g. runtimes/php/8.4/bin)
            if (runtimeBase.exists() && runtimeBase.isDirectory) {
                runtimeBase.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.forEach { verDir ->
                    add(File(verDir, "bin"))
                    add(File(verDir, "usr/bin"))
                    add(verDir)
                }
            }
            context.applicationInfo.nativeLibraryDir?.let { add(File(it)) }
            add(File("/data/data/com.termux/files/usr/bin"))
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

    fun probeBinaryVersion(binary: File, flag: String = "--version"): Pair<Boolean, String> {
        return try {
            val libDir = binary.parentFile?.parentFile?.let { File(it, "lib") }
            val pb = ProcessBuilder(binary.absolutePath, flag).redirectErrorStream(true)
            if (libDir != null && libDir.exists()) {
                val oldLd = pb.environment()["LD_LIBRARY_PATH"]
                pb.environment()["LD_LIBRARY_PATH"] = listOfNotNull(libDir.absolutePath, oldLd).joinToString(":")
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

        return EngineInfo(
            type = engineType,
            status = EngineStatus.NOT_INSTALLED,
            activeVersion = "غير مثبت",
            availableVersions = defaultVersionsFor(engineType),
            port = defaultPort,
            binaryPath = null,
            architecture = abi,
            verificationDetails = "لم يتم العثور على ملف تنفيذي متوافق لمعمارية $abi أو منفذ نشط ($defaultPort).",
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
            ?: return Result.failure(IllegalStateException("المحرك غير مثبت على الجهاز. يرجى مزامنة المستودع وتثبيت الحزمة المتوافقة مع معمارية ${current.architecture} أولاً."))

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
