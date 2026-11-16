package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SiteEntity
import com.example.core.model.DbEngineType
import com.example.core.security.SecurityHelper
import com.example.engine.db.DatabaseManager
import com.example.engine.domain.DomainManager
import com.example.engine.http.EmbeddedHttpServer
import com.example.engine.installer.ProjectInstaller
import com.example.files.IsolatedFileManager
import kotlinx.coroutines.runBlocking
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
import java.net.HttpURLConnection
import java.net.URL

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
    fun `project installer detects Sngine social script requirements and safe permissions`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val fileManager = IsolatedFileManager(context, db.appLogDao())
        val server = EmbeddedHttpServer(context, db.appLogDao())
        val installer = ProjectInstaller(context, fileManager, server, db.projectInstallDao(), db.appLogDao())

        val projDir = File(context.filesDir, "sngine_sample_scan").apply {
            deleteRecursively()
            mkdirs()
        }
        File(projDir, "bootloader.php").writeText("<?php // Sngine")
        File(projDir, "includes").mkdirs()
        File(projDir, "includes/config.php").writeText("<?php define('DB_HOST', '127.0.0.1'); // mysqli")
        File(projDir, "content/uploads").mkdirs()
        File(projDir, ".htaccess").writeText("RewriteEngine On\nRewriteRule ^([a-z]+)$ index.php?view=$1 [L]\n")
        File(projDir, "composer.json").writeText("""{"require":{"php":"^8.2","ext-mysqli":"*","ext-gd":"*"}}""")
        File(projDir, "package.json").writeText("""{"dependencies":{"socket.io":"^4.7.0"}}""")

        val report = installer.analyzeProjectDirectory(projDir, null)
        assertTrue(report.detectedScriptOrFramework.contains("Sngine"))
        assertEquals("PHP", report.primaryLanguage)
        assertTrue(report.hasComposerJson)
        assertTrue(report.hasPackageJson)
        assertTrue(report.hasHtaccess)
        assertTrue(report.requiresSocketIo)
        assertTrue(report.requiredPhpExtensions.contains("mysqli"))
        assertTrue(report.requiredPhpExtensions.contains("fileinfo"))
        assertTrue(report.detectedUploadDirs.contains("content/uploads"))

        val permRes = fileManager.restoreSafePermissions(projDir)
        assertTrue(permRes.isSuccess)
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
}
