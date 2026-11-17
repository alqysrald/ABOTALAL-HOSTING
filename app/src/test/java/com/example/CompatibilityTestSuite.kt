package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.core.compat.AbotalalPermissionManager
import com.example.core.compat.AndroidCompatibilityLayer
import com.example.core.compat.ExecutionBackend
import com.example.core.compat.FgsStrategy
import com.example.core.compat.StorageStrategy
import com.example.core.database.AppDatabase
import com.example.core.database.SiteEntity
import com.example.core.model.AndroidPackageSpec
import com.example.core.model.DbEngineType
import com.example.core.model.EngineStatus
import com.example.core.model.EngineType
import com.example.core.model.PackageInstallState
import com.example.core.security.SecurityHelper
import com.example.engine.core.ArchiveExtractor
import com.example.engine.core.ExternalTcpEngineModule
import com.example.engine.core.PackageDownloader
import com.example.engine.core.ProcessProbe
import com.example.engine.core.RuntimeCompatibilityLayer
import com.example.engine.core.RuntimeEngine
import com.example.engine.core.ElfBinaryInspector
import com.example.engine.db.DatabaseManager
import com.example.engine.http.EmbeddedHttpServer
import com.example.engine.installer.ProjectInstaller
import com.example.engine.php.AbotalalPhpNativeBridge
import com.example.engine.runtime.PhpRuntimeManager
import com.example.engine.runtime.PythonRuntimeManager
import com.example.files.IsolatedFileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Robolectric Multi-API Compatibility & Security Architecture Verification Suite
 * Covers real Robolectric sandboxes for API 23 (Android 6), API 28 (Android 9),
 * API 29 (Android 10), API 30 (Android 11), API 33 (Android 13), API 34 (Android 14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 29, 30, 33, 34], application = Application::class)
class MultiApiCompatibilityMatrixTest {

    @Test
    fun `compatibility layer resolves accurate storage, fgs, wx, and permission rules across Android 6 to 14`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val sdk = Build.VERSION.SDK_INT

        val compat = AndroidCompatibilityLayer(context)
        val permManager = AbotalalPermissionManager(context, compat, db.appLogDao())
        val profile = compat.collectDeviceProfile()
        val health = permManager.computeSnapshot()

        assertEquals(sdk, profile.apiLevel)
        assertTrue(profile.extractNativeLibsEnabled)

        when {
            sdk <= 28 -> {
                assertEquals(StorageStrategy.LEGACY_API_23_28, profile.storageStrategy)
                assertTrue(profile.canExecuteInAppData)
                assertTrue(health.requiresLegacyRuntimeStorage)
                assertFalse(health.supportsManageAllFiles)
                assertFalse(health.requiresPostNotificationsPermission)
                assertArrayEquals(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    permManager.getRequiredLegacyPermissions()
                )
            }
            sdk == 29 -> {
                assertEquals(StorageStrategy.SCOPED_TRANSITION_API_29, profile.storageStrategy)
                assertEquals(FgsStrategy.FOREGROUND_DATA_SYNC_API_29_33, profile.fgsStrategy)
                assertFalse(profile.canExecuteInAppData) // targetSdk >= 29 enforces W^X
                assertTrue(health.requiresLegacyRuntimeStorage)
                assertArrayEquals(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    permManager.getRequiredLegacyPermissions()
                )
            }
            sdk in 30..32 -> {
                assertEquals(StorageStrategy.SCOPED_SAF_API_30_PLUS, profile.storageStrategy)
                assertEquals(FgsStrategy.FOREGROUND_DATA_SYNC_API_29_33, profile.fgsStrategy)
                assertFalse(profile.canExecuteInAppData)
                assertFalse(health.requiresLegacyRuntimeStorage)
                assertTrue(health.supportsManageAllFiles)
                assertFalse(health.requiresPostNotificationsPermission)
                assertEquals(0, permManager.getRequiredLegacyPermissions().size)
            }
            sdk == 33 -> {
                assertEquals(StorageStrategy.SCOPED_SAF_API_30_PLUS, profile.storageStrategy)
                assertEquals(FgsStrategy.FOREGROUND_DATA_SYNC_API_29_33, profile.fgsStrategy)
                assertFalse(profile.canExecuteInAppData)
                assertTrue(health.supportsManageAllFiles)
                assertTrue(health.requiresPostNotificationsPermission)
            }
            else -> { // 34+
                assertEquals(StorageStrategy.SCOPED_SAF_API_30_PLUS, profile.storageStrategy)
                assertEquals(FgsStrategy.STRICT_TYPED_FGS_API_34_PLUS, profile.fgsStrategy)
                assertFalse(profile.canExecuteInAppData)
                assertTrue(health.supportsManageAllFiles)
                assertTrue(health.requiresPostNotificationsPermission)
            }
        }

        assertNotNull(permManager.buildAppNotificationSettingsIntent())
        if (sdk >= 30) {
            assertNotNull(permManager.buildManageAllFilesSettingsIntent())
        } else {
            assertNull(permManager.buildManageAllFilesSettingsIntent())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuntimeDiagnosticsAndElfSuiteTest {

    @Test
    fun `compatibility rules behave identically for API 35, 36, and 37 via apiLevelProvider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)

        for (api in listOf(35, 36, 37)) {
            val compat = AndroidCompatibilityLayer(context, apiLevelProvider = { api })
            val permManager = AbotalalPermissionManager(context, compat, db.appLogDao())
            val profile = compat.collectDeviceProfile()
            val health = permManager.computeSnapshot()

            assertEquals(api, profile.apiLevel)
            assertEquals(StorageStrategy.SCOPED_SAF_API_30_PLUS, profile.storageStrategy)
            assertEquals(FgsStrategy.STRICT_TYPED_FGS_API_34_PLUS, profile.fgsStrategy)
            assertFalse(profile.canExecuteInAppData)
            assertTrue(health.supportsManageAllFiles)
            assertTrue(health.requiresPostNotificationsPermission)
        }
    }

    @Test
    fun `runtime environment never injects external Termux paths into PATH or LD_LIBRARY_PATH`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val runtimeEngine = RuntimeEngine(context, db.appLogDao())

        val phpEnv = runtimeEngine.buildEnvironment(EngineType.PHP, "8.5")
        val pyEnv = runtimeEngine.buildEnvironment(EngineType.PYTHON, "3.12")

        assertFalse(phpEnv["PATH"].orEmpty().contains("com.termux"))
        assertFalse(phpEnv["LD_LIBRARY_PATH"].orEmpty().contains("com.termux"))
        assertFalse(pyEnv["PATH"].orEmpty().contains("com.termux"))
        assertFalse(pyEnv["LD_LIBRARY_PATH"].orEmpty().contains("com.termux"))
        assertEquals("1", pyEnv["PYTHONUNBUFFERED"])
        assertTrue(phpEnv.containsKey("PHPRC"))
    }

    @Test
    fun `nativeLibraryDir bundled Bionic binary passes 8-point W^X and SELinux decision while filesDir fails on API 29+`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "native_lib_check").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val fakeBundledPhp = File(tempDir, "libphp_cgi.so")
            buildSyntheticElf64(
                outFile = fakeBundledPhp,
                eMachine = 183,
                interp = "/system/bin/linker64",
                needed = listOf("libc.so", "libdl.so"),
                runpath = emptyList()
            )
            fakeBundledPhp.setExecutable(true, false)

            val fakeAppDataPhp = File(context.filesDir, "runtimes/php/8.5/bin/php-cgi")
            fakeAppDataPhp.parentFile?.mkdirs()
            buildSyntheticElf64(
                outFile = fakeAppDataPhp,
                eMachine = 183,
                interp = "/system/bin/linker64",
                needed = listOf("libc.so"),
                runpath = emptyList()
            )
            fakeAppDataPhp.setExecutable(true, false)

            // API 34: bundled libphp_cgi.so succeeds, appData binary is strictly blocked
            val compat34 = AndroidCompatibilityLayer(context, apiLevelProvider = { 34 })
            val allowBundled = compat34.evaluateEngineExecution(EngineType.PHP, bundledNativeBinary = fakeBundledPhp, downloadedAppDataBinary = null)
            assertTrue(allowBundled.isExecutableOnDevice)
            assertEquals(ExecutionBackend.BUNDLED_NATIVE_LIB, allowBundled.backend)

            val blockAppData = compat34.evaluateEngineExecution(EngineType.PHP, bundledNativeBinary = null, downloadedAppDataBinary = fakeAppDataPhp)
            assertFalse(blockAppData.isExecutableOnDevice)
            assertEquals(ExecutionBackend.BLOCKED_WX_SELINUX, blockAppData.backend)
            assertTrue(blockAppData.reasonAr.contains("error=13"))

            // Legacy API 26 (Android 8): appData binary is permitted when targetSdk allows or API < 29
            val compat26 = AndroidCompatibilityLayer(context, apiLevelProvider = { 26 })
            val legacyExec = compat26.evaluateEngineExecution(EngineType.PHP, bundledNativeBinary = null, downloadedAppDataBinary = fakeAppDataPhp)
            assertTrue(legacyExec.isExecutableOnDevice)
            assertEquals(ExecutionBackend.LEGACY_APP_DATA_EXEC, legacyExec.backend)
        } finally {
            tempDir.deleteRecursively()
            File(context.filesDir, "runtimes/php/8.5").deleteRecursively()
        }
    }

    @Test
    fun `elf binary inspector parses ARM64 Bionic, Linux glibc, x86_64 mismatch, and Termux RUNPATH`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "elf_inspector_tests").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val libDir = File(tempDir, "libs").apply { mkdirs() }
            File(libDir, "libc.so").writeText("stub")
            val bionicElf = File(tempDir, "php-cgi-bionic")
            buildSyntheticElf64(
                outFile = bionicElf,
                eMachine = 183, // EM_AARCH64
                interp = "/system/bin/linker64",
                needed = listOf("libc.so", "libmissing_ext.so"),
                runpath = emptyList()
            )
            val resBionic = ElfBinaryInspector.inspect(
                binaryFile = bionicElf,
                expectedAbis = listOf("arm64-v8a"),
                librarySearchDirs = listOf(libDir)
            )
            assertTrue(resBionic.isValidElf)
            assertEquals(64, resBionic.bitness)
            assertEquals("arm64-v8a", resBionic.abiName)
            assertEquals("/system/bin/linker64", resBionic.interpreterPath)
            assertTrue(resBionic.isBionicLinker)
            assertFalse(resBionic.isGlibcLinker)
            assertEquals(listOf("libmissing_ext.so"), resBionic.missingLibraries)

            val glibcElf = File(tempDir, "php-cgi-glibc")
            buildSyntheticElf64(
                outFile = glibcElf,
                eMachine = 183,
                interp = "/lib/ld-linux-aarch64.so.1",
                needed = listOf("libc.so.6"),
                runpath = emptyList()
            )
            val resGlibc = ElfBinaryInspector.inspect(glibcElf, listOf("arm64-v8a"))
            assertTrue(resGlibc.isValidElf)
            assertFalse(resGlibc.isBionicLinker)
            assertTrue(resGlibc.isGlibcLinker)

            val termuxElf = File(tempDir, "php-cgi-termux")
            buildSyntheticElf64(
                outFile = termuxElf,
                eMachine = 183,
                interp = "/system/bin/linker64",
                needed = listOf("libc.so"),
                runpath = listOf("/data/data/com.termux/files/usr/lib")
            )
            val resTermux = ElfBinaryInspector.inspect(termuxElf, listOf("arm64-v8a"))
            assertTrue(resTermux.runpathEntries.any { it.contains("com.termux") })

            val x86Elf = File(tempDir, "php-x86_64")
            buildSyntheticElf64(
                outFile = x86Elf,
                eMachine = 62, // EM_X86_64
                interp = "/system/bin/linker64",
                needed = emptyList(),
                runpath = emptyList()
            )
            val resX86 = ElfBinaryInspector.inspect(x86Elf, listOf("arm64-v8a"))
            assertEquals("x86_64", resX86.abiName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `error=13 Permission denied on API 34 app_data php-cgi is diagnosed accurately across 8 points and never faked`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val compat = AndroidCompatibilityLayer(context, apiLevelProvider = { 34 })
        val runtimeCompat = RuntimeCompatibilityLayer(context, compat)
        val runtimeEngine = RuntimeEngine(context, db.appLogDao())
        val phpManager = PhpRuntimeManager(context, db.runtimeConfigDao(), runtimeEngine, db.appLogDao(), runtimeCompat)
        val httpServer = EmbeddedHttpServer(context, db.appLogDao(), db.domainDao(), db.siteDao())

        val phpBin = File(context.filesDir, "runtimes/php/8.5/bin/php-cgi")
        phpBin.parentFile?.mkdirs()
        buildSyntheticElf64(
            outFile = phpBin,
            eMachine = 183,
            interp = "/system/bin/linker64",
            needed = listOf("libphp-8.5.so", "libc.so"),
            runpath = listOf("/data/data/com.termux/files/usr/lib")
        )
        phpBin.setExecutable(true, false)

        try {
            val phpTermuxSpec = AndroidPackageSpec(
                id = "apt_php_arm64",
                programName = "php",
                engineType = EngineType.PHP,
                version = "8.5.12-2",
                abi = "arm64-v8a (aarch64)",
                minAndroidApi = 23,
                repositoryName = "Termux Android Bionic (aarch64)",
                officialUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/php/php_8.5.12-2_aarch64.deb",
                sha256 = "a".repeat(64),
                downloadSizeBytes = 10_000_000L,
                dependencies = listOf("libxml2", "openssl"),
                installState = PackageInstallState.NOT_INSTALLED,
                verificationSummary = "",
                androidCompatibilityNote = "",
                isAvailableForInstall = true
            )
            val eligibility = runtimeCompat.evaluatePackageInstallEligibility(phpTermuxSpec)
            assertFalse(eligibility.allowed)
            assertTrue(eligibility.reasonAr.contains("W^X") || eligibility.reasonAr.contains("error=13"))

            val pmaSpec = phpTermuxSpec.copy(
                id = "pkg_phpmyadmin",
                programName = "phpmyadmin",
                engineType = EngineType.PHPMYADMIN,
                repositoryName = "phpmyadmin.net",
                officialUrl = "https://files.phpmyadmin.net/phpMyAdmin/5.2.1/phpMyAdmin-5.2.1-all-languages.zip"
            )
            assertTrue(runtimeCompat.evaluatePackageInstallEligibility(pmaSpec).allowed)

            assertNull(ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php/8.5"))
            assertNotNull(ProcessProbe.findBlockedAppDataBinary(context, listOf("php-cgi", "php"), "php/8.5"))

            val engineInfo = phpManager.probeStatus()
            assertEquals(EngineStatus.ERROR, engineInfo.status)

            val diag = runtimeCompat.runEightPointDiagnostics(EngineType.PHP)
            assertEquals(8, diag.checks.size)
            assertEquals(ExecutionBackend.BLOCKED_WX_SELINUX, diag.backend)
            assertFalse(diag.isExecutionPermitted)
            assertFalse(diag.checks[4].passed) // PT_INTERP / missing libs / Termux RUNPATH
            assertFalse(diag.checks[5].passed) // SELinux app_data_file
            assertFalse(diag.checks[6].passed) // W^X Android 10+
            assertFalse(diag.checks[7].passed) // ProcessBuilder path blocked

            val phpReport = phpManager.runPhpVerificationSuite("8.5", httpServer)
            assertEquals(7, phpReport.steps.size)
            assertFalse(phpReport.allPassed)
            assertEquals(0, phpReport.steps.count { it.passed })
        } finally {
            File(context.filesDir, "runtimes/php/8.5").deleteRecursively()
        }
    }

    @Test
    fun `SAF folder import streams files into isolated site root and blocks traversal`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val compat = AndroidCompatibilityLayer(context, apiLevelProvider = { 34 })
        val permManager = AbotalalPermissionManager(context, compat, db.appLogDao())
        val fileManager = IsolatedFileManager(context, db.appLogDao())

        val sourceFile = File(context.cacheDir, "saf_stream_src.txt").apply {
            writeText("SAF_STREAMING_OK_123")
        }
        val destDir = File(context.filesDir, "sites/saf_test_site/public_html").apply {
            deleteRecursively()
            mkdirs()
        }
        val destFile = File(destDir, "imported.txt")
        try {
            val streamRes = permManager.copyLargeUriStreaming(Uri.fromFile(sourceFile), destFile)
            assertTrue(streamRes.isSuccess)
            assertEquals("SAF_STREAMING_OK_123", destFile.readText())
            assertNull(SecurityHelper.resolveSafeChild(destDir, "../../outside.txt"))
            assertFalse(fileManager.readTextFile(destDir, "../../outside.txt").isSuccess)
        } finally {
            sourceFile.delete()
            File(context.filesDir, "sites/saf_test_site").deleteRecursively()
        }
    }

    @Test
    fun `database lifecycle supports SQLite CRUD, export, import, read-only enforcement, and honest TCP engine states`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val runtimeEngine = RuntimeEngine(context, db.appLogDao())
        val dbManager = DatabaseManager(context, db.databaseInstanceDao(), db.appLogDao())

        // 1. SQLite create, multi-statement create+insert+select, table inspection, dump export/import
        val createdDb = dbManager.createDatabase("compat_sqlite", DbEngineType.SQLITE, "admin_u", "Pass123!", "ALL").getOrThrow()
        val targetDb = dbManager.createDatabase("compat_sqlite_restore", DbEngineType.SQLITE, "ro_u", "Pass123!", "READ_ONLY").getOrThrow()

        val exportFile = File(context.cacheDir, "compat_export.sql")
        try {
            val statusInfo = dbManager.getSqliteEngineStatus()
            assertEquals(EngineStatus.RUNNING, statusInfo.status)

            val qRes = dbManager.executeSqliteQuery(
                createdDb,
                "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO users(name) VALUES('abotalal'); SELECT name FROM users;"
            )
            assertFalse("Unexpected SQLite error: ${qRes.message}", qRes.isError)
            assertEquals(listOf("name"), qRes.columns)
            assertEquals("abotalal", qRes.rows.first().first())

            val tables = dbManager.inspectSqliteTables(createdDb.storageTarget)
            assertTrue(tables.any { it.name == "users" && it.rowCount == 1L })

            val exportRes = dbManager.exportSqlDump(createdDb, Uri.fromFile(exportFile))
            assertTrue(exportRes.isSuccess)
            assertTrue(exportFile.readText().contains("INSERT INTO \"users\" VALUES(1, 'abotalal')"))

            val importRes = dbManager.importSqlFile(targetDb, Uri.fromFile(exportFile))
            assertTrue(importRes.isSuccess)
            val restoredQuery = dbManager.executeSqliteQuery(targetDb, "SELECT name FROM users;")
            assertFalse(restoredQuery.isError)
            assertEquals("abotalal", restoredQuery.rows.first().first())

            // READ_ONLY privilege check must block DROP TABLE / INSERT
            val blockedWrite = dbManager.executeSqliteQuery(targetDb, "DROP TABLE users;")
            assertTrue(blockedWrite.isError)
            assertTrue(blockedWrite.message.contains("READ_ONLY"))

            // 2. MariaDB & PostgreSQL ExternalTcpEngineModule must fail honestly when binaries are unbundled
            val mysqlMod = ExternalTcpEngineModule(
                context = context,
                runtimeEngine = runtimeEngine,
                engineType = EngineType.MYSQL_MARIADB,
                binaryCandidates = listOf("mariadbd", "mysqld"),
                subDir = "mariadb",
                defaultPort = 3306,
                requirementNoticeText = "MariaDB"
            )
            val pgMod = ExternalTcpEngineModule(
                context = context,
                runtimeEngine = runtimeEngine,
                engineType = EngineType.POSTGRESQL,
                binaryCandidates = listOf("postgres", "pg_ctl"),
                subDir = "postgresql",
                defaultPort = 5432,
                requirementNoticeText = "PostgreSQL"
            )
            assertEquals(EngineStatus.NOT_INSTALLED, mysqlMod.probeEngine().status)
            assertEquals(EngineStatus.NOT_INSTALLED, pgMod.probeEngine().status)
            assertTrue(mysqlMod.start().isFailure)
            assertTrue(pgMod.start().isFailure)
            assertEquals(EngineStatus.NOT_INSTALLED, mysqlMod.stop().getOrThrow().status)
            assertTrue(mysqlMod.restart().isFailure)
        } finally {
            exportFile.delete()
            dbManager.deleteDatabase(createdDb)
            dbManager.deleteDatabase(targetDb)
        }
    }

    @Test
    fun `project installer zip analysis, venv layout, and python verification work cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val downloader = PackageDownloader(context, db.appLogDao())
        val fileManager = IsolatedFileManager(context, db.appLogDao())
        val server = EmbeddedHttpServer(context, db.appLogDao(), db.domainDao(), db.siteDao())
        val installer = ProjectInstaller(context, fileManager, server, db.projectInstallDao(), db.appLogDao())
        val runtimeEngine = RuntimeEngine(context, db.appLogDao())
        val pyManager = PythonRuntimeManager(context, db.runtimeConfigDao(), runtimeEngine, downloader, db.appLogDao())

        val projRoot = File(context.filesDir, "sites/compat_py_site").apply {
            deleteRecursively()
            mkdirs()
        }
        val zipSrc = File(context.cacheDir, "flask_sample.zip")
        ZipOutputStream(FileOutputStream(zipSrc)).use { zos ->
            zos.putNextEntry(ZipEntry("app.py"))
            zos.write("from flask import Flask\napp = Flask(__name__)\n".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("requirements.txt"))
            zos.write("flask>=3.0.0\ngunicorn>=22.0.0\n".toByteArray())
            zos.closeEntry()
        }
        try {
            val extractRes = ArchiveExtractor.extractArchive(zipSrc, File(projRoot, "public_html"))
            assertTrue(extractRes.isSuccess)
            val site = SiteEntity(
                id = "compat_py_site",
                name = "FlaskTest",
                rootFolder = projRoot.absolutePath,
                port = 18095,
                projectType = "PYTHON_FLASK",
                runtimeVersion = "3.12",
                entryFile = "app.py"
            )
            val analysis = installer.analyzeProjectDirectory(projRoot, site)
            assertEquals("Python", analysis.primaryLanguage)
            assertTrue(analysis.hasRequirementsTxt)
            assertTrue(analysis.detectedPythonPackages.contains("flask"))

            // Python venv structure creation
            val venvRes = pyManager.createVirtualEnv(site, "3.12")
            assertTrue(venvRes.isSuccess)
            assertTrue(File(projRoot, "venv/pyvenv.cfg").exists())
            assertTrue(File(projRoot, "venv/lib/python3.12/site-packages").isDirectory)

            // Python 7-step check honestly fails without bundled libpython3.so
            val pyReport = pyManager.runPythonVerificationSuite("3.12")
            assertEquals(7, pyReport.steps.size)
            assertFalse(pyReport.allPassed)
            assertEquals(0, pyReport.steps.count { it.passed })
        } finally {
            zipSrc.delete()
            projRoot.deleteRecursively()
        }
    }

    @Test
    fun `bundled php 8_3 manifest metadata and jni embedded sapi multi-version detection work honestly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val runtimeEngine = RuntimeEngine(context, db.appLogDao())
        val phpManager = PhpRuntimeManager(context, db.runtimeConfigDao(), runtimeEngine, db.appLogDao())

        // 1. Asset manifest for built ARM64 PHP 8.3.14 is parsed accurately
        val manifests = AbotalalPhpNativeBridge.inspectVersionManifests(context)
        val php83 = manifests.firstOrNull { it.series == "8.3" }
        assertNotNull("Missing php_8_3_arm64-v8a.json asset manifest", php83)
        assertEquals("8.3.14", php83!!.fullVersion)
        assertEquals("arm64-v8a", php83.abi)
        assertEquals("libphp_8_3.so", php83.coreLibrary)
        assertEquals("libabotalal_php_8_3.so", php83.adapterLibrary)
        assertTrue(php83.extensions.containsAll(listOf("mysqli", "pdo_sqlite", "openssl", "mbstring", "session")))

        // 2. On JVM host, isEngineAvailable returns false cleanly without throwing UnsatisfiedLinkError
        assertFalse(AbotalalPhpNativeBridge.isEngineAvailable(context, "8.3"))
        assertFalse(AbotalalPhpNativeBridge.isEngineAvailable(context, "8.4"))

        // 3. Simulated nativeLibraryDir with libphp_8_3.so + libabotalal_php_8_3.so passes BUNDLED_NATIVE_LIB on API 34
        val nativeDir = File(context.cacheDir, "sim_jni_native_dir").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val prevNativeDir = context.applicationInfo.nativeLibraryDir
            context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
            try {
                buildSyntheticElf64(
                    outFile = File(nativeDir, "libphp_8_3.so"),
                    eMachine = 183,
                    interp = "",
                    needed = listOf("libm.so", "libdl.so", "liblog.so", "libc.so"),
                    runpath = emptyList()
                )
                buildSyntheticElf64(
                    outFile = File(nativeDir, "libabotalal_php_8_3.so"),
                    eMachine = 183,
                    interp = "",
                    needed = listOf("libphp_8_3.so", "liblog.so", "libdl.so", "libm.so", "libc.so"),
                    runpath = emptyList()
                )

                val versions = AbotalalPhpNativeBridge.listInstalledNativeVersions(context)
                assertEquals(listOf("8.3"), versions)
                assertNotNull(AbotalalPhpNativeBridge.findNativeLibsForVersion(context, "8.3"))
                assertNull(AbotalalPhpNativeBridge.findNativeLibsForVersion(context, "8.4"))

                val compat34 = AndroidCompatibilityLayer(context, apiLevelProvider = { 34 })
                val runtimeCompat = RuntimeCompatibilityLayer(context, compat34)
                val bundled = runtimeCompat.findBundledNativeBinary(EngineType.PHP)
                assertNotNull(bundled)
                assertEquals("libabotalal_php_8_3.so", bundled!!.name)

                val decision = compat34.evaluateEngineExecution(EngineType.PHP, bundledNativeBinary = bundled)
                assertTrue(decision.isExecutableOnDevice)
                assertEquals(ExecutionBackend.BUNDLED_NATIVE_LIB, decision.backend)
            } finally {
                context.applicationInfo.nativeLibraryDir = prevNativeDir
            }

            // 4. Default php.ini avoids invalid dl() lines for statically built-in extensions
            val defaultIni = phpManager.buildDefaultPhpIni("8.3", phpManager.comprehensiveWebExtensions)
            assertFalse(defaultIni.contains("extension=mysqli.so"))
            assertTrue(defaultIni.contains("session.use_cookies = 1"))
        } finally {
            nativeDir.deleteRecursively()
        }
    }

    private fun buildSyntheticElf64(
        outFile: File,
        eMachine: Int,
        interp: String,
        needed: List<String>,
        runpath: List<String>
    ) {
        val ehdrSize = 64
        val phdrSize = 56
        val phnum = 3
        val phoff = ehdrSize.toLong()
        val afterPhdrs = ehdrSize + phnum * phdrSize

        val interpBytes = (interp + "\u0000").toByteArray(Charsets.UTF_8)
        val interpOffset = afterPhdrs.toLong()

        val strOut = ByteArrayOutputStream()
        strOut.write(0)
        val neededOffsets = needed.map { lib ->
            val pos = strOut.size().toLong()
            strOut.write((lib + "\u0000").toByteArray(Charsets.UTF_8))
            pos
        }
        val runpathOffsets = if (runpath.isNotEmpty()) {
            val pos = strOut.size().toLong()
            strOut.write((runpath.joinToString(":") + "\u0000").toByteArray(Charsets.UTF_8))
            listOf(pos)
        } else {
            emptyList()
        }
        val strTabBytes = strOut.toByteArray()

        val strTabOffset = interpOffset + interpBytes.size
        val dynOffset = strTabOffset + strTabBytes.size

        val dynEntries = mutableListOf<Pair<Long, Long>>()
        dynEntries.add(5L to strTabOffset)
        dynEntries.add(10L to strTabBytes.size.toLong())
        neededOffsets.forEach { dynEntries.add(1L to it) }
        runpathOffsets.forEach { dynEntries.add(29L to it) }
        dynEntries.add(0L to 0L)

        val dynBytesSize = dynEntries.size * 16
        val totalSize = (dynOffset + dynBytesSize).toInt()
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(0x7F.toByte())
        buf.put('E'.code.toByte())
        buf.put('L'.code.toByte())
        buf.put('F'.code.toByte())
        buf.put(2.toByte())
        buf.put(1.toByte())
        buf.put(1.toByte())
        buf.put(3.toByte())
        for (i in 8 until 16) buf.put(0.toByte())
        buf.putShort(3)
        buf.putShort(eMachine.toShort())
        buf.putInt(1)
        buf.putLong(0L)
        buf.putLong(phoff)
        buf.putLong(0L)
        buf.putInt(0)
        buf.putShort(ehdrSize.toShort())
        buf.putShort(phdrSize.toShort())
        buf.putShort(phnum.toShort())
        buf.putShort(64)
        buf.putShort(0)
        buf.putShort(0)

        putPhdr64(buf, pType = 1, pOffset = 0L, pVaddr = 0L, pFilesz = totalSize.toLong())
        putPhdr64(buf, pType = 3, pOffset = interpOffset, pVaddr = interpOffset, pFilesz = interpBytes.size.toLong())
        putPhdr64(buf, pType = 2, pOffset = dynOffset, pVaddr = dynOffset, pFilesz = dynBytesSize.toLong())

        buf.put(interpBytes)
        buf.put(strTabBytes)
        dynEntries.forEach { (tag, v) ->
            buf.putLong(tag)
            buf.putLong(v)
        }

        outFile.writeBytes(buf.array())
    }

    private fun putPhdr64(buf: ByteBuffer, pType: Int, pOffset: Long, pVaddr: Long, pFilesz: Long) {
        buf.putInt(pType)
        buf.putInt(5)
        buf.putLong(pOffset)
        buf.putLong(pVaddr)
        buf.putLong(pVaddr)
        buf.putLong(pFilesz)
        buf.putLong(pFilesz)
        buf.putLong(0x1000L)
    }
}
