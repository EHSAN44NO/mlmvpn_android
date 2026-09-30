package com.mlmvpn.scanner.openvpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

object OpenVpnLatency {
    /**
     * A server passes only when it gets through the step real connections die on.
     *
     * From Iran the OpenVPN reset is answered by servers whose session is then cut at TLS
     * (measured 2026-09-27), so a probe that stopped at the reset -- or at a TCP connect -- showed
     * a green number for servers that never connected. This one does what the connect does: the
     * reset, then the TLS ClientHello inside the control channel (for TunnelBear cut into the same
     * small segments [OpenVpnSplitRelay] uses, with the same small receive window), and waits for
     * the server's certificate flight -- a few KB. The number shown is the reset's round trip.
     */
    suspend fun measure(profile: Profile): ProbeResult = withContext(Dispatchers.IO) {
        if (ProfileRuntime.isTunnelBear(profile)) return@withContext measureTunnelBear(profile)
        val remote = profile.remotes.first()
        val tcp = remote.protocol.startsWith("tcp")
        val method = if (tcp) "OPENVPN_TCP_TLS" else "OPENVPN_UDP_TLS"
        if (profile.authenticatedControl) return@withContext measureConnectOnly(profile, tcp)
        try {
            currentCoroutineContext().ensureActive()
            val address = InetAddress.getAllByName(remote.host).firstOrNull { when {
                remote.protocol.contains('4') -> it is java.net.Inet4Address
                remote.protocol.contains('6') -> it is java.net.Inet6Address
                else -> true
            } } ?: error("No address")
            currentCoroutineContext().ensureActive()
            val r = if (tcp) handshakeTcp(address, remote.port, split = false) else handshakeUdp(address, remote.port)
            when {
                r.rttMs == null -> ProbeResult(null, System.currentTimeMillis(), method, "NO_RESPONSE")
                !r.tls -> ProbeResult(null, System.currentTimeMillis(), method, "TLS_BLOCKED")
                else -> ProbeResult(r.rttMs, System.currentTimeMillis(), method)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { ProbeResult(null, System.currentTimeMillis(), method, "NO_RESPONSE") }
    }

    /**
     * Profiles whose control packets are signed (tls-auth / tls-crypt): nothing can be sent that
     * the server would read without their key, so only the door is checked, and the method says so.
     */
    private fun measureConnectOnly(profile: Profile, tcp: Boolean): ProbeResult {
        val remote = profile.remotes.first()
        if (!tcp) return ProbeResult(null, System.currentTimeMillis(), "OPENVPN_UDP_RESET", "UNSUPPORTED_CONTROL_AUTH")
        return try {
            val address = InetAddress.getAllByName(remote.host).first()
            val start = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(address, remote.port), REPLY_MS) }
            ProbeResult(((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1), System.currentTimeMillis(), "TCP_CONNECT")
        } catch (_: Exception) { ProbeResult(null, System.currentTimeMillis(), "TCP_CONNECT", "NO_RESPONSE") }
    }

    /**
     * The route a TunnelBear connection really takes: TCP to 7011, through the same cutting the
     * relay does. Every address behind the name is tried at once; the ones that got through TLS
     * are the ones the connect goes to.
     */
    private suspend fun measureTunnelBear(profile: Profile): ProbeResult = coroutineScope {
        val method = "OPENVPN_TCP_TLS"
        // The pool behind one name: what DNS hands out now plus what answered last time. On
        // the phone (2026-09-27, 5 rounds x 47 names) whole subnets of a country stayed silent
        // every round while its other servers answered, so one lookup is a coin toss.
        val candidates = candidates(profile)
        if (candidates.isEmpty()) return@coroutineScope ProbeResult(null, System.currentTimeMillis(), method, "NO_RESPONSE")
        val results = candidates.map { ip ->
            async {
                val r = try {
                    handshakeTcp(InetAddress.getByName(ip), ProfileRuntime.TUNNELBEAR_TCP_PORT, split = true)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { Handshake(null, false) }
                ip to r
            }
        }.awaitAll()
        val passed = results.filter { it.second.tls && it.second.rttMs != null }.sortedBy { it.second.rttMs }
        when {
            passed.isNotEmpty() -> ProbeResult(passed.first().second.rttMs, System.currentTimeMillis(), method, healthy = passed.map { it.first })
            results.any { it.second.rttMs != null } -> ProbeResult(null, System.currentTimeMillis(), method, "TLS_BLOCKED")
            else -> ProbeResult(null, System.currentTimeMillis(), method, "NO_RESPONSE")
        }
    }

    /** Fresh IPv4 addresses for the profile's name first, then the ones that answered before. */
    suspend fun candidates(profile: Profile): List<String> {
        val fresh = runCatching {
            InetAddress.getAllByName(profile.remotes.first().host).filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        currentCoroutineContext().ensureActive()
        return (fresh + profile.probe?.healthy.orEmpty()).distinct().take(MAX_CANDIDATES)
    }

    private const val MAX_CANDIDATES = 6
    private const val REPLY_MS = 3_500
    /** How long the certificate flight may take to arrive after the ClientHello. */
    private const val TLS_MS = 5_000
    /** Less than this back means no certificate flight came: the relay's own rule. */
    private const val FLIGHT_BYTES = 2048

    /** [rttMs]: the reset's round trip, null when unanswered. [tls]: the server's TLS got back. */
    private data class Handshake(val rttMs: Long?, val tls: Boolean)

    private suspend fun handshakeTcp(address: InetAddress, port: Int, split: Boolean): Handshake {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            val start = System.nanoTime()
            try {
                socket.connect(InetSocketAddress(address, port), REPLY_MS)
            } catch (_: Exception) { return Handshake(null, false) }
            // The same small window the relay uses: the server's flight then comes in small
            // segments, which is how it gets through on the routes that read the reply.
            if (split) clampWindow(socket, 1024)
            socket.soTimeout = REPLY_MS
            val out = socket.getOutputStream()
            val input = java.io.DataInputStream(socket.getInputStream())
            val session = ByteArray(8).also { SecureRandom().nextBytes(it) }
            out.write(frame(OpenVpnProbePacket.request(session)))
            out.flush()
            val reply = try { readFrame(input) } catch (_: Exception) { return Handshake(null, false) }
            if (!OpenVpnProbePacket.accepts(reply, session)) return Handshake(null, false)
            val rtt = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            currentCoroutineContext().ensureActive()
            val hello = OpenVpnProbePacket.control(
                session, OpenVpnProbePacket.packetId(reply), OpenVpnProbePacket.serverSession(reply), 1, clientHello(),
            )
            val framed = frame(hello)
            if (split) writeSplit(out, framed) else { out.write(framed); out.flush() }
            // The server's answer: its TLS records inside control packets, until a flight's worth.
            val deadline = System.currentTimeMillis() + TLS_MS
            var got = 0
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceIn(1, TLS_MS)
                val pkt = try { readFrame(input) } catch (_: Exception) { break }
                val tls = OpenVpnProbePacket.controlPayload(pkt) ?: continue
                // An alert is an answer too: the server read our TLS, so the path is open.
                if (tls.isNotEmpty() && tls[0].toInt() == 0x15) return Handshake(rtt, true)
                got += tls.size
                if (got >= FLIGHT_BYTES) return Handshake(rtt, true)
            }
            return Handshake(rtt, false)
        }
    }

    private suspend fun handshakeUdp(address: InetAddress, port: Int): Handshake {
        DatagramSocket().use { socket ->
            socket.connect(address, port)
            val session = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val start = System.nanoTime()
            val request = OpenVpnProbePacket.request(session)
            socket.send(DatagramPacket(request, request.size))
            val deadline = System.nanoTime() + REPLY_MS * 1_000_000L
            var reply: ByteArray? = null
            while (reply == null && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).toInt().coerceIn(1, REPLY_MS)
                val packet = DatagramPacket(ByteArray(2048), 2048)
                try { socket.receive(packet) } catch (_: Exception) { break }
                val data = packet.data.copyOf(packet.length)
                if (OpenVpnProbePacket.accepts(data, session)) reply = data
            }
            val r = reply ?: return Handshake(null, false)
            val rtt = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            val hello = OpenVpnProbePacket.control(session, OpenVpnProbePacket.packetId(r), OpenVpnProbePacket.serverSession(r), 1, clientHello())
            socket.send(DatagramPacket(hello, hello.size))
            val until = System.currentTimeMillis() + TLS_MS
            var got = 0
            while (System.currentTimeMillis() < until) {
                currentCoroutineContext().ensureActive()
                socket.soTimeout = (until - System.currentTimeMillis()).toInt().coerceIn(1, TLS_MS)
                val packet = DatagramPacket(ByteArray(4096), 4096)
                try { socket.receive(packet) } catch (_: Exception) { break }
                val tls = OpenVpnProbePacket.controlPayload(packet.data.copyOf(packet.length)) ?: continue
                if (tls.isNotEmpty() && tls[0].toInt() == 0x15) return Handshake(rtt, true)
                got += tls.size
                if (got >= FLIGHT_BYTES) return Handshake(rtt, true)
            }
            return Handshake(rtt, false)
        }
    }

    private fun frame(packet: ByteArray) = byteArrayOf((packet.size ushr 8).toByte(), packet.size.toByte()) + packet

    private fun readFrame(input: java.io.DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        check(length in 1..4096)
        return ByteArray(length).also { input.readFully(it) }
    }

    /**
     * The relay's cutting: the first byte alone, then pieces of 3-29 bytes a few milliseconds
     * apart, so no segment starts on a record boundary a matcher can key on.
     */
    private fun writeSplit(out: java.io.OutputStream, bytes: ByteArray) {
        val rng = SecureRandom()
        var at = 0
        var first = true
        while (at < bytes.size) {
            val size = if (first) 1 else 3 + rng.nextInt(27)
            first = false
            val end = minOf(bytes.size, at + size)
            out.write(bytes, at, end - at)
            out.flush()
            at = end
            if (at < bytes.size) Thread.sleep(2L + rng.nextInt(6))
        }
    }

    private fun clampWindow(socket: Socket, bytes: Int) {
        runCatching {
            android.os.ParcelFileDescriptor.fromSocket(socket).use { pfd ->
                android.system.Os.setsockoptInt(pfd.fileDescriptor, 6 /* IPPROTO_TCP */, 10 /* TCP_WINDOW_CLAMP */, bytes)
            }
        }
    }

    private val tlsContext by lazy { javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, null, null) } }

    /** A real TLS ClientHello from the platform's own stack: a fresh one every time. */
    private fun clientHello(): ByteArray {
        val engine = tlsContext.createSSLEngine().apply { useClientMode = true }
        val out = java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.beginHandshake()
        engine.wrap(java.nio.ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }
}

object ServerPolicy {
    fun fastest(profiles: List<Profile>, now: Long): Profile? = profiles.filter {
        it.probe?.let { p -> p.millis != null && p.error == null && now - p.checkedAt in 0..600_000L } == true
    }.minByOrNull { it.probe!!.millis!! }
}
