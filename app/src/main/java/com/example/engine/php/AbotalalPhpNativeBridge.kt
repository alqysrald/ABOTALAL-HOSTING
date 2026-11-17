package com.example.engine.php

import android.content.Context
import android.os.Build
import java.io.File

data class PhpNativeHttpResponse(
    val success: Boolean,
    val statusCode: Int,
    val headersText: String,
    val bodyBytes: ByteArray,
    val stderrLog: String,
    val errorMessage: String
) {
    fun parsedHeaderLines(): List<Pair<String, String>> =
        headersText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(':') }
            .map { line ->
                val idx = line.indexOf(':')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toList()
}

data class BundledPhpVersionSpec(
    val series: String,
    val fullVersion: String,
    val abi: String,
    val minAndroidApi: Int,
    val coreLibrary: String,
    val adapterLibrary: String,
    val extensions: List<String>,
    val isNativePresentOnDevice: Boolean,
    val isLoadableViaJni: Boolean
)

/**
 * Clean JNI Bridge separating:
 * - Java/Kotlin HTTP Server (EmbeddedHttpServer)
 * - JNI Bridge (libabotalal_php_jni.so)
 * - Versioned PHP Embedded SAPI Engines (libphp_<ver>.so + libabotalal_php_<ver>.so)
 */
object AbotalalPhpNativeBridge {

    @Volatile
    private var jniLibLoaded: Boolean = false

    @Volatile
    private var jniLoadError: String? = null

    private fun ensureJniLoaded(): Boolean {
        if (jniLibLoaded) return true
        return synchronized(this) {
            if (jniLibLoaded) return@synchronized true
            try {
                System.loadLibrary("abotalal_php_jni")
                jniLibLoaded = true
                jniLoadError = null
                true
            } catch (t: Throwable) {
                jniLoadError = t.message ?: t::class.java.simpleName
                false
            }
        }
    }

    fun getJniLoadError(): String? = jniLoadError

    fun versionTag(seriesOrVersion: String): String {
        val parts = seriesOrVersion.trim().split('.')
        return if (parts.size >= 2) "${parts[0]}_${parts[1]}" else seriesOrVersion.replace('.', '_')
    }

    fun findNativeLibsForVersion(context: Context, version: String): Pair<File, File>? {
        val nativeDir = context.applicationInfo?.nativeLibraryDir?.let { File(it) } ?: return null
        if (!nativeDir.exists()) return null
        val tag = versionTag(version)
        val core = File(nativeDir, "libphp_$tag.so")
        val adapter = File(nativeDir, "libabotalal_php_$tag.so")
        return if (core.exists() && adapter.exists()) core to adapter else null
    }

    fun listInstalledNativeVersions(context: Context): List<String> {
        val nativeDir = context.applicationInfo?.nativeLibraryDir?.let { File(it) } ?: return emptyList()
        if (!nativeDir.exists() || !nativeDir.isDirectory) return emptyList()
        val pattern = Regex("""^libabotalal_php_(\d+)_(\d+)\.so$""")
        return nativeDir.listFiles()
            ?.mapNotNull { f ->
                val match = pattern.matchEntire(f.name) ?: return@mapNotNull null
                val ver = "${match.groupValues[1]}.${match.groupValues[2]}"
                val core = File(nativeDir, "libphp_${match.groupValues[1]}_${match.groupValues[2]}.so")
                if (core.exists() && f.exists()) ver else null
            }
            ?.sortedDescending()
            .orEmpty()
    }

    fun inspectVersionManifests(context: Context): List<BundledPhpVersionSpec> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val assetDir = "runtimes/php"
        val files = runCatching { context.assets.list(assetDir)?.toList().orEmpty() }.getOrDefault(emptyList())
        return files.filter { it.endsWith(".json") }.mapNotNull { fileName ->
            runCatching {
                val jsonText = context.assets.open("$assetDir/$fileName").bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(jsonText)
                val series = obj.getString("series")
                val fullVer = obj.getString("version")
                val manifestAbi = obj.getString("abi")
                val minApi = obj.optInt("minAndroidApi", 24)
                val coreLib = obj.getString("coreLibrary")
                val adapterLib = obj.getString("adapterLibrary")
                val extArr = obj.optJSONArray("extensions")
                val exts = buildList {
                    if (extArr != null) {
                        for (i in 0 until extArr.length()) add(extArr.getString(i))
                    }
                }
                val present = findNativeLibsForVersion(context, series) != null &&
                    Build.SUPPORTED_ABIS.any { it.equals(manifestAbi, ignoreCase = true) }
                val loadable = present && isEngineAvailable(context, series)
                BundledPhpVersionSpec(
                    series = series,
                    fullVersion = fullVer,
                    abi = manifestAbi,
                    minAndroidApi = minApi,
                    coreLibrary = coreLib,
                    adapterLibrary = adapterLib,
                    extensions = exts,
                    isNativePresentOnDevice = present,
                    isLoadableViaJni = loadable
                )
            }.getOrNull()
        }.sortedByDescending { it.series }
    }

    fun isEngineAvailable(context: Context, version: String): Boolean {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return false
        if (findNativeLibsForVersion(context, version) == null) return false
        if (!ensureJniLoaded()) return false
        return runCatching { nativeIsEngineLoadable(nativeDir, versionTag(version)) }.getOrDefault(false)
    }

    fun startEngine(context: Context, version: String, phpIniPath: String?): Boolean {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return false
        if (!ensureJniLoaded()) return false
        return runCatching { nativeStartEngine(nativeDir, versionTag(version), phpIniPath) }.getOrDefault(false)
    }

    fun stopEngine(context: Context, version: String) {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return
        if (!ensureJniLoaded()) return
        runCatching { nativeStopEngine(nativeDir, versionTag(version)) }
    }

    fun isEngineRunning(context: Context, version: String): Boolean {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return false
        if (!jniLibLoaded) return false
        return runCatching { nativeIsEngineRunning(nativeDir, versionTag(version)) }.getOrDefault(false)
    }

    fun getPhpVersionString(context: Context, version: String): String {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return ""
        if (!ensureJniLoaded()) return ""
        return runCatching { nativeGetPhpVersion(nativeDir, versionTag(version)) }.getOrDefault("")
    }

    fun getLoadedModules(context: Context, version: String): List<String> {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return emptyList()
        if (!ensureJniLoaded()) return emptyList()
        val csv = runCatching { nativeGetLoadedModules(nativeDir, versionTag(version)) }.getOrDefault("")
        return csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun executePhpScript(
        context: Context,
        version: String,
        scriptFile: File,
        documentRoot: File,
        phpIniPath: String?,
        sessionSaveDir: File,
        uploadTmpDir: File,
        method: String,
        requestUri: String,
        queryString: String,
        scriptName: String,
        httpHost: String,
        serverPort: Int,
        remoteAddr: String = "127.0.0.1",
        contentType: String? = null,
        cookieHeader: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        postBody: ByteArray = ByteArray(0)
    ): PhpNativeHttpResponse {
        val nativeDir = context.applicationInfo?.nativeLibraryDir
            ?: return PhpNativeHttpResponse(false, 503, "", ByteArray(0), "", "nativeLibraryDir is null")
        if (!ensureJniLoaded()) {
            return PhpNativeHttpResponse(
                false,
                503,
                "",
                ByteArray(0),
                "",
                "Failed to load libabotalal_php_jni.so: ${jniLoadError ?: "unknown"}"
            )
        }
        val extraText = extraHeaders.entries.joinToString("\n") { (k, v) -> "$k=$v" }
        sessionSaveDir.mkdirs()
        uploadTmpDir.mkdirs()

        return nativeExecuteRequest(
            nativeLibDir = nativeDir,
            versionTag = versionTag(version),
            scriptPath = scriptFile.absolutePath,
            documentRoot = documentRoot.absolutePath,
            phpIniPath = phpIniPath,
            sessionSavePath = sessionSaveDir.absolutePath,
            uploadTmpDir = uploadTmpDir.absolutePath,
            method = method,
            requestUri = requestUri,
            queryString = queryString,
            scriptName = scriptName,
            httpHost = httpHost,
            serverPort = serverPort,
            remoteAddr = remoteAddr,
            contentType = contentType,
            cookieHeader = cookieHeader,
            extraHeaders = extraText,
            postBody = postBody
        )
    }

    private external fun nativeIsEngineLoadable(nativeLibDir: String, versionTag: String): Boolean
    private external fun nativeStartEngine(nativeLibDir: String, versionTag: String, phpIniPath: String?): Boolean
    private external fun nativeStopEngine(nativeLibDir: String, versionTag: String)
    private external fun nativeIsEngineRunning(nativeLibDir: String, versionTag: String): Boolean
    private external fun nativeGetPhpVersion(nativeLibDir: String, versionTag: String): String
    private external fun nativeGetLoadedModules(nativeLibDir: String, versionTag: String): String
    private external fun nativeExecuteRequest(
        nativeLibDir: String,
        versionTag: String,
        scriptPath: String,
        documentRoot: String,
        phpIniPath: String?,
        sessionSavePath: String,
        uploadTmpDir: String,
        method: String,
        requestUri: String,
        queryString: String,
        scriptName: String,
        httpHost: String,
        serverPort: Int,
        remoteAddr: String,
        contentType: String?,
        cookieHeader: String?,
        extraHeaders: String,
        postBody: ByteArray
    ): PhpNativeHttpResponse
}
