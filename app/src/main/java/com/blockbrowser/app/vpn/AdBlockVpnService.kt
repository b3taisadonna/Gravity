package com.blockbrowser.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.blockbrowser.app.MainActivity
import com.blockbrowser.app.R
import com.blockbrowser.app.browser.AdBlockManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * IMPORTANT - read this before relying on it:
 *
 * This is a *local, DNS-sinkhole* VPN, in the same family as DNS66 / Blokada's
 * free mode. It does NOT:
 *   - hide your IP address or location
 *   - encrypt/tunnel your general traffic
 *   - route you through a remote server in another country
 *
 * What it DOES do: it becomes the device's DNS resolver, so it can refuse to
 * resolve known ad/tracker domains system-wide (not just inside this browser).
 * A real privacy VPN needs a remote VPN server/provider behind it - that is
 * infrastructure this project does not include.
 *
 * Scope of this reference implementation: IPv4, UDP/53 plain DNS only (DNS-over-
 * HTTPS/TLS from apps that hardcode their own resolver will bypass it, same
 * limitation every no-root DNS blocker has).
 */
class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false
    private val upstreamDns = InetSocketAddress("1.1.1.1", 53)

    companion object {
        private const val TAG = "AdBlockVpnService"
        private const val CHANNEL_ID = "adblock_vpn_channel"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.gravity.browser.vpn.STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        AdBlockManager.ensureLoaded(applicationContext)

        val builder = Builder()
            .setSession("Gravity Ad-Block")
            .addAddress("10.111.222.1", 32)
            .addDnsServer("10.111.222.1")
            .addRoute("10.111.222.1", 32) // only DNS traffic is routed through the tunnel
            .setMtu(1500)

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "VPN establish() returned null - permission not granted?")
            return
        }
        running = true

        thread(name = "adblock-vpn-loop") { runLoop() }
    }

    private fun stopVpn() {
        running = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun runLoop() {
        val iface = vpnInterface ?: return
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "tun read failed", e)
                break
            }
            if (length <= 0) continue

            try {
                handlePacket(buffer, length, output)
            } catch (e: Exception) {
                Log.w(TAG, "packet handling failed", e)
            }
        }
    }

    /** Parses an IPv4/UDP/DNS query, resolves or blocks it, writes the reply back into the tun. */
    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        val ipVersion = (packet[0].toInt() shr 4) and 0xF
        if (ipVersion != 4) return // IPv6 not handled in this reference implementation

        val ihl = (packet[0].toInt() and 0xF) * 4
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only (17). TCP DNS fallback not handled here.

        val udpStart = ihl
        val srcPort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (dstPort != 53) return // only intercept DNS

        val dnsStart = udpStart + 8
        val dnsLength = length - dnsStart
        if (dnsLength <= 12) return

        val queryName = parseDnsQuestionName(packet, dnsStart) ?: return

        if (AdBlockManager.isBlockedHost(queryName)) {
            val reply = buildSinkholeDnsReply(packet, length, ihl, udpStart, dnsStart, dnsLength)
            output.write(reply)
        } else {
            forwardToRealDns(packet, length, ihl, udpStart, dnsStart, dnsLength, srcPort, output)
        }
    }

    /** Extracts the queried domain name from a raw DNS question section (very small parser). */
    private fun parseDnsQuestionName(packet: ByteArray, dnsStart: Int): String? {
        var pos = dnsStart + 12 // skip the 12-byte DNS header
        val sb = StringBuilder()
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) break
            if (sb.isNotEmpty()) sb.append('.')
            pos++
            if (pos + len > packet.size) return null
            sb.append(String(packet, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    /** Builds a DNS response answering the blocked query with 0.0.0.0, wrapped back in IP/UDP,
     *  with source/destination swapped so it looks like a reply from the "DNS server" (us). */
    private fun buildSinkholeDnsReply(
        original: ByteArray,
        length: Int,
        ihl: Int,
        udpStart: Int,
        dnsStart: Int,
        dnsQueryLength: Int
    ): ByteArray {
        // DNS answer: copy the question, flip QR bit to response, add one A record -> 0.0.0.0
        val header = ByteArray(12)
        System.arraycopy(original, dnsStart, header, 0, 12)
        header[2] = (header[2].toInt() or 0x80).toByte() // QR = 1 (response)
        header[3] = (header[3].toInt() and 0x0F.inv()).toByte() // clear RCODE (no error)
        header[7] = 1 // ANCOUNT = 1 (big-endian low byte)
        header[6] = 0

        val question = original.copyOfRange(dnsStart + 12, dnsStart + dnsQueryLength)
        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte(); answer[1] = 0x0C // pointer to name at offset 12
        answer[2] = 0x00; answer[3] = 0x01 // TYPE A
        answer[4] = 0x00; answer[5] = 0x01 // CLASS IN
        // TTL = 60s
        answer[6] = 0; answer[7] = 0; answer[8] = 0; answer[9] = 60
        answer[10] = 0x00; answer[11] = 0x04 // RDLENGTH = 4
        answer[12] = 0; answer[13] = 0; answer[14] = 0; answer[15] = 0 // 0.0.0.0

        val dnsReply = header + question + answer

        return wrapUdpIpv4(
            original, udpStart, dnsReply,
            swapSrcDst = true
        )
    }

    /** Sends the (allowed) query to a real upstream DNS resolver and relays the answer back. */
    private fun forwardToRealDns(
        original: ByteArray,
        length: Int,
        ihl: Int,
        udpStart: Int,
        dnsStart: Int,
        dnsQueryLength: Int,
        srcPort: Int,
        output: FileOutputStream
    ) {
        val socket = DatagramSocket()
        protect(socket) // exclude this socket from the VPN so it doesn't loop back to itself
        socket.soTimeout = 4000
        try {
            val query = original.copyOfRange(dnsStart, dnsStart + dnsQueryLength)
            socket.send(DatagramPacket(query, query.size, upstreamDns))

            val respBuf = ByteArray(1024)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)

            val dnsReply = respBuf.copyOf(respPacket.length)
            val ipPacket = wrapUdpIpv4(original, udpStart, dnsReply, swapSrcDst = true)
            output.write(ipPacket)
        } catch (_: Exception) {
            // Upstream timeout/unreachable - silently drop, matching normal DNS failure behavior.
        } finally {
            socket.close()
        }
    }

    /** Wraps a UDP payload (e.g. a DNS reply) into a new IPv4+UDP packet, reusing the original
     *  packet's addresses/ports (optionally swapped, to turn a query's envelope into a reply). */
    private fun wrapUdpIpv4(
        original: ByteArray,
        udpStart: Int,
        udpPayload: ByteArray,
        swapSrcDst: Boolean
    ): ByteArray {
        val ihl = 20 // we always emit a minimal 20-byte IPv4 header for replies
        val udpLen = 8 + udpPayload.size
        val totalLen = ihl + udpLen
        val out = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(out)

        // --- IPv4 header ---
        buf.put(0, (0x45).toByte()) // version 4, IHL 5 (20 bytes)
        buf.put(1, 0)
        buf.putShort(2, totalLen.toShort())
        buf.putShort(4, 0) // identification
        buf.putShort(6, 0) // flags/fragment
        buf.put(8, 64) // TTL
        buf.put(9, 17) // protocol = UDP

        // Copy original src/dst IPs, swapped (so the reply appears to come from the queried server)
        System.arraycopy(original, 12, out, 16, 4) // original src -> new dst
        System.arraycopy(original, 16, out, 12, 4) // original dst -> new src

        buf.putShort(10, 0) // checksum placeholder, computed below

        // --- UDP header ---
        val origSrcPort = ((original[udpStart].toInt() and 0xFF) shl 8) or (original[udpStart + 1].toInt() and 0xFF)
        val origDstPort = ((original[udpStart + 2].toInt() and 0xFF) shl 8) or (original[udpStart + 3].toInt() and 0xFF)
        val newSrcPort = if (swapSrcDst) origDstPort else origSrcPort
        val newDstPort = if (swapSrcDst) origSrcPort else origDstPort

        buf.putShort(ihl, newSrcPort.toShort())
        buf.putShort(ihl + 2, newDstPort.toShort())
        buf.putShort(ihl + 4, udpLen.toShort())
        buf.putShort(ihl + 6, 0) // UDP checksum = 0 (optional for IPv4, accepted by the kernel's tun)
        System.arraycopy(udpPayload, 0, out, ihl + 8, udpPayload.size)

        // IPv4 header checksum
        val checksum = ipv4HeaderChecksum(out, ihl)
        buf.putShort(10, checksum.toShort())

        return out
    }

    private fun ipv4HeaderChecksum(packet: ByteArray, ihl: Int): Int {
        var sum = 0
        var i = 0
        while (i < ihl) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ad-Block VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gravity Ad-Block aktiv")
            .setContentText("Blockiert: ${AdBlockManager.blockedCount()} Domains")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }
}
