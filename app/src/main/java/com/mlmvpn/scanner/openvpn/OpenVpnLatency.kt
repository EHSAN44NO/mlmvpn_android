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
    suspend fun measure(profile: Profile): ProbeResult = withContext(Dispatchers.IO) {
        if (ProfileRuntime.isTunnelBear(profile)) return@withContext measureTcpReset(profile)
        val remote = profile.remotes.first()
        val tcp = remote.protocol.startsWith("tcp")
        val method = if (tcp) "TCP_CONNECT" else "OPENVPN_UDP_RESET"
        if (!tcp && profile.authenticatedControl) return@withContext ProbeResult(null, System.currentTimeMillis(), method, "UNSUPPORTED_CONTROL_AUTH")
        try {
            currentCoroutineContext().ensureActive()
            val addresses = InetAddress.getAllByName(remote.host)
            val address = addresses.firstOrNull { when {
                remote.protocol.contains('4') -> it is java.net.Inet4Address
                remote.protocol.contains('6') -> it is java.net.Inet6Address
                else -> true
            } } ?: error("No address")
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            if (tcp) {
                Socket().use { it.connect(InetSocketAddress(address, remote.port), 3500) }
            } else {
                DatagramSocket().use { socket ->
                    socket.connect(address, remote.port)
                    val session = ByteArray(8).also { SecureRandom().nextBytes(it) }
                    val request = OpenVpnProbePacket.request(session)
                    socket.send(DatagramPacket(request, request.size))
                    val deadline = System.nanoTime() + 3_500_000_000L
                    var accepted = false
                    while (!accepted && System.nanoTime() < deadline) {
                        currentCoroutineContext().ensureActive()
                        socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).toInt().coerceIn(1, 3500)
                        val packet = DatagramPacket(ByteArray(2048), 2048)
                        socket.receive(packet)
                        accepted = OpenVpnProbePacket.accepts(packet.data.copyOf(packet.length), session)
                    }
                    check(accepted)
                }
            }
            currentCoroutineContext().ensureActive()
            ProbeResult(((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1), System.currentTimeMillis(), method)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { ProbeResult(null, System.currentTimeMillis(), method, "NO_RESPONSE") }
    }

    /**
     * The route a TunnelBear connection really takes: TCP to 7011 and an OpenVPN reset answered
     * on it. The old UDP-443 probe was answered from Iran by servers whose sessions were then
     * cut, so a green number meant nothing; this one times the same door the connect uses.
     */
    private suspend fun measureTcpReset(profile: Profile): ProbeResult = coroutineScope {
        val method = "OPENVPN_TCP_RESET"
        // The pool behind one name: what DNS hands out now plus what answered last time. On
        // the phone (2026-09-27, 5 rounds x 47 names) whole subnets of a country stayed silent
        // every round while its other servers answered, so one lookup is a coin toss.
        val candidates = candidates(profile)
        if (candidates.isEmpty()) return@coroutineScope ProbeResult(null, System.currentTimeMillis(), method, "NO_RESPONSE")
        val timed = candidates.map { ip -> async { ip to resetMillis(ip) } }.awaitAll()
            .mapNotNull { (ip, ms) -> ms?.let { ip to it } }.sortedBy { it.second }
        if (timed.isEmpty()) ProbeResult(null, System.currentTimeMillis(), method, "NO_RESPONSE")
        else ProbeResult(timed.first().second, System.currentTimeMillis(), method, healthy = timed.map { it.first })
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

    /** One reset over TCP to [ip]; its round trip, or null when nothing valid came back. */
    private suspend fun resetMillis(ip: String): Long? {
        return try {
            val address = InetAddress.getByName(ip)
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, ProfileRuntime.TUNNELBEAR_TCP_PORT), 3500)
                socket.soTimeout = 3500
                val session = ByteArray(8).also { SecureRandom().nextBytes(it) }
                val packet = OpenVpnProbePacket.request(session)
                socket.getOutputStream().apply {
                    write(byteArrayOf(0, packet.size.toByte()) + packet)
                    flush()
                }
                val input = java.io.DataInputStream(socket.getInputStream())
                val length = input.readUnsignedShort()
                check(length in 26..2048)
                val reply = ByteArray(length).also { input.readFully(it) }
                check(OpenVpnProbePacket.accepts(reply, session))
            }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }
}

object ServerPolicy {
    fun fastest(profiles: List<Profile>, now: Long): Profile? = profiles.filter {
        it.probe?.let { p -> p.millis != null && p.error == null && now - p.checkedAt in 0..600_000L } == true
    }.minByOrNull { it.probe!!.millis!! }
}
