package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SiteEntity
import com.example.core.model.DbEngineType
import com.example.core.security.SecurityHelper
import com.example.engine.db.DatabaseManager
import com.example.engine.http.EmbeddedHttpServer
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
    fun `embedded http server serves real site and enforces isolation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val server = EmbeddedHttpServer(context, db.appLogDao())

        val siteDir = File(context.filesDir, "test_site_1").apply {
            deleteRecursively()
            mkdirs()
        }
        File(siteDir, "index.html").writeText("<h1>Abotalal Live Test</h1>")

        val port = 18089
        val site = SiteEntity(
            id = "test-site-1",
            name = "TestSite",
            rootFolder = siteDir.absolutePath,
            port = port,
            projectType = "STATIC_HTML",
            runtimeVersion = "HTML5",
            entryFile = "index.html",
            allowLanAccess = false
        )

        val startRes = server.startSite(site)
        assertTrue(startRes.isSuccess)
        try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().readText()
            assertTrue(body.contains("Abotalal Live Test"))

            val traversalConn = URL("http://127.0.0.1:$port/../../etc/passwd").openConnection() as HttpURLConnection
            assertEquals(403, traversalConn.responseCode)
        } finally {
            server.stopAll()
            siteDir.deleteRecursively()
        }
    }

    @Test
    fun `sqlite database manager creates db and executes sql`() = runBlocking {
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
            "CREATE TABLE sites_meta (id INTEGER PRIMARY KEY, domain TEXT); INSERT INTO sites_meta (domain) VALUES ('abotalal.local');"
        )
        assertFalse(createTableRes.isError)

        val selectRes = manager.executeSqliteQuery(entity, "SELECT domain FROM sites_meta;")
        assertFalse(selectRes.isError)
        assertEquals(1, selectRes.rows.size)
        assertEquals("abotalal.local", selectRes.rows.first().first())

        manager.deleteDatabase(entity)
    }
}
