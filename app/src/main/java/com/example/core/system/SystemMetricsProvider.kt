package com.example.core.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.example.core.model.SystemMetrics
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class SystemMetricsProvider(private val context: Context) {

    private var lastAppCpuTimeNs: Long = 0L
    private var lastWallTimeNs: Long = 0L
    private var lastTotalCpuTicks: Long = 0L
    private var lastIdleCpuTicks: Long = 0L

    fun collectMetrics(activeConnections: Int, isServerRunning: Boolean): SystemMetrics {
        val (ramUsedMb, ramTotalMb) = readMemoryInfo()
        val (storageUsed, storageFree, storageTotal) = readStorageInfo()
        val cpuPercent = readCpuUsagePercent()

        return SystemMetrics(
            cpuUsagePercent = cpuPercent,
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
            storageUsedBytes = storageUsed,
            storageFreeBytes = storageFree,
            storageTotalBytes = storageTotal,
            activeConnections = activeConnections,
            localIpAddress = resolveLocalIpv4Address(),
            deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            isServerRunning = isServerRunning
        )
    }

    private fun readMemoryInfo(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
        return usedMb to totalMb
    }

    private fun readStorageInfo(): Triple<Long, Long, Long> {
        val stat = StatFs(context.filesDir.absolutePath)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = (total - free).coerceAtLeast(0L)
        return Triple(used, free, total)
    }

    private fun readCpuUsagePercent(): Float {
        // Attempt system-wide /proc/stat first; fall back to process CPU time on restricted kernels.
        try {
            val procStat = File("/proc/stat")
            if (procStat.canRead()) {
                val firstLine = procStat.bufferedReader().use { it.readLine() } ?: ""
                if (firstLine.startsWith("cpu ")) {
                    val parts = firstLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (parts.size >= 4) {
                        val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
                        val total = parts.sum()
                        val dTotal = total - lastTotalCpuTicks
                        val dIdle = idle - lastIdleCpuTicks
                        lastTotalCpuTicks = total
                        lastIdleCpuTicks = idle
                        if (dTotal > 0L) {
                            return (((dTotal - dIdle).toFloat() / dTotal.toFloat()) * 100f).coerceIn(0f, 100f)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // /proc/stat is restricted on newer Android kernels; fallback to process/JVM CPU load below.
        }

        return try {
            val nowWall = System.nanoTime()
            val threadCpu = android.os.Process.getElapsedCpuTime() * 1_000_000L
            val dWall = nowWall - lastWallTimeNs
            val dCpu = threadCpu - lastAppCpuTimeNs
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            lastWallTimeNs = nowWall
            lastAppCpuTimeNs = threadCpu
            if (dWall > 0L && dCpu >= 0L) {
                ((dCpu.toFloat() / (dWall.toFloat() * cores)) * 100f).coerceIn(0f, 100f)
            } else {
                0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    fun resolveLocalIpv4Address(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
