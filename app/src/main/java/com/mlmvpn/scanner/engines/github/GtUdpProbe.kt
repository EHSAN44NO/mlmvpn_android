package com.mlmvpn.scanner.engines.github

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Where this connection's UDP really leaves from — measured, not taken from a label.
 *
 * One STUN binding request, sent as UDP through the running connection's own SOCKS inbound (SOCKS5
 * UDP ASSOCIATE, RFC 1928 §7). The STUN server answers with the address it saw the packet come
 * from (RFC 8489 XOR-MAPPED-ADDRESS). Through a VPN Gate exit that is the exit country; through a
 * Psiphon exit, which carries no UDP, the runner sends UDP out from its own address, and that is
 * what this finds; if nothing comes back, UDP does not get through at all.
 *
 * The packets go to 127.0.0.1 from this app, which the VPN excludes — so this measures the tunnel
 * the phone's apps use, not a second path of its own.
 */
object GtUdpProbe {
    private const val COOKIE = 0x2112A442
    private val SERVERS = listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478)

    /** The public address the STUN server saw, or null when no UDP answer came back. */
    fun publicAddress(socksPort: Int, timeoutMs: Int = 4000): String? {
        for ((host, port) in SERVERS) {
            val ip = try { once(socksPort, host, port, timeoutMs) } catch (_: Exception) { null }
            if (ip != null) return ip
        }
        return null
    }

    private fun once(socksPort: Int, host: String, port: Int, timeoutMs: Int): String? {
        Socket(Proxy.NO_PROXY).use { ctl ->
            ctl.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            ctl.soTimeout = timeoutMs
            val out = ctl.getOutputStream()
            val inp = DataInputStream(ctl.getInputStream())
            // No authentication.
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val m = ByteArray(2); inp.readFully(m)
            if (m[0].toInt() != 5 || m[1].toInt() != 0) return null
            // UDP ASSOCIATE; the client's own address is not known in advance: 0.0.0.0:0.
            out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            val h = ByteArray(4); inp.readFully(h)
            if (h[0].toInt() != 5 || h[1].toInt() != 0) return null
            val bound = when (h[3].toInt()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { inp.readFully(it) })
                4 -> InetAddress.getByAddress(ByteArray(16).also { inp.readFully(it) })
                3 -> InetAddress.getByName(String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }))
                else -> return null
            }
            val relayPort = inp.readUnsignedShort()
            // "Any address" means: the one you reached me on.
            val relay = if (bound.isAnyLocalAddress) InetAddress.getByName("127.0.0.1") else bound

            val tid = GtCrypto.randomBytes(12)
            val request = ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(COOKIE).put(tid).array()
            val name = host.toByteArray(Charsets.US_ASCII)
            // RSV RSV FRAG, ATYP 3 (a name: the far end resolves it, not this phone), name, port.
            val header = byteArrayOf(0, 0, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte())
            val packet = header + request

            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { udp ->
                udp.soTimeout = timeoutMs / 2
                // UDP can drop a packet: two tries per server, the association kept open meanwhile.
                repeat(2) {
                    udp.send(DatagramPacket(packet, packet.size, relay, relayPort))
                    val buf = ByteArray(1500)
                    val dp = DatagramPacket(buf, buf.size)
                    try { udp.receive(dp) } catch (_: SocketTimeoutException) { return@repeat }
                    val off = skipSocksHeader(buf, dp.length) ?: return@repeat
                    parseStun(buf, off, dp.length - off, tid)?.let { return it }
                }
            }
        }
        return null
    }

    /** Where the payload starts after RSV(2) FRAG(1) ATYP(1) ADDR PORT(2), or null if malformed. */
    private fun skipSocksHeader(b: ByteArray, len: Int): Int? {
        if (len < 4 || b[2].toInt() != 0) return null
        val at = when (b[3].toInt()) {
            1 -> 4 + 4
            4 -> 4 + 16
            3 -> if (len > 4) 4 + 1 + (b[4].toInt() and 0xff) else return null
            else -> return null
        }
        return (at + 2).takeIf { it <= len }
    }

    /** XOR-MAPPED-ADDRESS (or plain MAPPED-ADDRESS from an old server) out of a binding success. */
    internal fun parseStun(b: ByteArray, off: Int, len: Int, tid: ByteArray): String? {
        if (len < 20) return null
        val bb = ByteBuffer.wrap(b, off, len)
        val type = bb.short.toInt() and 0xffff
        val size = bb.short.toInt() and 0xffff
        val cookie = bb.int
        val t = ByteArray(12).also { bb.get(it) }
        if (type != 0x0101 || cookie != COOKIE || !t.contentEquals(tid)) return null
        val end = minOf(off + 20 + size, off + len)
        val magic = ByteBuffer.allocate(16).putInt(COOKIE).put(tid).array()
        var plain: String? = null
        while (bb.position() + 4 <= end) {
            val at = bb.short.toInt() and 0xffff
            val al = bb.short.toInt() and 0xffff
            val start = bb.position()
            if (start + al > end) break
            if ((at == 0x0020 || at == 0x0001) && al >= 8) {
                bb.get()
                val family = bb.get().toInt()
                bb.short
                val n = if (family == 1) 4 else if (family == 2) 16 else 0
                if (n > 0 && al >= 4 + n) {
                    val a = ByteArray(n).also { bb.get(it) }
                    if (at == 0x0020) for (i in 0 until n) a[i] = (a[i].toInt() xor magic[i].toInt()).toByte()
                    val ip = InetAddress.getByAddress(a).hostAddress
                    if (at == 0x0020) return ip
                    plain = ip
                }
            }
            bb.position(start + ((al + 3) and 3.inv()))
        }
        return plain
    }
}
