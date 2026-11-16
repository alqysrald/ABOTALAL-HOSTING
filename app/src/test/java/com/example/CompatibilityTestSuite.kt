package com.example

import android.Manifest
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
import com.example.engine.core.ElfBinaryInspector
import com.example.engine.core.PackageDownloader
import com.example.engine.core.ProcessProbe
import com.example.engine.core.RuntimeCompatibilityLayer
import com.example.engine.core.RuntimeEngine
import com.example.engine.db.DatabaseManager
import com.example.engine.http.EmbeddedHttpServer
import com.example.engine.installer.ProjectInstaller
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
 * Covers API 23 (Android 6), API 28 (Android 9), API 29 (Android 10), API 30 (Android 11),
 * API 33 (Android 13), API 34 (Android 14), API 35 (Android 15), and API 36 (Android 16/17+).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 29, 30, 33, 34, 35, 36])
class MultiApiCompatibilityMatrixTest {

    @Test
    fun `compatibility layer resolves accurate storage, fgs, wx, and permission rules across Android 6 to 17`() {
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
            else -> { // 34, 35, 36+
                assertEquals(StorageStrategy.SCOPED_SAF_API_30_PLUS, profile.storageStrategy)
                assertEquals(FgsStrategy.STRICT_TYPED_FGS_API_34_PLUS, profile.fgsStrategy)
                assertFalse(profile.canExecuteInAppData)
                assertTrue(health.supportsManageAllFiles)
                assertTrue(health.requiresPostNotificationsPermission)
            }
        }

        // Notification intent works on every API without crashing
        assertNotNull(permManager.buildAppNotificationSettingsIntent())
        if (sdk >= 30) {
            assertNotNull(permManager.buildManageAllFilesSettingsIntent())
        } else {
            assertNull(permManager.buildManageAllFilesSettingsIntent())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeDiagnosticsAndElfSuiteTest {

    @Test
    fun `elf binary inspector parses ARM64 Bionic, Linux glibc, x86_64 mismatch, and Termux RUNPATH`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "elf_inspector_tests").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // 1. Valid ARM64 Android Bionic binary
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

            // 2. Linux glibc ARM64 binary (must be rejected as glibc)
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

            // 3. Termux DT_RUNPATH & x86_64 mismatch detection
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

        // Simulate downloaded Termux PHP 8.5 binary sitting inside getFilesDir()/runtimes/php/8.5/bin/php-cgi
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
            // 1. Pre-install gate must refuse Termux .deb on API 34
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

            // Pure PHP phpMyAdmin remains installable as archive
            val pmaSpec = phpTermuxSpec.copy(
                id = "pkg_phpmyadmin",
                programName = "phpmyadmin",
                engineType = EngineType.PHPMYADMIN,
                repositoryName = "phpmyadmin.net",
                officialUrl = "https://files.phpmyadmin.net/phpMyAdmin/5.2.1/phpMyAdmin-5.2.1-all-languages.zip"
            )
            assertTrue(runtimeCompat.evaluatePackageInstallEligibility(pmaSpec).allowed)

            // 2. ProcessProbe must NOT return writable filesDir binary on API 34, and must find it as blocked
            assertNull(ProcessProbe.findBinary(context, listOf("php-cgi", "php"), "php/8.5"))
            assertNotNull(ProcessProbe.findBlockedAppDataBinary(context, listOf("php-cgi", "php"), "php/8.5"))

            // 3. PhpRuntimeManager.probeStatus() reports EngineStatus.ERROR honestly (not RUNNING/STOPPED)
            val engineInfo = phpManager.probeStatus()
            assertEquals(EngineStatus.ERROR, engineInfo.status)

            // 4. 8-Point Diagnostic report catches SELinux (6), W^X (7), and Termux DT_RUNPATH (5)
            val diag = runtimeCompat.runEightPointDiagnostics(EngineType.PHP)
            assertEquals(8, diag.checks.size)
            assertEquals(ExecutionBackend.BLOCKED_WX_SELINUX, diag.backend)
            assertFalse(diag.isExecutionPermitted)
            assertFalse(diag.checks[4].passed) // PT_INTERP / missing libs / Termux RUNPATH
            assertFalse(diag.checks[5].passed) // SELinux app_data_file
            assertFalse(diag.checks[6].passed) // W^X Android 10+
            assertFalse(diag.checks[7].passed) // ProcessBuilder path blocked

            // 5. 7-Step PHP Verification Suite reports 0/7 honestly
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
    fun `project installer zip analysis, sqlite, python verification, and compatibility checks work cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val downloader = PackageDownloader(context, db.appLogDao())
        val fileManager = IsolatedFileManager(context, db.appLogDao())
        val server = EmbeddedHttpServer(context, db.appLogDao(), db.domainDao(), db.siteDao())
        val installer = ProjectInstaller(context, fileManager, server, db.projectInstallDao(), db.appLogDao())
        val runtimeEngine = RuntimeEngine(context, db.appLogDao())
        val pyManager = PythonRuntimeManager(context, db.runtimeConfigDao(), runtimeEngine, downloader, db.appLogDao())
        val dbManager = DatabaseManager(context, db.databaseInstanceDao(), db.appLogDao())

        // Zip extraction + Flask project detection
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

            // Python 7-step check honestly fails without bundled libpython3.so
            val pyReport = pyManager.runPythonVerificationSuite("3.12")
            assertEquals(7, pyReport.steps.size)
            assertFalse(pyReport.allPassed)

            // SQLite CRUD works in-process on every API
            val createdDb = dbManager.createDatabase("compat_sqlite", DbEngineType.SQLITE, "u", "p", "ALL").getOrThrow()
            val qRes = dbManager.executeSqliteQuery(createdDb, "CREATE TABLE t(x INT); INSERT INTO t VALUES(42); SELECT x FROM t;")
            assertFalse(qRes.isError)
            assertEquals("42", qRes.rows.first().first())
            dbManager.deleteDatabase(createdDb)
        } finally {
            zipSrc.delete()
            projRoot.deleteRecursively()
        }
    }

    /**
     * Generates a valid minimal 64-bit LSB ELF binary with PT_INTERP and PT_DYNAMIC (DT_NEEDED, DT_RUNPATH)
     * in memory for rigorous verification of ElfBinaryInspector.
     */
    private fun buildSyntheticElf64(
        outFile: File,
        eMachine: Int,
        interp: String,
        needed: List<String>,
        runpath: List<String>
    ) {
        val ehdrSize = 64
        val phdrSize = 56
        val phnum = 3 // PT_LOAD, PT_INTERP, PT_DYNAMIC
        val phoff = ehdrSize.toLong()
        val afterPhdrs = ehdrSize + phnum * phdrSize

        val interpBytes = (interp + "\u0000").toByteArray(Charsets.UTF_8)
        val interpOffset = afterPhdrs.toLong()

        val strOut = ByteArrayOutputStream()
        strOut.write(0) // index 0 = empty string
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
        dynEntries.add(5L to strTabOffset) // DT_STRTAB (vaddr == file offset in ET_DYN)
        dynEntries.add(10L to strTabBytes.size.toLong()) // DT_STRSZ
        neededOffsets.forEach { dynEntries.add(1L to it) } // DT_NEEDED
        runpathOffsets.forEach { dynEntries.add(29L to it) } // DT_RUNPATH
        dynEntries.add(0L to 0L) // DT_NULL

        val dynBytesSize = dynEntries.size * 16
        val totalSize = (dynOffset + dynBytesSize).toInt()
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // ELF Header (64 bytes)
        buf.put(0x7F.toByte())
        buf.put('E'.code.toByte())
        buf.put('L'.code.toByte())
        buf.put('F'.code.toByte())
        buf.put(2.toByte()) // EI_CLASS = 64-bit
        buf.put(1.toByte()) // EI_DATA = little endian
        buf.put(1.toByte()) // EI_VERSION
        buf.put(3.toByte()) // OSABI
        for (i in 8 until 16) buf.put(0.toByte())
        buf.putShort(3) // e_type = ET_DYN
        buf.putShort(eMachine.toShort()) // e_machine
        buf.putInt(1) // e_version
        buf.putLong(0L) // e_entry
        buf.putLong(phoff) // e_phoff
        buf.putLong(0L) // e_shoff
        buf.putInt(0) // e_flags
        buf.putShort(ehdrSize.toShort()) // e_ehsize
        buf.putShort(phdrSize.toShort()) // e_phentsize
        buf.putShort(phnum.toShort()) // e_phnum
        buf.putShort(64) // e_shentsize
        buf.putShort(0) // e_shnum
        buf.putShort(0) // e_shstrndx

        // PHDR 0: PT_LOAD (covers entire file with vaddr == offset)
        putPhdr64(buf, pType = 1, pOffset = 0L, pVaddr = 0L, pFilesz = totalSize.toLong())
        // PHDR 1: PT_INTERP
        putPhdr64(buf, pType = 3, pOffset = interpOffset, pVaddr = interpOffset, pFilesz = interpBytes.size.toLong())
        // PHDR 2: PT_DYNAMIC
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
        buf.putInt(5) // PF_R | PF_X
        buf.putLong(pOffset)
        buf.putLong(pVaddr)
        buf.putLong(pVaddr) // p_paddr
        buf.putLong(pFilesz)
        buf.putLong(pFilesz) // p_memsz
        buf.putLong(0x1000L) // p_align
    }
}
