package com.example.engine.core

import android.content.Context
import android.os.Build
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.model.EngineType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CommandExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val blockedByAndroidWx: Boolean = false,
    val timedOut: Boolean = false
) {
    val combinedOutput: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()
    val isSuccess: Boolean
        get() = exitCode == 0 && !blockedByAndroidWx && !timedOut
}

data class ManagedServiceSpec(
    val serviceId: String,
    val engineType: EngineType,
    val version: String,
    val command: List<String>,
    val workingDir: File,
    val env: Map<String, String>,
    val listenPort: Int
)

data class ManagedServiceState(
    val spec: ManagedServiceSpec,
    val isAlive: Boolean,
    val portVerified: Boolean,
    val startedAt: Long,
    val lastError: String? = null
)

class RuntimeEngine(
    private val context: Context,
    private val logDao: AppLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = ConcurrentHashMap<String, Process>()
    private val specs = ConcurrentHashMap<String, ManagedServiceSpec>()

    private val _activeServices = MutableStateFlow<Map<String, ManagedServiceState>>(emptyMap())
    val activeServices: StateFlow<Map<String, ManagedServiceState>> = _activeServices.asStateFlow()

    val runtimesRoot: File
        get() = File(context.filesDir, "runtimes").apply { mkdirs() }

    val logsRoot: File
        get() = File(context.filesDir, "logs").apply { mkdirs() }

    fun getRuntimeDir(engineType: EngineType, version: String): File {
        val sub = when (engineType) {
            EngineType.PHP -> "php/$version"
            EngineType.PYTHON -> "python/$version"
            EngineType.NODEJS -> "nodejs/$version"
            EngineType.MYSQL_MARIADB -> "mariadb"
            EngineType.POSTGRESQL -> "postgresql"
            EngineType.PHPMYADMIN -> "phpmyadmin"
            else -> engineType.name.lowercase(Locale.ROOT)
        }
        return File(runtimesRoot, sub).apply { mkdirs() }
    }

    fun buildEnvironment(
        engineType: EngineType,
        version: String,
        siteRoot: File? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val runtimeDir = getRuntimeDir(engineType, version)
        val binDir = File(runtimeDir, "bin")
        val libDir = File(runtimeDir, "lib")
        val tmpDir = File(context.cacheDir, "runtime_tmp").apply { mkdirs() }
        val homeDir = siteRoot ?: runtimeDir

        val existingPath = System.getenv("PATH") ?: "/system/bin"
        val pathParts = listOfNotNull(
            binDir.takeIf { it.exists() }?.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            existingPath
        ).distinct().joinToString(":")

        val ldParts = listOfNotNull(
            libDir.takeIf { it.exists() }?.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            System.getenv("LD_LIBRARY_PATH")
        ).distinct().joinToString(":")

        val env = mutableMapOf(
            "PATH" to pathParts,
            "LD_LIBRARY_PATH" to ldParts,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "ANDROID_API_LEVEL" to Build.VERSION.SDK_INT.toString(),
            "ABOTALAL_ABI" to ProcessProbe.getDeviceAbi()
        )

        if (engineType == EngineType.PHP) {
            val sitePhpIni = siteRoot?.let { File(it, "config/php.ini") }?.takeIf { it.exists() }
            val runtimeIni = File(runtimeDir, "php.ini")
            val activeIni = sitePhpIni ?: runtimeIni
            env["PHPRC"] = activeIni.parentFile?.absolutePath ?: runtimeDir.absolutePath
            env["PHP_INI_SCAN_DIR"] = activeIni.parentFile?.absolutePath ?: runtimeDir.absolutePath
        } else if (engineType == EngineType.PYTHON) {
            if (File(runtimeDir, "lib").exists()) {
                env["PYTHONHOME"] = runtimeDir.absolutePath
            }
            val sitePackages = siteRoot?.let { File(it, "venv/lib/python$version/site-packages") }
            val runtimeSitePackages = File(runtimeDir, "lib/python$version/site-packages")
            val pyPaths = listOfNotNull(
                sitePackages?.takeIf { it.exists() }?.absolutePath,
                runtimeSitePackages.takeIf { it.exists() }?.absolutePath,
                siteRoot?.absolutePath
            ).joinToString(":")
            if (pyPaths.isNotBlank()) env["PYTHONPATH"] = pyPaths
            env["PYTHONUNBUFFERED"] = "1"
            env["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
        }

        env.putAll(extraEnv)
        return env
    }

    suspend fun executeCommand(
        command: List<String>,
        workingDir: File,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30L
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        if (command.isEmpty()) {
            return@withContext CommandExecutionResult(-1, "", "الأمر التنفيذي فارغ")
        }
        try {
            workingDir.mkdirs()
            val pb = ProcessBuilder(command)
            pb.directory(workingDir)
            pb.environment().putAll(env)
            val proc = pb.start()
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@withContext CommandExecutionResult(-1, "", "انتهت مهلة تنفيذ الأمر ($timeoutSeconds ثانية)", timedOut = true)
            }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val err = proc.errorStream.bufferedReader().use { it.readText() }
            CommandExecutionResult(
                exitCode = proc.exitValue(),
                stdout = out,
                stderr = err,
                blockedByAndroidWx = false
            )
        } catch (e: Exception) {
            val msg = e.message ?: "فشل تنفيذ الأمر"
            val isWx = msg.contains("Permission denied", ignoreCase = true) ||
                msg.contains("error=13", ignoreCase = true) ||
                msg.contains("noexec", ignoreCase = true)
            CommandExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = if (isWx) {
                    "قيود نظام Android (W^X / SELinux على API ${Build.VERSION.SDK_INT}) منعت تشغيل الملف الثنائي من مجلد التطبيق: $msg"
                } else {
                    msg
                },
                blockedByAndroidWx = isWx
            )
        }
    }

    suspend fun startService(spec: ManagedServiceSpec): Result<ManagedServiceState> = withContext(Dispatchers.IO) {
        stopService(spec.serviceId)
        try {
            spec.workingDir.mkdirs()
            val pb = ProcessBuilder(spec.command)
            pb.directory(spec.workingDir)
            pb.environment().putAll(spec.env)
            val process = pb.start()

            processes[spec.serviceId] = process
            specs[spec.serviceId] = spec

            val serviceLogFile = File(logsRoot, "${spec.serviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.log")
            streamToLog(process.inputStream, serviceLogFile, "INFO", spec.serviceId)
            streamToLog(process.errorStream, serviceLogFile, "ERROR", spec.serviceId)

            // Wait briefly and verify actual health
            var portUp = false
            for (attempt in 1..10) {
                if (!process.isAlive) break
                if (spec.listenPort <= 0 || ProcessProbe.isTcpPortListening("127.0.0.1", spec.listenPort, 200)) {
                    portUp = true
                    break
                }
                Thread.sleep(200)
            }

            val alive = process.isAlive
            if (!alive) {
                val exit = runCatching { process.exitValue() }.getOrDefault(-1)
                processes.remove(spec.serviceId)
                val lastLog = if (serviceLogFile.exists()) serviceLogFile.readLines().takeLast(5).joinToString(" | ") else ""
                val errMsg = "توقفت الخدمة فور التشغيل (رمز الخروج $exit). $lastLog"
                recordLog("ERROR", "SERVICE:${spec.serviceId}", errMsg)
                val failedState = ManagedServiceState(spec, isAlive = false, portVerified = false, startedAt = System.currentTimeMillis(), lastError = errMsg)
                _activeServices.value = _activeServices.value + (spec.serviceId to failedState)
                return@withContext Result.failure(IllegalStateException(errMsg))
            }

            val state = ManagedServiceState(
                spec = spec,
                isAlive = true,
                portVerified = portUp,
                startedAt = System.currentTimeMillis()
            )
            _activeServices.value = _activeServices.value + (spec.serviceId to state)
            recordLog("INFO", "SERVICE:${spec.serviceId}", "تم تشغيل الخدمة بنجاح (المنفذ ${spec.listenPort}, portVerified=$portUp)")
            Result.success(state)
        } catch (e: Exception) {
            val msg = e.message ?: "تعذر بدء الخدمة"
            val isWx = msg.contains("Permission denied", ignoreCase = true) || msg.contains("error=13", ignoreCase = true)
            val friendly = if (isWx) {
                "رفض Android W^X/SELinux تنفيذ الملف الثنائي مباشرة من مجلد البيانات: $msg"
            } else {
                "فشل بدء الخدمة ${spec.serviceId}: $msg"
            }
            recordLog("ERROR", "SERVICE:${spec.serviceId}", friendly)
            val failedState = ManagedServiceState(spec, isAlive = false, portVerified = false, startedAt = System.currentTimeMillis(), lastError = friendly)
            _activeServices.value = _activeServices.value + (spec.serviceId to failedState)
            Result.failure(IllegalStateException(friendly))
        }
    }

    suspend fun stopService(serviceId: String): Boolean = withContext(Dispatchers.IO) {
        val proc = processes.remove(serviceId) ?: return@withContext false
        try {
            proc.destroy()
            val exited = proc.waitFor(2, TimeUnit.SECONDS)
            if (!exited) proc.destroyForcibly()
        } catch (_: Exception) {
        }
        val spec = specs[serviceId]
        if (spec != null) {
            _activeServices.value = _activeServices.value + (
                serviceId to ManagedServiceState(spec, isAlive = false, portVerified = false, startedAt = 0L)
                )
        }
        recordLog("INFO", "SERVICE:$serviceId", "تم إيقاف الخدمة وتحرير مواردها")
        true
    }

    suspend fun restartService(serviceId: String): Result<ManagedServiceState> {
        val spec = specs[serviceId] ?: return Result.failure(IllegalStateException("لا توجد إعدادات تشغيل محفوظة للخدمة $serviceId"))
        stopService(serviceId)
        return startService(spec)
    }

    fun isServiceAlive(serviceId: String): Boolean {
        val proc = processes[serviceId] ?: return false
        return proc.isAlive
    }

    fun readServiceLogs(serviceId: String, tailLines: Int = 80): String {
        val file = File(logsRoot, "${serviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.log")
        if (!file.exists()) return "لا توجد سجلات لهذه الخدمة حتى الآن."
        return file.readLines().takeLast(tailLines).joinToString("\n")
    }

    fun stopAllServices() {
        processes.keys.toList().forEach { id ->
            val proc = processes.remove(id)
            runCatching {
                proc?.destroy()
                proc?.destroyForcibly()
            }
        }
    }

    private fun streamToLog(stream: java.io.InputStream, logFile: File, level: String, serviceId: String) {
        scope.launch {
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                        logFile.appendText("[$stamp] [$level] $line\n")
                        if (level == "ERROR") {
                            logDao.insert(AppLogEntity(level = "ERROR", source = "SERVICE:$serviceId", message = line.take(300)))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun recordLog(level: String, source: String, message: String) {
        scope.launch {
            logDao.insert(AppLogEntity(level = level, source = source, message = message))
        }
    }
}
