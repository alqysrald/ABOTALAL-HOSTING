package com.example.engine.domain

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.AbotalalHostingApp
import com.example.core.database.AppLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class LocalDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running.get()) return
        try {
            val builder = Builder()
                .setSession("Abotalal Local DNS")
                .addAddress(VPN_CLIENT_IP, 32)
                .addDnsServer(VIRTUAL_DNS_IP)
                .addRoute(VIRTUAL_DNS_IP, 32) // Route ONLY virtual DNS traffic; normal traffic stays untouched
                .setMtu(1500)

            vpnInterface = builder.establish() ?: return
            running.set(true)
            _isDnsVpnRunning.value = true

            val app = application as AbotalalHostingApp
            scope.launch {
                app.container.database.appLogDao().insert(
                    AppLogEntity(
                        level = "INFO",
                        source = "DNS_VPN",
                        message = "تم تشغيل خادم DNS المحلي عبر Android VpnService ($VIRTUAL_DNS_IP)"
                    )
                )
            }

            readerThread = Thread(::packetLoop, "AbotalalDnsVpnThread").apply { start() }
        } catch (e: Exception) {
            _isDnsVpnRunning.value = false
            stopSelf()
        }
    }

    private fun packetLoop() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buffer = ByteArray(4096)

        while (running.get()) {
            try {
                val len = input.read(buffer)
                if (len <= 0) {
                    Thread.sleep(15)
                    continue
                }
                handleIpPacket(buffer, len, output)
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun handleIpPacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 28) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17 || length < ihl + 8) return // UDP only

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val udpStart = ihl + 8
        val dnsPayload = packet.copyOfRange(udpStart, length)
        val queriedHost = parseDnsQueryName(dnsPayload) ?: return

        val app = application as AbotalalHostingApp
        val responseDns: ByteArray? = if (isHandledLocally(app, queriedHost)) {
            buildLocalARecordResponse(dnsPayload)
        } else {
            forwardUpstreamDns(dnsPayload)
        }

        if (responseDns != null) {
            val replyPacket = wrapIpv4UdpPacket(
                srcIp = packet.copyOfRange(16, 20), // swap dst -> src
                dstIp = packet.copyOfRange(12, 16), // swap src -> dst
                srcPort = dstPort,
                dstPort = srcPort,
                payload = responseDns
            )
            synchronized(output) {
                output.write(replyPacket)
            }
        }
    }

    private fun isHandledLocally(app: AbotalalHostingApp, host: String): Boolean {
        val lower = host.lowercase()
        if (lower.endsWith(".test") || lower.endsWith(".local") || lower.endsWith(".localhost")) {
            return true
        }
        val registered = kotlinx.coroutines.runBlocking {
            app.container.database.domainDao().getByName(lower)
        }
        return registered != null && registered.isLocalDomain
    }

    private fun forwardUpstreamDns(query: ByteArray): ByteArray? {
        return try {
            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 2000
            val upstream = InetAddress.getByName("8.8.8.8")
            socket.send(DatagramPacket(query, query.size, upstream, 53))
            val respBuf = ByteArray(1500)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)
            socket.close()
            respBuf.copyOf(respPacket.length)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDnsQueryName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        var idx = 12
        val labels = mutableListOf<String>()
        while (idx < dns.size) {
            val len = dns[idx].toInt() and 0xFF
            idx++
            if (len == 0) break
            if (idx + len > dns.size) return null
            labels.add(String(dns, idx, len, Charsets.US_ASCII))
            idx += len
        }
        return if (labels.isEmpty()) null else labels.joinToString(".")
    }

    private fun buildLocalARecordResponse(query: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(query.size + 16)
        out.put(query[0]); out.put(query[1]) // Transaction ID
        out.put(0x81.toByte()); out.put(0x80.toByte()) // Standard query response, No error
        out.put(query[4]); out.put(query[5]) // QDCOUNT
        out.put(0x00); out.put(0x01) // ANCOUNT = 1
        out.put(0x00); out.put(0x00) // NSCOUNT = 0
        out.put(0x00); out.put(0x00) // ARCOUNT = 0

        // Question section copy
        var idx = 12
        while (idx < query.size) {
            val len = query[idx].toInt() and 0xFF
            out.put(query[idx])
            idx++
            if (len == 0) break
            out.put(query, idx, len)
            idx += len
        }
        if (idx + 4 <= query.size) {
            out.put(query, idx, 4) // QTYPE + QCLASS
        }

        // Answer: Pointer 0xC00C -> Type A (1), Class IN (1), TTL 60s, RDLENGTH 4, 127.0.0.1
        out.put(0xC0.toByte()); out.put(0x0C)
        out.putShort(1)
        out.putShort(1)
        out.putInt(60)
        out.putShort(4)
        out.put(127); out.put(0); out.put(0); out.put(1)

        return out.array().copyOf(out.position())
    }

    private fun wrapIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val pkt = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(pkt)
        bb.put(0x45) // IPv4, IHL 5
        bb.put(0x00)
        bb.putShort(totalLen.toShort())
        bb.putShort(1)
        bb.putShort(0x4000)
        bb.put(64) // TTL
        bb.put(17) // UDP
        bb.putShort(0) // Checksum placeholder
        bb.put(srcIp)
        bb.put(dstIp)

        // IPv4 header checksum
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val ipChecksum = sum.inv() and 0xFFFF
        pkt[10] = (ipChecksum ushr 8).toByte()
        pkt[11] = (ipChecksum and 0xFF).toByte()

        // UDP header
        bb.position(20)
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort((8 + payload.size).toShort())
        bb.putShort(0) // UDP checksum optional on IPv4
        bb.put(payload)
        return pkt
    }

    private fun stopVpn() {
        running.set(false)
        _isDnsVpnRunning.value = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.action.STOP_LOCAL_DNS_VPN"
        private const val VPN_CLIENT_IP = "10.222.0.1"
        private const val VIRTUAL_DNS_IP = "10.222.0.2"

        private val _isDnsVpnRunning = MutableStateFlow(false)
        val isDnsVpnRunning: StateFlow<Boolean> = _isDnsVpnRunning.asStateFlow()
    }
}
