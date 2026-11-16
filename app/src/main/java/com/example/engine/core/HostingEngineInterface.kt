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
}

object ProcessProbe {

    fun getDeviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    fun findBinary(context: Context, binaryNames: List<String>, subDir: String): File? {
        val searchDirs = buildList {
            add(File(context.filesDir, "runtimes/$subDir/bin"))
            add(File(context.filesDir, "runtimes/$subDir"))
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
            val process = ProcessBuilder(binary.absolutePath, flag)
                .redirectErrorStream(true)
                .start()
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
    override val engineType: EngineType,
    private val binaryCandidates: List<String>,
    private val subDir: String,
    private val defaultPort: Int,
    private val requirementNoticeText: String
) : HostingEngineModule {

    override suspend fun probeEngine(): EngineInfo {
        val abi = ProcessProbe.getDeviceAbi()
        val binary = ProcessProbe.findBinary(context, binaryCandidates, subDir)
        val portListening = ProcessProbe.isTcpPortListening("127.0.0.1", defaultPort)

        if (portListening) {
            return EngineInfo(
                type = engineType,
                status = EngineStatus.RUNNING,
                activeVersion = binary?.let { ProcessProbe.probeBinaryVersion(it).second } ?: "خدمة نشطة على المنفذ $defaultPort",
                availableVersions = defaultVersionsFor(engineType),
                port = defaultPort,
                binaryPath = binary?.absolutePath,
                architecture = abi,
                verificationDetails = "تم التحقق عبر اتصال TCP ناجح على 127.0.0.1:$defaultPort",
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
        return if (current.status == EngineStatus.NOT_INSTALLED) {
            Result.failure(IllegalStateException("المحرك غير مثبت على الجهاز. يرجى تثبيت أو تهيئة الحزمة المتوافقة مع معمارية ${current.architecture} أولاً."))
        } else {
            Result.success(current)
        }
    }

    override suspend fun stop(): Result<EngineInfo> {
        return Result.success(probeEngine())
    }

    private fun defaultVersionsFor(type: EngineType): List<String> = when (type) {
        EngineType.PHP -> listOf("8.3", "8.2", "8.1")
        EngineType.PYTHON -> listOf("3.12", "3.11", "3.10")
        EngineType.MYSQL_MARIADB -> listOf("MariaDB 11.4", "MariaDB 10.11")
        EngineType.POSTGRESQL -> listOf("PostgreSQL 16", "PostgreSQL 15")
        EngineType.PHPMYADMIN -> listOf("5.2.1")
        else -> listOf("1.0")
    }
}
