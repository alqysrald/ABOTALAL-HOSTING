package com.example.engine.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ElfInspectionResult(
    val isValidElf: Boolean,
    val bitness: Int,
    val elfType: String,
    val machineArch: String,
    val abiName: String,
    val interpreterPath: String?,
    val isBionicLinker: Boolean,
    val isGlibcLinker: Boolean,
    val neededLibraries: List<String>,
    val runpathEntries: List<String>,
    val missingLibraries: List<String>,
    val diagnosticSummary: String
)

object ElfBinaryInspector {

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val PT_INTERP = 3

    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L
    private const val DT_RPATH = 15L
    private const val DT_RUNPATH = 29L

    fun inspect(
        binaryFile: File,
        expectedAbis: List<String> = listOf("arm64-v8a"),
        librarySearchDirs: List<File> = emptyList()
    ): ElfInspectionResult {
        if (!binaryFile.exists() || !binaryFile.isFile || binaryFile.length() < 64L) {
            return invalidResult("الملف غير موجود أو أصغر من ترويسة ELF")
        }

        return try {
            RandomAccessFile(binaryFile, "r").use { raf ->
                val ident = ByteArray(16)
                raf.readFully(ident)
                if (ident[0] != 0x7F.toByte() ||
                    ident[1] != 'E'.code.toByte() ||
                    ident[2] != 'L'.code.toByte() ||
                    ident[3] != 'F'.code.toByte()
                ) {
                    val shebang = String(ident, Charsets.US_ASCII)
                    val note = if (shebang.startsWith("#!")) "نص برمجي (Script) وليس ELF ثنائي" else "ترويسة ليست ELF صالحة"
                    return invalidResult(note)
                }

                val eiClass = ident[4].toInt() and 0xFF // 1 = 32-bit, 2 = 64-bit
                val eiData = ident[5].toInt() and 0xFF  // 1 = LSB, 2 = MSB
                val order = if (eiData == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val bitness = if (eiClass == 2) 64 else if (eiClass == 1) 32 else 0
                if (bitness == 0) return invalidResult("فئة ELF غير مدعومة (eiClass=$eiClass)")

                raf.seek(0)
                val headerSize = if (bitness == 64) 64 else 52
                val hdr = ByteArray(headerSize)
                raf.readFully(hdr)
                val bb = ByteBuffer.wrap(hdr).order(order)

                val eType = bb.getShort(16).toInt() and 0xFFFF
                val eMachine = bb.getShort(18).toInt() and 0xFFFF

                val phoff: Long
                val phentsize: Int
                val phnum: Int
                if (bitness == 64) {
                    phoff = bb.getLong(32)
                    phentsize = bb.getShort(54).toInt() and 0xFFFF
                    phnum = bb.getShort(56).toInt() and 0xFFFF
                } else {
                    phoff = (bb.getInt(28).toLong() and 0xFFFFFFFFL)
                    phentsize = bb.getShort(42).toInt() and 0xFFFF
                    phnum = bb.getShort(44).toInt() and 0xFFFF
                }

                val (machineLabel, abiName) = mapMachineToAbi(eMachine)
                val elfTypeLabel = when (eType) {
                    2 -> "ET_EXEC"
                    3 -> "ET_DYN (PIE / Shared)"
                    else -> "TYPE_$eType"
                }

                var interpPath: String? = null
                var dynOffset = -1L
                var dynSize = 0L
                val loadSegments = mutableListOf<Triple<Long, Long, Long>>() // vaddr, offset, filesz

                if (phoff > 0 && phnum > 0 && phentsize >= (if (bitness == 64) 56 else 32) && phoff + phnum.toLong() * phentsize <= raf.length()) {
                    for (i in 0 until phnum) {
                        raf.seek(phoff + i.toLong() * phentsize)
                        val phBytes = ByteArray(phentsize)
                        raf.readFully(phBytes)
                        val pbb = ByteBuffer.wrap(phBytes).order(order)
                        val pType = pbb.getInt(0)

                        val pOffset: Long
                        val pVaddr: Long
                        val pFilesz: Long
                        if (bitness == 64) {
                            pOffset = pbb.getLong(8)
                            pVaddr = pbb.getLong(16)
                            pFilesz = pbb.getLong(32)
                        } else {
                            pOffset = pbb.getInt(4).toLong() and 0xFFFFFFFFL
                            pVaddr = pbb.getInt(8).toLong() and 0xFFFFFFFFL
                            pFilesz = pbb.getInt(16).toLong() and 0xFFFFFFFFL
                        }

                        when (pType) {
                            PT_LOAD -> loadSegments.add(Triple(pVaddr, pOffset, pFilesz))
                            PT_INTERP -> if (pFilesz in 1..1024 && pOffset + pFilesz <= raf.length()) {
                                val raw = ByteArray(pFilesz.toInt())
                                raf.seek(pOffset)
                                raf.readFully(raw)
                                val zeroIdx = raw.indexOf(0.toByte())
                                val cleanLen = if (zeroIdx >= 0) zeroIdx else raw.size
                                interpPath = String(raw, 0, cleanLen, Charsets.UTF_8).trim()
                            }
                            PT_DYNAMIC -> {
                                dynOffset = pOffset
                                dynSize = pFilesz
                            }
                        }
                    }
                }

                val neededIdx = mutableListOf<Long>()
                val runpathIdx = mutableListOf<Long>()
                var strTabVaddr = -1L
                var strTabSize = 0L

                if (dynOffset > 0 && dynSize > 0 && dynOffset + dynSize <= raf.length()) {
                    val entrySize = if (bitness == 64) 16 else 8
                    val maxEntries = (dynSize / entrySize).toInt().coerceAtMost(4096)
                    val dynBuf = ByteArray(maxEntries * entrySize)
                    raf.seek(dynOffset)
                    raf.readFully(dynBuf)
                    val dbb = ByteBuffer.wrap(dynBuf).order(order)
                    for (i in 0 until maxEntries) {
                        val base = i * entrySize
                        val tag = if (bitness == 64) dbb.getLong(base) else dbb.getInt(base).toLong()
                        val valField = if (bitness == 64) dbb.getLong(base + 8) else (dbb.getInt(base + 4).toLong() and 0xFFFFFFFFL)
                        if (tag == DT_NULL) break
                        when (tag) {
                            DT_NEEDED -> neededIdx.add(valField)
                            DT_STRTAB -> strTabVaddr = valField
                            DT_STRSZ -> strTabSize = valField
                            DT_RPATH, DT_RUNPATH -> runpathIdx.add(valField)
                        }
                    }
                }

                val dynStrBytes = if (strTabVaddr >= 0 && strTabSize in 1..(4 * 1024 * 1024)) {
                    val fileOffset = mapVaddrToFileOffset(strTabVaddr, loadSegments)
                    if (fileOffset != null && fileOffset + strTabSize <= raf.length()) {
                        ByteArray(strTabSize.toInt()).also {
                            raf.seek(fileOffset)
                            raf.readFully(it)
                        }
                    } else null
                } else null

                val neededLibs = neededIdx.mapNotNull { readCString(dynStrBytes, it.toInt()) }
                val runpaths = runpathIdx
                    .mapNotNull { readCString(dynStrBytes, it.toInt()) }
                    .flatMap { it.split(':') }
                    .filter { it.isNotBlank() }

                val isBionic = interpPath == "/system/bin/linker64" ||
                    interpPath == "/system/bin/linker" ||
                    neededLibs.contains("libc.so")
                val isGlibc = (interpPath?.contains("ld-linux") == true) ||
                    neededLibs.any { it.startsWith("libc.so.6") }

                val searchDirs = (librarySearchDirs + defaultSystemLibDirs(bitness)).distinct()
                val missing = neededLibs.filter { libName ->
                    searchDirs.none { dir -> File(dir, libName).exists() }
                }

                val abiMatches = expectedAbis.any { it.equals(abiName, ignoreCase = true) }
                val summary = buildString {
                    append("ELF$bitness ($abiName / $machineLabel)")
                    if (interpPath != null) append(" • Linker: $interpPath")
                    if (isGlibc) append(" • تحذير: مبني لـ Linux glibc وليس Android Bionic!")
                    else if (isBionic) append(" • Android Bionic متوافق")
                    if (!abiMatches) append(" • تعارض ABI مع الجهاز (${expectedAbis.joinToString()})")
                    if (missing.isNotEmpty()) append(" • مكتبات مفقودة (${missing.size}): ${missing.take(4).joinToString()}")
                }

                ElfInspectionResult(
                    isValidElf = true,
                    bitness = bitness,
                    elfType = elfTypeLabel,
                    machineArch = machineLabel,
                    abiName = abiName,
                    interpreterPath = interpPath,
                    isBionicLinker = isBionic && !isGlibc,
                    isGlibcLinker = isGlibc,
                    neededLibraries = neededLibs,
                    runpathEntries = runpaths,
                    missingLibraries = missing,
                    diagnosticSummary = summary
                )
            }
        } catch (e: Exception) {
            invalidResult("خطأ في تحليل ترويسة ELF: ${e.message}")
        }
    }

    private fun mapMachineToAbi(eMachine: Int): Pair<String, String> = when (eMachine) {
        183 -> "EM_AARCH64" to "arm64-v8a"
        40 -> "EM_ARM" to "armeabi-v7a"
        62 -> "EM_X86_64" to "x86_64"
        3 -> "EM_386" to "x86"
        else -> "EM_$eMachine" to "unknown"
    }

    private fun mapVaddrToFileOffset(
        vaddr: Long,
        segments: List<Triple<Long, Long, Long>>
    ): Long? {
        for ((segVaddr, segOffset, segFilesz) in segments) {
            if (vaddr >= segVaddr && vaddr < segVaddr + segFilesz) {
                return segOffset + (vaddr - segVaddr)
            }
        }
        return if (vaddr >= 0) vaddr else null
    }

    private fun readCString(table: ByteArray?, offset: Int): String? {
        if (table == null || offset < 0 || offset >= table.size) return null
        var end = offset
        while (end < table.size && table[end] != 0.toByte()) end++
        return String(table, offset, end - offset, Charsets.UTF_8)
    }

    private fun defaultSystemLibDirs(bitness: Int): List<File> = if (bitness == 64) {
        listOf(
            File("/system/lib64"),
            File("/apex/com.android.runtime/lib64/bionic"),
            File("/apex/com.android.art/lib64"),
            File("/vendor/lib64")
        )
    } else {
        listOf(
            File("/system/lib"),
            File("/apex/com.android.runtime/lib/bionic"),
            File("/vendor/lib")
        )
    }

    private fun invalidResult(reason: String) = ElfInspectionResult(
        isValidElf = false,
        bitness = 0,
        elfType = "INVALID",
        machineArch = "UNKNOWN",
        abiName = "unknown",
        interpreterPath = null,
        isBionicLinker = false,
        isGlibcLinker = false,
        neededLibraries = emptyList(),
        runpathEntries = emptyList(),
        missingLibraries = emptyList(),
        diagnosticSummary = reason
    )
}
