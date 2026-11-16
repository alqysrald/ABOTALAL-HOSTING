package com.example.engine.http

import android.content.Context
import com.example.core.database.AppLogDao
import com.example.core.database.AppLogEntity
import com.example.core.database.DomainDao
import com.example.core.database.SiteDao
import com.example.core.database.SiteEntity
import com.example.core.model.EngineInfo
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.core.model.ProjectType
import com.example.core.security.SecurityHelper
import com.example.engine.core.ProcessProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class EmbeddedHttpServer(
    private val context: Context,
    private val logDao: AppLogDao,
    private val domainDao: DomainDao? = null,
    private val siteDao: SiteDao? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newCachedThreadPool()
    private val activeSockets = ConcurrentHashMap<String, ServerSocket>()
    private val runningSitesMap = ConcurrentHashMap<String, SiteEntity>()
    private val activeConnectionsCounter = AtomicInteger(0)

    private val _runningSiteIds = MutableStateFlow<Set<String>>(emptySet())
    val runningSiteIds: StateFlow<Set<String>> = _runningSiteIds.asStateFlow()

    private val _activeConnections = MutableStateFlow(0)
    val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

    fun getHttpEngineStatus(): EngineInfo {
        val runningCount = activeSockets.size
        val verified = runningSitesMap.values.all {
            ProcessProbe.isTcpPortListening("127.0.0.1", it.port, 250)
        }
        val status = when {
            runningCount > 0 && verified -> EngineStatus.RUNNING
            runningCount > 0 && !verified -> EngineStatus.ERROR
            else -> EngineStatus.STOPPED
        }
        val activePorts = runningSitesMap.values.joinToString(", ") { it.port.toString() }
        return EngineInfo(
            type = EngineType.HTTP_SERVER,
            status = status,
            activeVersion = "Abotalal HTTP/1.1 + VirtualHost + .htaccess Rewrite",
            availableVersions = listOf("1.1"),
            port = runningSitesMap.values.firstOrNull()?.port ?: 8080,
            binaryPath = "مدمج في التطبيق (java.net.ServerSocket)",
            architecture = ProcessProbe.getDeviceAbi(),
            verificationDetails = if (runningCount > 0) {
                "يعمل فعلياً على $runningCount موقع/منافذ: [$activePorts] (مع توجيه الدومينات و .htaccess)"
            } else {
                "جاهز للتشغيل دون إنترنت (يدعم النطاقات المحلية وإعادة كتابة الروابط .htaccess)"
            },
            requirementNotice = "محرك خادم الويب المدمج يدعم HTTP/1.1 والملفات الثابتة والنطاقات الافتراضية وإعادة كتابة الروابط .htaccess، ويرتبط بـ PHP/Python/Node.js.",
            lastCheckedAt = System.currentTimeMillis()
        )
    }

    fun startSite(site: SiteEntity): Result<String> {
        if (activeSockets.containsKey(site.id)) {
            return Result.success("الموقع يعمل بالفعل على المنفذ ${site.port}")
        }
        val portConflict = runningSitesMap.values.firstOrNull { it.port == site.port }
        if (portConflict != null) {
            return Result.failure(IllegalStateException("المنفذ ${site.port} مستخدم حالياً بواسطة ${portConflict.name}"))
        }
        val rootDir = File(site.rootFolder)
        if (!rootDir.exists()) rootDir.mkdirs()

        return try {
            val bindHost = if (site.allowLanAccess) "0.0.0.0" else "127.0.0.1"
            val serverSocket = ServerSocket(site.port, 64, InetAddress.getByName(bindHost))
            serverSocket.reuseAddress = true

            if (!ProcessProbe.isTcpPortListening("127.0.0.1", site.port, 500)) {
                serverSocket.close()
                return Result.failure(IllegalStateException("تعذر التحقق من استماع الخادم على المنفذ ${site.port}"))
            }

            activeSockets[site.id] = serverSocket
            runningSitesMap[site.id] = site
            _runningSiteIds.value = activeSockets.keys.toSet()

            executor.submit { acceptLoop(site, serverSocket) }
            logEvent("INFO", "SITE:${site.name}", "تم تشغيل الخادم بنجاح محلياً على http://$bindHost:${site.port}")
            Result.success("http://127.0.0.1:${site.port}")
        } catch (e: Exception) {
            logEvent("ERROR", "SITE:${site.name}", "فشل تشغيل المنفذ ${site.port}: ${e.message}")
            Result.failure(e)
        }
    }

    fun stopSite(siteId: String) {
        val site = runningSitesMap.remove(siteId)
        val socket = activeSockets.remove(siteId)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        _runningSiteIds.value = activeSockets.keys.toSet()
        if (site != null) {
            logEvent("INFO", "SITE:${site.name}", "تم إيقاف الموقع وتحرير المنفذ ${site.port}")
        }
    }

    fun stopAll() {
        val ids = activeSockets.keys.toList()
        ids.forEach { stopSite(it) }
    }

    private fun acceptLoop(site: SiteEntity, serverSocket: ServerSocket) {
        while (!serverSocket.isClosed && activeSockets.containsKey(site.id)) {
            try {
                val client = serverSocket.accept()
                executor.submit { handleClient(site, client) }
            } catch (_: Exception) {
                if (serverSocket.isClosed) break
            }
        }
    }

    private fun handleClient(defaultSite: SiteEntity, socket: Socket) {
        val currentCount = activeConnectionsCounter.incrementAndGet()
        _activeConnections.value = currentCount
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLineAscii(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendHtmlResponse(output, 400, "Bad Request", "<h1>400 طلب غير صالح</h1>")
                return
            }

            val method = parts[0].uppercase(Locale.ROOT)
            val rawTarget = parts[1]
            val queryIdx = rawTarget.indexOf('?')
            val rawPath = if (queryIdx >= 0) rawTarget.substring(0, queryIdx) else rawTarget
            var queryString = if (queryIdx >= 0) rawTarget.substring(queryIdx + 1) else ""

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLineAscii(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                        line.substring(colon + 1).trim()
                }
            }

            // Virtual Host resolution via Host header
            val hostHeader = headers["host"]?.substringBefore(':')?.trim()?.lowercase(Locale.ROOT)
            var activeSite = defaultSite
            var siteRoot = File(defaultSite.rootFolder).canonicalFile

            if (!hostHeader.isNullOrBlank() && domainDao != null) {
                val matchedDomain = runBlocking { domainDao.getByName(hostHeader) }
                if (matchedDomain != null) {
                    val linkedSite = siteDao?.let { runBlocking { it.getSiteById(matchedDomain.siteId) } }
                    if (linkedSite != null) {
                        activeSite = linkedSite
                    }
                    val docRoot = File(matchedDomain.documentRoot).canonicalFile
                    if (docRoot.exists() && docRoot.isDirectory) {
                        siteRoot = docRoot
                    }
                }
            }

            val decodedPath = try {
                URLDecoder.decode(rawPath, "UTF-8")
            } catch (_: Exception) {
                rawPath
            }

            if (decodedPath.contains("..")) {
                logEvent("SECURITY", "SITE:${activeSite.name}", "تم حظر محاولة اختراق مسار (Path Traversal): $decodedPath")
                sendHtmlResponse(output, 403, "Forbidden", buildErrorPage(403, "تم حظر الوصول لأسباب أمنية"))
                return
            }

            val normalizedRel = decodedPath.trimStart('/')
            var targetFile = if (normalizedRel.isEmpty()) {
                resolveDefaultEntry(siteRoot, activeSite)
            } else {
                SecurityHelper.resolveSafeChild(siteRoot, normalizedRel)
            }

            if (targetFile == null) {
                logEvent("SECURITY", "SITE:${activeSite.name}", "محاولة وصول خارج المجلد المعزول: $decodedPath")
                sendHtmlResponse(output, 403, "Forbidden", buildErrorPage(403, "وصول مرفوض خارج المجلد المعزول"))
                return
            }

            // .htaccess URL Rewrite fallback when physical file doesn't exist
            if (!targetFile.exists() && normalizedRel.isNotEmpty()) {
                val rewritten = applyHtaccessRewrite(siteRoot, normalizedRel, queryString)
                if (rewritten != null) {
                    val (newRel, newQuery) = rewritten
                    queryString = newQuery
                    targetFile = SecurityHelper.resolveSafeChild(siteRoot, newRel) ?: targetFile
                }
            }

            if (activeSite.projectType.startsWith("PYTHON_")) {
                handlePythonProjectRequest(activeSite, targetFile, output)
                return
            }

            if (activeSite.projectType == ProjectType.NODE_SOCKETIO.name) {
                handleNodeProjectRequest(activeSite, targetFile, output)
                return
            }

            val resolvedFile = if (targetFile.isDirectory) {
                val indexHtml = File(targetFile, "index.html")
                val indexPhp = File(targetFile, "index.php")
                when {
                    indexHtml.exists() -> indexHtml
                    indexPhp.exists() -> indexPhp
                    activeSite.directoryListingEnabled -> {
                        val listingHtml = buildDirectoryListingHtml(activeSite.name, decodedPath, targetFile)
                        sendHtmlResponse(output, 200, "OK", listingHtml)
                        return
                    }
                    else -> {
                        sendHtmlResponse(output, 403, "Forbidden", buildErrorPage(403, "استعراض المجلد معطل"))
                        return
                    }
                }
            } else {
                targetFile
            }

            if (!resolvedFile.exists()) {
                logEvent("WARN", "SITE:${activeSite.name}", "404 ملف غير موجود: $decodedPath")
                sendHtmlResponse(output, 404, "Not Found", buildErrorPage(404, "الملف المطلوب غير موجود: $decodedPath"))
                return
            }

            if (resolvedFile.extension.equals("php", ignoreCase = true)) {
                handlePhpRequest(activeSite, siteRoot, resolvedFile, method, queryString, headers, input, output)
                return
            }

            serveStaticFile(resolvedFile, method == "HEAD", output)
        } catch (e: Exception) {
            logEvent("ERROR", "SITE:${defaultSite.name}", "خطأ معالجة طلب HTTP: ${e.message}")
        } finally {
            val remaining = activeConnectionsCounter.decrementAndGet().coerceAtLeast(0)
            _activeConnections.value = remaining
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Parses Apache .htaccess RewriteRule directives (supporting Sngine, WoWonder, Laravel, WordPress clean URLs).
     */
    private fun applyHtaccessRewrite(siteRoot: File, relPath: String, existingQuery: String): Pair<String, String>? {
        val htaccess = File(siteRoot, ".htaccess")
        if (!htaccess.exists() || !htaccess.isFile) return null
        val lines = htaccess.readLines()
        var engineOn = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.equals("RewriteEngine On", ignoreCase = true)) {
                engineOn = true
                continue
            }
            if (!engineOn) continue
            if (line.startsWith("RewriteRule", ignoreCase = true)) {
                val tokens = line.split(Regex("\\s+"))
                if (tokens.size >= 3) {
                    val pattern = tokens[1]
                    val target = tokens[2]
                    try {
                        val regex = Regex(pattern)
                        val match = regex.find(relPath)
                        if (match != null) {
                            var rewrittenTarget = target
                            match.groupValues.forEachIndexed { idx, groupVal ->
                                rewrittenTarget = rewrittenTarget.replace("$$idx", groupVal)
                            }
                            val qIdx = rewrittenTarget.indexOf('?')
                            val newPath = if (qIdx >= 0) rewrittenTarget.substring(0, qIdx) else rewrittenTarget
                            val ruleQuery = if (qIdx >= 0) rewrittenTarget.substring(qIdx + 1) else ""
                            val mergedQuery = listOf(ruleQuery, existingQuery).filter { it.isNotBlank() }.joinToString("&")
                            return newPath.trimStart('/') to mergedQuery
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        // Fallback front controller if RewriteEngine On and index.php exists
        if (engineOn && File(siteRoot, "index.php").exists()) {
            return "index.php" to existingQuery
        }
        return null
    }

    private fun resolveDefaultEntry(siteRoot: File, site: SiteEntity): File {
        val customEntry = File(siteRoot, site.entryFile)
        if (customEntry.exists()) return customEntry
        val indexHtml = File(siteRoot, "index.html")
        if (indexHtml.exists()) return indexHtml
        val indexPhp = File(siteRoot, "index.php")
        if (indexPhp.exists()) return indexPhp
        return siteRoot
    }

    private fun handlePhpRequest(
        site: SiteEntity,
        siteRoot: File,
        phpFile: File,
        method: String,
        queryString: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        val phpBinary = ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php/${site.runtimeVersion}")
            ?: ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php")

        if (phpBinary == null) {
            val msg = "محرك PHP (الإصدار ${site.runtimeVersion}) غير مثبت على الجهاز. يتطلب تنفيذ ملفات .php وجود حزمة php-cgi متوافقة مع معمارية ${ProcessProbe.getDeviceAbi()}."
            logEvent("ERROR", "SITE:${site.name}", msg)
            sendHtmlResponse(output, 503, "Service Unavailable", buildErrorPage(503, msg))
            return
        }

        try {
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength in 1..(4 * 1024 * 1024)) {
                ByteArray(contentLength).also { buf ->
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = input.read(buf, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                }
            } else {
                ByteArray(0)
            }

            val iniFile = File(context.filesDir, "runtimes/php/${site.runtimeVersion}/php.ini")
            val command = mutableListOf(phpBinary.absolutePath)
            if (iniFile.exists()) {
                command.add("-c")
                command.add(iniFile.absolutePath)
            }
            if (phpBinary.name == "php") {
                command.add("-f")
                command.add(phpFile.absolutePath)
            }

            val pb = ProcessBuilder(command)
            pb.directory(siteRoot)
            pb.environment().apply {
                put("GATEWAY_INTERFACE", "CGI/1.1")
                put("SERVER_PROTOCOL", "HTTP/1.1")
                put("REQUEST_METHOD", method)
                put("QUERY_STRING", queryString)
                put("SCRIPT_FILENAME", phpFile.absolutePath)
                put("SCRIPT_NAME", "/${phpFile.name}")
                put("DOCUMENT_ROOT", siteRoot.absolutePath)
                put("REDIRECT_STATUS", "200")
                put("CONTENT_LENGTH", bodyBytes.size.toString())
                headers["content-type"]?.let { put("CONTENT_TYPE", it) }
            }
            val process = pb.start()
            if (bodyBytes.isNotEmpty()) {
                process.outputStream.write(bodyBytes)
                process.outputStream.flush()
            }
            process.outputStream.close()

            val stdout = process.inputStream.readBytes()
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor()

            if (stderr.isNotBlank()) {
                logEvent("ERROR", "SITE:${site.name}", "PHP stderr: $stderr")
            }
            val outText = stdout.toString(Charsets.UTF_8)
            val separatorIdx = outText.indexOf("\r\n\r\n")
            val body = if (phpBinary.name.contains("cgi") && separatorIdx >= 0) {
                outText.substring(separatorIdx + 4)
            } else {
                outText
            }
            sendHtmlResponse(output, 200, "OK", body)
        } catch (e: Exception) {
            logEvent("ERROR", "SITE:${site.name}", "فشل تنفيذ PHP: ${e.message}")
            sendHtmlResponse(output, 500, "Internal Server Error", buildErrorPage(500, "خطأ في تنفيذ PHP: ${e.message}"))
        }
    }

    private fun handlePythonProjectRequest(site: SiteEntity, targetFile: File, output: OutputStream) {
        if (targetFile.isFile && targetFile.extension.lowercase(Locale.ROOT) in setOf("html", "css", "js", "png", "jpg", "svg", "json")) {
            serveStaticFile(targetFile, false, output)
            return
        }
        val pythonBin = ProcessProbe.findBinary(context, listOf("python3", "python"), "python/${site.runtimeVersion}")
            ?: ProcessProbe.findBinary(context, listOf("python3", "python"), "python")
        val typeLabel = ProjectType.entries.firstOrNull { it.name == site.projectType }?.titleAr ?: "Python"
        val message = if (pythonBin == null) {
            "بيئة تشغيل $typeLabel (Python ${site.runtimeVersion}) غير مثبتة على هذا الجهاز (${ProcessProbe.getDeviceAbi()})."
        } else {
            "تم العثور على مفسر Python (${pythonBin.name})، يرجى التأكد من تثبيت متطلبات المشروع (${site.entryFile})."
        }
        logEvent("WARN", "SITE:${site.name}", message)
        sendHtmlResponse(output, 503, "Service Unavailable", buildErrorPage(503, message))
    }

    private fun handleNodeProjectRequest(site: SiteEntity, targetFile: File, output: OutputStream) {
        if (targetFile.isFile && targetFile.extension.lowercase(Locale.ROOT) in setOf("html", "css", "js", "png", "jpg", "svg", "json")) {
            serveStaticFile(targetFile, false, output)
            return
        }
        val nodeBin = ProcessProbe.findBinary(context, listOf("node", "nodejs"), "nodejs/${site.runtimeVersion}")
            ?: ProcessProbe.findBinary(context, listOf("node", "nodejs"), "nodejs")
        val message = if (nodeBin == null) {
            "بيئة Node.js / Socket.io (الإصدار ${site.runtimeVersion}) غير مثبتة على هذا الجهاز (${ProcessProbe.getDeviceAbi()})."
        } else {
            "تم العثور على محرك Node.js (${nodeBin.name})، يرجى تشغيل خدمة ${site.entryFile} للاتصالات الفورية."
        }
        logEvent("WARN", "SITE:${site.name}", message)
        sendHtmlResponse(output, 503, "Service Unavailable", buildErrorPage(503, message))
    }

    private fun serveStaticFile(file: File, headOnly: Boolean, output: OutputStream) {
        val mime = guessMimeType(file.extension)
        val bytes = if (headOnly) ByteArray(0) else file.readBytes()
        val length = file.length()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Server: Abotalal-HTTP/1.1\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $length\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: SAMEORIGIN\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (!headOnly && bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun sendHtmlResponse(output: OutputStream, statusCode: Int, statusText: String, html: String) {
        val bodyBytes = html.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Server: Abotalal-HTTP/1.1\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun buildDirectoryListingHtml(siteName: String, requestPath: String, dir: File): String {
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val rows = items.joinToString("") { f ->
            val slash = if (f.isDirectory) "/" else ""
            val sizeText = if (f.isDirectory) "مجلد" else "${f.length()} بايت"
            val href = (requestPath.trimEnd('/') + "/" + f.name + slash).replace("//", "/")
            "<tr><td><a href=\"$href\">${f.name}$slash</a></td><td>$sizeText</td></tr>"
        }
        return """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <title>فهرس $siteName</title>
              <style>
                body{font-family:sans-serif;background:#f4f7fc;color:#0b1c33;padding:24px;margin:0}
                .card{max-width:780px;margin:auto;background:#fff;border-radius:14px;padding:20px;box-shadow:0 6px 20px rgba(13,71,161,.08);border-top:4px solid #0d47a1}
                h2{color:#0d47a1;margin-top:0}
                table{width:100%;border-collapse:collapse}
                th,td{padding:10px;border-bottom:1px solid #e2eaf6;text-align:right}
                a{color:#1565c0;text-decoration:none;font-weight:bold}
              </style>
            </head>
            <body>
              <div class="card">
                <h2>استضافة أبوطلال — فهرس: $requestPath</h2>
                <table><thead><tr><th>الاسم</th><th>الحجم</th></tr></thead><tbody>$rows</tbody></table>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildErrorPage(code: Int, details: String): String = """
        <!DOCTYPE html>
        <html lang="ar" dir="rtl">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width,initial-scale=1"/>
          <title>خطأ $code — استضافة أبوطلال</title>
          <style>
            body{font-family:sans-serif;background:#0a2842;color:#fff;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:16px}
            .box{max-width:560px;background:#fff;color:#0b1c33;border-radius:16px;padding:28px;border-top:6px solid #c62828}
            .code{font-size:40px;font-weight:900;color:#c62828;margin:0}
            .brand{font-size:12px;color:#0d47a1;font-weight:bold;letter-spacing:1px}
          </style>
        </head>
        <body>
          <div class="box">
            <div class="brand">ABOTALAL HOSTING — استضافة أبوطلال</div>
            <p class="code">$code</p>
            <p>$details</p>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun readLineAscii(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
            b = input.read()
        }
        return baos.toString(Charsets.US_ASCII.name())
    }

    private fun guessMimeType(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "txt", "log", "ini", "py", "sql" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    private fun logEvent(level: String, source: String, message: String) {
        scope.launch {
            logDao.insert(AppLogEntity(level = level, source = source, message = message))
            try {
                val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
                val safeName = source.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(logsDir, "$safeName.log").appendText("[$stamp] [$level] $message\n")
            } catch (_: Exception) {
            }
        }
    }
}
