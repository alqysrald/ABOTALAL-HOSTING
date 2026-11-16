package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SiteEntity
import com.example.core.model.DbEngineType
import com.example.core.security.SecurityHelper
import com.example.engine.core.ArchiveExtractor
import com.example.engine.core.PackageDownloader
import com.example.engine.core.RuntimeEngine
import com.example.engine.db.DatabaseManager
import com.example.engine.db.PhpMyAdminManager
import com.example.engine.domain.DomainManager
import com.example.engine.http.EmbeddedHttpServer
import com.example.engine.installer.ProjectInstaller
import com.example.engine.runtime.PhpRuntimeManager
import com.example.engine.runtime.PythonRuntimeManager
import com.example.files.IsolatedFileManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `app name matches Abotalal Hosting Arabic title`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("استضافة أبوطلال", appName)
    }

    @Test
    fun `security helper hashes secrets and blocks path traversal`() {
        val hash = SecurityHelper.hashSecret("1234")
        assertTrue(SecurityHelper.verifySecret("1234", hash))
        assertFalse(SecurityHelper.verifySecret("9999", hash))

        val root = File("/tmp/abotalal_test_root")
        assertNotNull(SecurityHelper.resolveSafeChild(root, "public/index.html"))
        assertNull(SecurityHelper.resolveSafeChild(root, "../../etc/passwd"))
    }

    @Test
    fun `embedded http server serves real site, virtual host, htaccess rewrite, and enforces isolation`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val domainManager = DomainManager(context, db.domainDao(), db.appLogDao())
        val server = EmbeddedHttpServer(context, db.appLogDao(), db.domainDao(), db.siteDao())

        val siteDir = File(context.filesDir, "test_site_vh").apply {
            deleteRecursively()
            mkdirs()
        }
        val publicDir = File(siteDir, "public").apply { mkdirs() }
        File(publicDir, "index.html").writeText("<h1>Abotalal VirtualHost Live</h1>")
        File(publicDir, "rewritten.html").writeText("<h1>Rewritten OK</h1>")
        File(publicDir, ".htaccess").writeText("RewriteEngine On\nRewriteRule ^profile/(.+)$ rewritten.html [L]\n")

        val port = 18091
        val site = SiteEntity(
            id = "test-site-vh",
            name = "TestVH",
            rootFolder = siteDir.absolutePath,
            port = port,
            projectType = "STATIC_HTML",
            runtimeVersion = "HTML5",
            entryFile = "index.html",
            allowLanAccess = false
        )
        db.siteDao().upsertSite(site)
        val domRes = domainManager.addOrUpdateDomain(
            rawDomain = "project.test",
            site = site,
            customDocumentRoot = "public",
            dnsARecord = "127.0.0.1",
            dnsCnameRecord = "",
            dnsTxtRecord = "",
            sslEnabled = false
        )
        assertTrue(domRes.isSuccess)
        assertFalse(domRes.getOrThrow().dnsResolvedGlobally)

        val startRes = server.startSite(site)
        assertTrue(startRes.isSuccess)
        try {
            val (homeCode, homeBody) = sendRawHttpGet(port, "/", "project.test")
            assertEquals(200, homeCode)
            assertTrue(homeBody.contains("Abotalal VirtualHost Live"))

            val (rewriteCode, rewriteBody) = sendRawHttpGet(port, "/profile/ahmed", "project.test")
            assertEquals(200, rewriteCode)
            assertTrue(rewriteBody.contains("Rewritten OK"))

            val (traversalCode, _) = sendRawHttpGet(port, "/../../etc/passwd", "project.test")
            assertEquals(403, traversalCode)
        } finally {
            server.stopAll()
            siteDir.deleteRecursively()
        }
    }

    private fun sendRawHttpGet(port: Int, path: String, host: String): Pair<Int, String> {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val raw = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            val statusLine = raw.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            val body = raw.substringAfter("\r\n\r\n", "")
            return code to body
        }
    }

    @Test
    fun `project installer detects generic PHP web project requirements and safe permissions`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val fileManager = IsolatedFileManager(context, db.appLogDao())
        val server = EmbeddedHttpServer(context, db.appLogDao())
        val installer = ProjectInstaller(context, fileManager, server, db.projectInstallDao(), db.appLogDao())

        val projDir = File(context.filesDir, "generic_php_project_scan").apply {
            deleteRecursively()
            mkdirs()
        }
        val publicHtml = File(projDir, "public_html").apply { mkdirs() }
        File(publicHtml, "index.php").writeText("<?php echo 'hello';")
        File(publicHtml, "includes").mkdirs()
        File(publicHtml, "includes/config.php").writeText("<?php define('DB_HOST', '127.0.0.1'); // mysqli curl_init gd")
        File(publicHtml, "content/uploads").mkdirs()
        File(publicHtml, ".htaccess").writeText("RewriteEngine On\nRewriteRule ^([a-z]+)$ index.php?view=$1 [L]\n")
        File(publicHtml, "composer.json").writeText("""{"require":{"php":"^8.2","ext-mysqli":"*","ext-gd":"*","ext-mbstring":"*"}}""")
        File(publicHtml, "package.json").writeText("""{"dependencies":{"socket.io":"^4.7.0"}}""")

        val report = installer.analyzeProjectDirectory(projDir, null)
        assertEquals("PHP", report.primaryLanguage)
        assertTrue(report.hasComposerJson)
        assertTrue(report.hasPackageJson)
        assertTrue(report.hasHtaccess)
        assertTrue(report.requiresSocketIo)
        assertTrue(report.requiredPhpExtensions.contains("mysqli"))
        assertTrue(report.requiredPhpExtensions.contains("gd"))
        assertTrue(report.requiredPhpExtensions.contains("mbstring"))
        assertTrue(report.detectedNodePackages.contains("socket.io"))
        assertTrue(report.detectedUploadDirs.any { it.contains("uploads") })

        val permRes = fileManager.restoreSafePermissions(projDir)
        assertTrue(permRes.isSuccess)
        projDir.deleteRecursively()
    }

    @Test
    fun `project installer detects Python FastAPI requirements generically`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val fileManager = IsolatedFileManager(context, db.appLogDao())
        val server = EmbeddedHttpServer(context, db.appLogDao())
        val installer = ProjectInstaller(context, fileManager, server, db.projectInstallDao(), db.appLogDao())

        val projDir = File(context.filesDir, "generic_python_scan").apply {
            deleteRecursively()
            mkdirs()
        }
        val publicHtml = File(projDir, "public_html").apply { mkdirs() }
        File(publicHtml, "main.py").writeText("from fastapi import FastAPI\napp = FastAPI()\n")
        File(publicHtml, "requirements.txt").writeText("fastapi>=0.115.0\nuvicorn>=0.30.0\npymysql>=1.1.0\n")

        val report = installer.analyzeProjectDirectory(projDir, null)
        assertEquals("Python", report.primaryLanguage)
        assertTrue(report.hasRequirementsTxt)
        assertTrue(report.detectedScriptOrFramework.contains("FastAPI"))
        assertTrue(report.detectedPythonPackages.contains("fastapi"))
        assertTrue(report.detectedPythonPackages.contains("uvicorn"))
        assertTrue(report.detectedDatabases.any { it.contains("MySQL") })
        projDir.deleteRecursively()
    }

    @Test
    fun `sqlite database manager creates db and executes sql`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val manager = DatabaseManager(context, db.databaseInstanceDao(), db.appLogDao())

        val created = manager.createDatabase(
            name = "hosting_test_db",
            engineType = DbEngineType.SQLITE,
            username = "admin_test",
            password = "secret_password",
            privileges = "ALL"
        )
        assertTrue(created.isSuccess)
        val entity = created.getOrThrow()

        val createTableRes = manager.executeSqliteQuery(
            entity,
            "CREATE TABLE sites_meta (id INTEGER PRIMARY KEY, domain TEXT); INSERT INTO sites_meta (domain) VALUES ('abotalal.test');"
        )
        assertFalse(createTableRes.isError)

        val selectRes = manager.executeSqliteQuery(entity, "SELECT domain FROM sites_meta;")
        assertFalse(selectRes.isError)
        assertEquals(1, selectRes.rows.size)
        assertEquals("abotalal.test", selectRes.rows.first().first())

        manager.deleteDatabase(entity)
    }

    @Test
    fun `isolated file manager creates strict workspace structure and enforces jail`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val fileManager = IsolatedFileManager(context, db.appLogDao())
        val siteRoot = File(context.filesDir, "sites/isolated_site_1").apply {
            deleteRecursively()
        }

        val publicHtml = fileManager.ensureSiteWorkspace(
            siteRoot = siteRoot,
            siteId = "isolated_site_1",
            siteName = "IsolatedSite",
            projectType = "PHP_APP",
            runtimeVersion = "8.4",
            port = 8085,
            entryFile = "index.php"
        )

        assertTrue(publicHtml.exists() && publicHtml.isDirectory)
        assertTrue(File(siteRoot, "config/.env").exists())
        assertTrue(File(siteRoot, "logs").isDirectory)
        assertTrue(File(siteRoot, "backups").isDirectory)
        assertTrue(File(siteRoot, "runtime_settings.json").exists())

        val escapeAttempt = fileManager.readTextFile(siteRoot, File(context.filesDir, "other_site/secret.txt").absolutePath)
        assertTrue(escapeAttempt.isFailure)
        siteRoot.deleteRecursively()
    }

    @Test
    fun `package downloader verifies SHA-256 and rejects checksum mismatches over real HTTP`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val downloader = PackageDownloader(context, db.appLogDao())

        val payload = "ABOTALAL_HOSTING_PACKAGE_PAYLOAD_12345".toByteArray()
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(payload)).setResponseCode(200))
        server.enqueue(MockResponse().setBody(Buffer().write(payload)).setResponseCode(200))
        server.start()

        val destValid = File(context.cacheDir, "dl_valid.bin")
        val destBadHash = File(context.cacheDir, "dl_bad.bin")
        try {
            val okUrl = server.url("/pkg.zip").toString()
            val okRes = downloader.downloadFileVerified(
                taskId = "t1",
                packageId = "p1",
                title = "Valid Download",
                url = okUrl,
                expectedSha256 = expectedSha,
                destFile = destValid
            )
            assertTrue(okRes.isSuccess)
            assertEquals(payload.size.toLong(), destValid.length())

            val badSha = "0".repeat(64)
            val failRes = downloader.downloadFileVerified(
                taskId = "t2",
                packageId = "p2",
                title = "Mismatch Download",
                url = okUrl,
                expectedSha256 = badSha,
                destFile = destBadHash
            )
            assertTrue(failRes.isFailure)
            assertFalse(destBadHash.exists())
        } finally {
            server.shutdown()
            destValid.delete()
            destBadHash.delete()
        }
    }

    @Test
    fun `archive extractor extracts zip and tar_gz safely and blocks zip slip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempRoot = File(context.cacheDir, "archive_test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // 1. Valid ZIP
            val zipFile = File(tempRoot, "valid.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry("folder/hello.txt"))
                zos.write("hello zip".toByteArray())
                zos.closeEntry()
            }
            val outZip = File(tempRoot, "out_zip")
            val zipRes = ArchiveExtractor.extractArchive(zipFile, outZip)
            assertTrue(zipRes.isSuccess)
            assertEquals("hello zip", File(outZip, "folder/hello.txt").readText())

            // 2. Valid TAR.GZ
            val tarGzFile = File(tempRoot, "valid.tar.gz")
            TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(tarGzFile))).use { tos ->
                val data = "hello tar gz".toByteArray()
                val entry = TarArchiveEntry("bin/tool.sh")
                entry.size = data.size.toLong()
                entry.mode = 493 // 0755
                tos.putArchiveEntry(entry)
                tos.write(data)
                tos.closeArchiveEntry()
            }
            val outTar = File(tempRoot, "out_tar")
            val tarRes = ArchiveExtractor.extractArchive(tarGzFile, outTar)
            assertTrue(tarRes.isSuccess)
            assertEquals("hello tar gz", File(outTar, "bin/tool.sh").readText())

            // 3. Zip-Slip attack rejection
            val evilZip = File(tempRoot, "evil.zip")
            ZipOutputStream(FileOutputStream(evilZip)).use { zos ->
                zos.putNextEntry(ZipEntry("../../evil.txt"))
                zos.write("pwn".toByteArray())
                zos.closeEntry()
            }
            val outEvil = File(tempRoot, "out_evil")
            val evilRes = ArchiveExtractor.extractArchive(evilZip, outEvil)
            assertTrue(evilRes.isFailure)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `phpMyAdmin and Python verification never report fake success without runtimes`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val downloader = PackageDownloader(context, db.appLogDao())
        val runtimeEngine = RuntimeEngine(context, db.appLogDao())
        val phpManager = PhpRuntimeManager(context, db.runtimeConfigDao(), runtimeEngine, db.appLogDao())
        val pythonManager = PythonRuntimeManager(context, db.runtimeConfigDao(), runtimeEngine, downloader, db.appLogDao())
        val httpServer = EmbeddedHttpServer(context, db.appLogDao(), db.domainDao(), db.siteDao())
        val pmaManager = PhpMyAdminManager(context, downloader, runtimeEngine, phpManager, httpServer, db.appLogDao())

        // Python 7-step check must honestly report 0/7 passed when python3 binary does not exist
        val pyReport = pythonManager.runPythonVerificationSuite("3.12")
        assertEquals(7, pyReport.steps.size)
        assertFalse(pyReport.allPassed)
        assertTrue(pyReport.steps.all { !it.passed })

        // phpMyAdmin config writer works cleanly and startAndVerify honestly fails when files are not installed
        File(pmaManager.pmaDir, "index.php").delete()
        val pmaStart = pmaManager.startAndVerify("8.4", "127.0.0.1", 3306)
        assertTrue(pmaStart.isFailure)

        File(pmaManager.pmaDir, "index.php").writeText("<?php echo 'pma';")
        pmaManager.writeConfigIncPhp("127.0.0.1", 3306)
        val cfgText = File(pmaManager.pmaDir, "config.inc.php").readText()
        assertTrue(cfgText.contains("127.0.0.1"))
        assertTrue(cfgText.contains("blowfish_secret"))
        val pmaStatus = pmaManager.refreshStatus("8.4", "127.0.0.1", 3306)
        assertFalse(pmaStatus.isVerified)
        File(pmaManager.pmaDir, "index.php").delete()
        File(pmaManager.pmaDir, "config.inc.php").delete()
    }
}
