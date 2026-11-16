package com.example.engine.core

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.model.AndroidPackageSpec
import com.example.core.model.EngineType
import com.example.core.model.PackageInstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class AndroidPackageManager(
    private val context: Context,
    private val downloader: PackageDownloader,
    private val runtimeEngine: RuntimeEngine,
    private val logDao: AppLogDao
) {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _packages = MutableStateFlow<List<AndroidPackageSpec>>(emptyList())
    val packages: StateFlow<List<AndroidPackageSpec>> = _packages.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncMessage = MutableStateFlow<String?>(null)
    val lastSyncMessage: StateFlow<String?> = _lastSyncMessage.asStateFlow()

    private val termuxRepoBase = "https://packages.termux.dev/apt/termux-main"
    private val phpmyadminListUrl = "https://www.phpmyadmin.net/downloads/list.json"

    init {
        refreshLocalCatalogState()
    }

    fun mapDeviceAbiToDebianArch(abi: String = ProcessProbe.getDeviceAbi()): String = when (abi.lowercase(Locale.ROOT)) {
        "arm64-v8a", "aarch64" -> "aarch64"
        "armeabi-v7a", "arm" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    /**
     * Rebuilds the baseline catalog purely from local verification state without inventing fake URLs or fake versions.
     * Official URLs, exact byte sizes, SHA-256 digests, and dependencies are populated only after live repository sync.
     */
    fun refreshLocalCatalogState() {
        val abi = ProcessProbe.getDeviceAbi()
        val existingRemote = _packages.value.associateBy { it.programName }

        val descriptors = listOf(
            Triple("php", EngineType.PHP, "حزمة PHP لمعمارية $abi (Android Bionic)"),
            Triple("python", EngineType.PYTHON, "مفسر Python 3 متوافق مع Android $abi"),
            Triple("nodejs-lts", EngineType.NODEJS, "بيئة Node.js LTS لمعمارية $abi"),
            Triple("mariadb", EngineType.MYSQL_MARIADB, "خادم قواعد البيانات MariaDB لمعمارية $abi"),
            Triple("postgresql", EngineType.POSTGRESQL, "خادم قواعد البيانات PostgreSQL لمعمارية $abi"),
            Triple("phpmyadmin", EngineType.PHPMYADMIN, "واجهة إدارة قواعد البيانات phpMyAdmin (PHP Pure)")
        )

        val updated = descriptors.map { (pkgName, engineType, note) ->
            val remote = existingRemote[pkgName]
            val localStatus = inspectLocalInstallState(engineType, remote?.version)
            if (remote != null) {
                remote.copy(
                    installState = localStatus.first,
                    verificationSummary = localStatus.second
                )
            } else {
                AndroidPackageSpec(
                    id = "pkg_${pkgName}_$abi",
                    programName = pkgName,
                    engineType = engineType,
                    version = localStatus.third ?: "غير متزامن",
                    abi = if (engineType == EngineType.PHPMYADMIN) "noarch (PHP)" else abi,
                    minAndroidApi = 24,
                    repositoryName = if (engineType == EngineType.PHPMYADMIN) "phpmyadmin.net" else "Termux Android Bionic ($abi)",
                    officialUrl = "",
                    sha256 = "",
                    downloadSizeBytes = 0L,
                    dependencies = emptyList(),
                    installState = localStatus.first,
                    verificationSummary = localStatus.second,
                    androidCompatibilityNote = "$note • يتطلب مزامنة المستودع أو استيراد حزمة محلية موثقة",
                    isAvailableForInstall = false
                )
            }
        }
        _packages.value = updated
    }

    /**
     * Queries official Android Bionic ARM64 repository + phpMyAdmin releases API for genuinely available versions,
     * sizes, SHA-256 checksums, and dependencies. Never lists a version as installable unless a real package exists.
     */
    suspend fun syncRepositories(): Result<List<AndroidPackageSpec>> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        val abi = ProcessProbe.getDeviceAbi()
        val debArch = mapDeviceAbiToDebianArch(abi)
        val targets = setOf("php", "python", "nodejs-lts", "nodejs", "mariadb", "postgresql")
        val parsedApt = mutableMapOf<String, AndroidPackageSpec>()

        try {
            val packagesUrl = "$termuxRepoBase/dists/stable/main/binary-$debArch/Packages.gz"
            val req = Request.Builder()
                .url(packagesUrl)
                .header("User-Agent", "AbotalalHosting-Android/1.0")
                .build()

            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("تعذر الوصول لفهرس مستودع $debArch: HTTP ${resp.code}")
                }
                val stream = resp.body?.byteStream() ?: throw IllegalStateException("فهرس الحزم فارغ")
                BufferedReader(InputStreamReader(GZIPInputStream(stream), Charsets.UTF_8)).use { reader ->
                    parseAptStanzas(reader, targets, abi, parsedApt)
                }
            }

            // Also query official phpMyAdmin release JSON
            val pmaSpec = fetchLatestPhpMyAdminSpec()

            val finalSpecs = buildList {
                listOf("php", "python", "nodejs-lts", "mariadb", "postgresql").forEach { key ->
                    val candidate = parsedApt[key] ?: if (key == "nodejs-lts") parsedApt["nodejs"] else null
                    if (candidate != null) {
                        val (st, summary, _) = inspectLocalInstallState(candidate.engineType, candidate.version)
                        add(candidate.copy(installState = st, verificationSummary = summary))
                    }
                }
                if (pmaSpec != null) {
                    val (st, summary, _) = inspectLocalInstallState(EngineType.PHPMYADMIN, pmaSpec.version)
                    add(pmaSpec.copy(installState = st, verificationSummary = summary))
                }
            }

            if (finalSpecs.isNotEmpty()) {
                _packages.value = finalSpecs
                val msg = "تمت مزامنة ${finalSpecs.size} حزمة متوافقة لمعمارية $abi ($debArch)"
                _lastSyncMessage.value = msg
                logDao.insert(AppLogEntity(level = "INFO", source = "PKG_MGR", message = msg))
                Result.success(finalSpecs)
            } else {
                val msg = "لم يُعثر على حزم متوافقة لمعمارية $abi في الفهرس الحالي"
                _lastSyncMessage.value = msg
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: Exception) {
            val msg = "تعذرت مزامنة المستودع الرسمي: ${e.message}"
            _lastSyncMessage.value = msg
            logDao.insert(AppLogEntity(level = "WARN", source = "PKG_MGR", message = msg))
            refreshLocalCatalogState()
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    private fun parseAptStanzas(
        reader: BufferedReader,
        targets: Set<String>,
        deviceAbi: String,
        outMap: MutableMap<String, AndroidPackageSpec>
    ) {
        val current = mutableMapOf<String, String>()
        var lastKey: String? = null

        fun flushStanza() {
            val pkg = current["Package"]?.trim() ?: return
            if (pkg !in targets) {
                current.clear()
                lastKey = null
                return
            }
            val version = current["Version"]?.trim() ?: ""
            val filename = current["Filename"]?.trim() ?: ""
            val sha = current["SHA256"]?.trim() ?: ""
            val size = current["Size"]?.trim()?.toLongOrNull() ?: 0L
            val deps = current["Depends"]?.split(',')
                ?.map { it.trim().substringBefore(" (").substringBefore("|").trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val arch = current["Architecture"]?.trim() ?: mapDeviceAbiToDebianArch(deviceAbi)

            val engineType = when (pkg) {
                "php" -> EngineType.PHP
                "python" -> EngineType.PYTHON
                "nodejs-lts", "nodejs" -> EngineType.NODEJS
                "mariadb" -> EngineType.MYSQL_MARIADB
                "postgresql" -> EngineType.POSTGRESQL
                else -> return
            }

            if (filename.isNotBlank() && sha.length == 64 && size > 0L) {
                outMap[pkg] = AndroidPackageSpec(
                    id = "apt_${pkg}_$deviceAbi",
                    programName = pkg,
                    engineType = engineType,
                    version = version,
                    abi = "$deviceAbi ($arch)",
                    minAndroidApi = 24,
                    repositoryName = "Termux Android Bionic ($arch)",
                    officialUrl = "$termuxRepoBase/$filename",
                    sha256 = sha,
                    downloadSizeBytes = size,
                    dependencies = deps,
                    installState = PackageInstallState.NOT_INSTALLED,
                    verificationSummary = "حزمة Android Bionic رسمية جاهزة للتنزيل والتحقق",
                    androidCompatibilityNote = "متوافقة مع Bionic libc ($deviceAbi) • API ${Build.VERSION.SDK_INT}",
                    isAvailableForInstall = true
                )
            }
            current.clear()
            lastKey = null
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) {
                flushStanza()
                continue
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (lastKey != null) {
                    current[lastKey!!] = (current[lastKey!!] ?: "") + "\n" + line.trim()
                }
            } else {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val valStr = line.substring(idx + 1).trim()
                    current[key] = valStr
                    lastKey = key
                }
            }
        }
        flushStanza()
    }

    private suspend fun fetchLatestPhpMyAdminSpec(): AndroidPackageSpec? {
        val textRes = downloader.fetchText(phpmyadminListUrl)
        val body = textRes.getOrNull() ?: return null
        return try {
            val json = JSONObject(body)
            val releases = json.optJSONArray("releases") ?: return null
            for (i in 0 until releases.length()) {
                val rel = releases.optJSONObject(i) ?: continue
                val version = rel.optString("version")
                val files = rel.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val f = files.optJSONObject(j) ?: continue
                    val name = f.optString("name")
                    if (name.endsWith("-all-languages.zip")) {
                        val url = f.optString("url")
                        val sha = f.optString("sha256")
                        val size = f.optLong("size", 0L)
                        if (url.startsWith("https://") && sha.length == 64) {
                            return AndroidPackageSpec(
                                id = "pma_$version",
                                programName = "phpmyadmin",
                                engineType = EngineType.PHPMYADMIN,
                                version = version,
                                abi = "noarch (PHP)",
                                minAndroidApi = 24,
                                repositoryName = "phpmyadmin.net Official",
                                officialUrl = url,
                                sha256 = sha,
                                downloadSizeBytes = size,
                                dependencies = listOf("php", "mysqli", "mbstring", "openssl", "mariadb"),
                                androidCompatibilityNote = "أرشيف PHP مستقل عن المعمارية • يتطلب PHP + MariaDB/MySQL",
                                isAvailableForInstall = true
                            )
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun installPackageFromUrl(
        spec: AndroidPackageSpec,
        overrideUrl: String? = null,
        overrideSha256: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetUrl = (overrideUrl?.trim().takeUnless { it.isNullOrBlank() } ?: spec.officialUrl).trim()
        val targetSha = (overrideSha256?.trim().takeUnless { it.isNullOrBlank() } ?: spec.sha256).trim()
        if (targetUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("لا يوجد رابط حزمة موثوق لهذا الإصدار. قم بمزامنة المستودع أو استورد حزمة محلية."))
        }

        val taskId = "task_${spec.id}_${UUID.randomUUID().toString().take(4)}"
        val ext = when {
            targetUrl.endsWith(".deb", true) -> ".deb"
            targetUrl.endsWith(".tar.xz", true) -> ".tar.xz"
            targetUrl.endsWith(".tar.gz", true) || targetUrl.endsWith(".tgz", true) -> ".tar.gz"
            else -> ".zip"
        }
        val cacheFile = File(context.cacheDir, "pkg_${spec.programName}$ext")

        val dlRes = downloader.downloadFileVerified(
            taskId = taskId,
            packageId = spec.id,
            title = "${spec.programName} ${spec.version}",
            url = targetUrl,
            expectedSha256 = targetSha,
            destFile = cacheFile
        )
        if (dlRes.isFailure) {
            return@withContext Result.failure(dlRes.exceptionOrNull() ?: IllegalStateException("فشل تنزيل الحزمة"))
        }

        unpackAndVerify(taskId, spec, cacheFile)
    }

    suspend fun installPackageFromUri(
        spec: AndroidPackageSpec,
        uri: Uri,
        optionalSha256: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val taskId = "local_${spec.id}_${UUID.randomUUID().toString().take(4)}"
        val tempFile = File(context.cacheDir, "local_${spec.programName}_${System.currentTimeMillis()}.pkg")
        return@withContext try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة ملف الحزمة المحلي"))

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = optionalSha256?.trim()?.lowercase().orEmpty()
            if (expected.isNotBlank() && actualSha != expected) {
                tempFile.delete()
                return@withContext Result.failure(SecurityException("فشل مطابقة SHA-256: الفعلي ${actualSha.take(12)}..."))
            }

            unpackAndVerify(taskId, spec, tempFile)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    private suspend fun unpackAndVerify(
        taskId: String,
        spec: AndroidPackageSpec,
        archiveFile: File
    ): Result<String> {
        val versionShort = normalizeMajorMinor(spec.engineType, spec.version)
        val runtimeDir = runtimeEngine.getRuntimeDir(spec.engineType, versionShort)

        downloader.updateInstallProgress(taskId, "جاري فك الحزمة وتجهيز المسارات...", 0.35f)
        val extractRes = ArchiveExtractor.extractArchive(archiveFile, runtimeDir) { p, fileName ->
            downloader.updateInstallProgress(taskId, "استخراج: $fileName", 0.35f + (p * 0.45f))
        }
        archiveFile.delete()

        if (extractRes.isFailure) {
            val err = extractRes.exceptionOrNull()?.message ?: "فشل فك الأرشيف"
            downloader.updateInstallProgress(taskId, "فشل فك الحزمة", 0f, isFinished = true, isVerified = false, errorDetails = err)
            return Result.failure(IllegalStateException(err))
        }

        // Write package metadata marker
        File(runtimeDir, ".abotalal_pkg.json").writeText(
            JSONObject().apply {
                put("programName", spec.programName)
                put("version", spec.version)
                put("abi", spec.abi)
                put("installedAt", System.currentTimeMillis())
                put("filesExtracted", extractRes.getOrThrow().extractedFiles)
            }.toString(2)
        )

        downloader.updateInstallProgress(taskId, "جاري التحقق الفعلي من التشغيل...", 0.9f)
        val (verified, detail) = verifyInstalledEngine(spec.engineType, versionShort, runtimeDir)

        downloader.updateInstallProgress(
            taskId = taskId,
            stageLabel = if (verified) "تم التثبيت والتحقق بنجاح" else "تم فك الملفات (تعذر التنفيذ)",
            installProgress = 1f,
            isFinished = true,
            isVerified = verified,
            errorDetails = if (verified) null else detail,
            verificationResult = detail
        )

        refreshLocalCatalogState()
        logDao.insert(
            AppLogEntity(
                level = if (verified) "INFO" else "WARN",
                source = "PKG_MGR",
                message = "[${spec.programName} ${spec.version}] verified=$verified — $detail"
            )
        )

        return if (verified) {
            Result.success(detail)
        } else {
            Result.failure(IllegalStateException(detail))
        }
    }

    private suspend fun verifyInstalledEngine(
        type: EngineType,
        versionShort: String,
        runtimeDir: File
    ): Pair<Boolean, String> {
        if (type == EngineType.PHPMYADMIN) {
            val hasIndex = File(runtimeDir, "index.php").exists() ||
                runtimeDir.listFiles()?.any { it.isDirectory && File(it, "index.php").exists() } == true
            return if (hasIndex) {
                true to "تم تثبيت ملفات phpMyAdmin بنجاح في ${runtimeDir.name}"
            } else {
                false to "لم يتم العثور على index.php داخل أرشيف phpMyAdmin"
            }
        }

        val candidates = when (type) {
            EngineType.PHP -> listOf("php-cgi", "php")
            EngineType.PYTHON -> listOf("python3", "python")
            EngineType.NODEJS -> listOf("node", "nodejs")
            EngineType.MYSQL_MARIADB -> listOf("mariadbd", "mysqld")
            EngineType.POSTGRESQL -> listOf("postgres")
            else -> emptyList()
        }
        val subDir = when (type) {
            EngineType.PHP -> "php/$versionShort"
            EngineType.PYTHON -> "python/$versionShort"
            EngineType.NODEJS -> "nodejs/$versionShort"
            EngineType.MYSQL_MARIADB -> "mariadb"
            EngineType.POSTGRESQL -> "postgresql"
            else -> type.name.lowercase(Locale.ROOT)
        }

        val bin = ProcessProbe.findBinary(context, candidates, subDir)
            ?: return false to "تم فك الحزمة لكن الملف التنفيذي (${candidates.joinToString()}) غير موجود في المسار المعزول"

        val env = runtimeEngine.buildEnvironment(type, versionShort)
        val flag = if (type == EngineType.PHP || type == EngineType.NODEJS) "-v" else "--version"
        val res = runtimeEngine.executeCommand(listOf(bin.absolutePath, flag), runtimeDir, env, 8L)
        return if (res.isSuccess && res.combinedOutput.isNotBlank()) {
            true to "اجتاز الاختبار التنفيذي: ${res.combinedOutput.lineSequence().first()}"
        } else {
            false to (res.stderr.ifBlank { "فشل تشغيل الملف الثنائي برمز ${res.exitCode}" })
        }
    }

    private fun inspectLocalInstallState(
        type: EngineType,
        remoteVersion: String?
    ): Triple<PackageInstallState, String, String?> {
        if (type == EngineType.PHPMYADMIN) {
            val dir = runtimeEngine.getRuntimeDir(EngineType.PHPMYADMIN, "")
            val hasIdx = File(dir, "index.php").exists()
            return if (hasIdx) {
                Triple(PackageInstallState.INSTALLED_UNVERIFIED, "ملفات phpMyAdmin موجودة (يتطلب اختبار PHP + MySQL)", remoteVersion ?: "5.2.x")
            } else {
                Triple(PackageInstallState.NOT_INSTALLED, "غير مثبت", remoteVersion)
            }
        }

        val candidates = when (type) {
            EngineType.PHP -> listOf("php-cgi", "php")
            EngineType.PYTHON -> listOf("python3", "python")
            EngineType.NODEJS -> listOf("node", "nodejs")
            EngineType.MYSQL_MARIADB -> listOf("mariadbd", "mysqld")
            EngineType.POSTGRESQL -> listOf("postgres")
            else -> emptyList()
        }
        val subDir = when (type) {
            EngineType.PHP -> "php"
            EngineType.PYTHON -> "python"
            EngineType.NODEJS -> "nodejs"
            EngineType.MYSQL_MARIADB -> "mariadb"
            EngineType.POSTGRESQL -> "postgresql"
            else -> type.name.lowercase(Locale.ROOT)
        }
        val bin = ProcessProbe.findBinary(context, candidates, subDir)
        if (bin == null) {
            return Triple(PackageInstallState.NOT_INSTALLED, "غير مثبت محلياً لمعمارية ${ProcessProbe.getDeviceAbi()}", remoteVersion)
        }
        val flag = if (type == EngineType.PHP || type == EngineType.NODEJS) "-v" else "--version"
        val (ok, line) = ProcessProbe.probeBinaryVersion(bin, flag)
        return if (ok) {
            Triple(PackageInstallState.VERIFIED, "مثبت ومتحقق منه: $line", line)
        } else {
            Triple(PackageInstallState.FAILED, "ملف موجود لكن تعذر تنفيذه: $line", remoteVersion)
        }
    }

    private fun normalizeMajorMinor(type: EngineType, rawVersion: String): String {
        val clean = rawVersion.substringBefore('-').substringBefore('+').trim()
        val parts = clean.split('.')
        return when (type) {
            EngineType.PHP, EngineType.PYTHON -> if (parts.size >= 2) "${parts[0]}.${parts[1]}" else clean
            EngineType.NODEJS -> parts.firstOrNull() ?: clean
            else -> clean
        }
    }
}
