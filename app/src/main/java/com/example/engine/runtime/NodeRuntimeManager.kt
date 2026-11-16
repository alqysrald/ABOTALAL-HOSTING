package com.example.engine.runtime

import android.content.Context
import com.example.core.database.RuntimeConfigDao
import com.example.core.database.RuntimeConfigEntity
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.engine.core.ProcessProbe
import com.example.engine.core.RuntimeEngine
import java.io.File

class NodeRuntimeManager(
    private val context: Context,
    private val runtimeConfigDao: RuntimeConfigDao,
    private val runtimeEngine: RuntimeEngine
) {
    val supportedVersions = listOf("22", "20", "18")

    suspend fun ensureDefaultConfigs() {
        supportedVersions.forEach { ver ->
            val id = "node_$ver"
            val pkgFile = getPackageJsonFile(ver)
            pkgFile.parentFile?.mkdirs()
            if (!pkgFile.exists()) {
                pkgFile.writeText(
                    """
                    {
                      "name": "abotalal-realtime-service",
                      "version": "1.0.0",
                      "description": "Node.js + Socket.io Runtime",
                      "main": "server.js",
                      "dependencies": {
                        "socket.io": "^4.7.5",
                        "express": "^4.19.2",
                        "mysql2": "^3.11.0"
                      }
                    }
                    """.trimIndent()
                )
            }
            if (runtimeConfigDao.getById(id) == null) {
                runtimeConfigDao.upsert(
                    RuntimeConfigEntity(
                        id = id,
                        engineType = EngineType.NODEJS.name,
                        version = ver,
                        installPath = runtimeEngine.getRuntimeDir(EngineType.NODEJS, ver).absolutePath,
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
        File(runtimeEngine.getRuntimeDir(EngineType.NODEJS, version), "package.json")

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
                val env = runtimeEngine.buildEnvironment(EngineType.NODEJS, ver)
                val res = runtimeEngine.executeCommand(listOf(bin.absolutePath, "-v"), bin.parentFile ?: runtimeEngine.runtimesRoot, env, 6L)
                foundBinary = bin
                val line = res.stdout.lineSequence().firstOrNull().orEmpty()
                foundVersion = if (res.isSuccess && line.isNotBlank()) line else ver
                details = if (res.isSuccess) "تم التحقق: $line" else res.stderr.ifBlank { "فشل التشغيل" }
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
            requirementNotice = "ضرورية لخدمات الوقت الحقيقي Socket.io وخوادم JavaScript.",
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
                installPath = runtimeEngine.getRuntimeDir(EngineType.NODEJS, version).absolutePath,
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
