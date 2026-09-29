package com.mlmvpn.scanner.openvpn

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A direct OpenVPN-over-TCP connection that the filter cannot read at the start.
 *
 * Measured 2026-09-27 from Iran: a TunnelBear server answers the OpenVPN reset on UDP 443 and on
 * TCP 7011, and then nothing more comes back -- the TLS handshake inside OpenVPN's control
 * channel is cut. The same server completes that handshake from abroad. What gets recognised is
 * the shape of the first records: each TCP segment starts with OpenVPN's 2-byte length and an
 * opcode byte, in a fixed sequence of sizes.
 *
 * So the core does not dial the server itself. It dials this relay on loopback, and the relay
 * dials the server DIRECTLY -- no second tunnel, no proxy, nothing in between -- and writes the
 * first [SPLIT_BYTES] of the client's stream in small segments cut at random offsets, with a few
 * milliseconds between them. No segment starts on a record boundary, so no segment carries a
 * length+opcode header a per-packet matcher can key on. TCP delivers the same byte stream to the
 * server, which therefore sees an ordinary client. Past that budget the relay is a plain copy in
 * both directions, so the session runs at direct speed.
 */
class OpenVpnSplitRelay(
    private val service: VpnService,
    /**
     * Where the server may be, best first: the addresses the delay test saw answer, then the
     * profile's name. A connection that brings nothing back moves the next one to the front, so
     * the core's own retry lands on another server instead of the same silent one.
     */
    private val hosts: List<String>,
    private val port: Int,
) {
    init { require(hosts.isNotEmpty()) }
    @Volatile private var next = 0
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val localPort: Int get() = server.localPort
    @Volatile private var closed = false
    private val open = mutableListOf<Socket>()

    fun start() {
        thread(name = "ovpn-relay-accept", isDaemon = true) {
            while (!closed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "ovpn-relay-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        synchronized(open) { open += client }
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val (host, up) = connect()
            upstream = up
            val t0 = System.currentTimeMillis()
            Log.i(TAG, "relay connected to $host:$port")
            // The other direction. On some routes (France, 2026-09-27) our ClientHello arrives --
            // the server acknowledges it -- and its certificate flight never comes back: the
            // filter reads the server's reply. A small receive window makes the server send that
            // flight in small segments. Set after connect, so the window scale agreed in the SYN
            // stays the normal one and the clamp can be lifted once the handshake is through.
            val clamped = clamp(up, HANDSHAKE_WINDOW)
            val trace = Trace(t0)
            // Both helper threads catch everything. They run beside the main pump, and a socket
            // the other side (or stop()) has already closed makes even getInputStream() throw --
            // "IOException: Socket Closed" on an uncaught thread killed the whole app (crash
            // reports from Android 8, 2026-09-29, where that ordering is common on slower phones).
            if (clamped) thread(name = "ovpn-relay-unclamp", isDaemon = true) {
                try {
                    while (!closed && trace.downBytes < UNCLAMP_AFTER_BYTES &&
                        System.currentTimeMillis() - t0 < UNCLAMP_AFTER_MS) Thread.sleep(100)
                    clamp(up, OPEN_WINDOW)
                    Log.i(TAG, "window opened after ${System.currentTimeMillis() - t0} ms, ${trace.downBytes} B down")
                } catch (e: Exception) {
                    Log.w(TAG, "unclamp: ${e.message}")
                }
            }
            thread(name = "ovpn-relay-down", isDaemon = true) {
                try {
                    pump(up.getInputStream(), client.getOutputStream(), trace)
                    if (trace.downBytes < HANDSHAKE_BYTES) skip(host)
                    Log.i(TAG, "server closed after ${System.currentTimeMillis() - t0} ms; ${trace.summary()}")
                } catch (e: Exception) {
                    Log.w(TAG, "relay down: ${e.message}")
                } finally {
                    runCatching { client.shutdownOutput() }
                }
            }
            upSplit(client.getInputStream(), up.getOutputStream(), trace)
            // The core gave up on a server that answered at most the reset: the known cut.
            if (trace.downBytes < HANDSHAKE_BYTES) skip(host)
            Log.i(TAG, "client closed after ${System.currentTimeMillis() - t0} ms; ${trace.summary()}")
            runCatching { up.shutdownOutput() }
        } catch (e: Exception) {
            Log.w(TAG, "relay: ${e.message}")
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /**
     * The first address that takes a TCP connection, starting at the current best. A socket
     * whose connect failed is closed for good, so every attempt gets a fresh one.
     */
    private fun connect(): Pair<String, Socket> {
        var last: Exception? = null
        val start = next
        for (i in hosts.indices) {
            if (closed) break
            val at = (start + i) % hosts.size
            val socket = Socket()
            synchronized(open) { open += socket }
            try {
                socket.tcpNoDelay = true
                // Outside any tunnel, even if one is up: this socket IS the direct path.
                service.protect(socket)
                socket.connect(InetSocketAddress(InetAddress.getByName(hosts[at]), port), if (hosts.size > 1) FAILOVER_CONNECT_MS else CONNECT_MS)
                next = at
                return hosts[at] to socket
            } catch (e: java.io.IOException) {
                last = e
                Log.w(TAG, "connect ${hosts[at]}: ${e.message}")
                runCatching { socket.close() }
                synchronized(open) { open -= socket }
            }
        }
        throw last ?: java.io.IOException("relay closed")
    }

    /** [host] never got past the handshake: try the next address next time. */
    private fun skip(host: String) {
        if (hosts.size > 1 && hosts[next] == host) {
            next = (next + 1) % hosts.size
            Log.i(TAG, "no reply from $host; next connection goes to ${hosts[next]}")
        }
    }

    /** Client -> server: the first [SPLIT_BYTES] cut up, then a plain copy. */
    private fun upSplit(input: InputStream, output: OutputStream, trace: Trace) {
        val rng = SecureRandom()
        val buf = ByteArray(16 * 1024)
        var budget = SPLIT_BYTES
        var first = true
        // The opening record -- the reset -- goes out whole. The filter lets a reset through (a
        // bare one is answered from Iran on every server), and cutting it into a 1-byte segment
        // made some routes (France, 2026-09-27) never answer at all. What gets recognised is
        // what follows, so the cutting starts there.
        var resetLeft = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            trace.up(n)
            var at = 0
            if (resetLeft != 0) {
                if (resetLeft < 0) {
                    if (n < 2) { output.write(buf, 0, n); output.flush(); resetLeft = 0; continue }
                    resetLeft = 2 + (((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff))
                }
                val whole = minOf(resetLeft, n)
                output.write(buf, 0, whole)
                output.flush()
                resetLeft -= whole
                at = whole
                if (at >= n) continue
            }
            if (budget <= 0) { output.write(buf, at, n - at); output.flush(); continue }
            while (at < n) {
                val size = if (first) 1 else MIN_PIECE + rng.nextInt(MAX_PIECE - MIN_PIECE + 1)
                first = false
                val end = minOf(n, at + size)
                output.write(buf, at, end - at)
                output.flush()
                budget -= end - at
                at = end
                if (budget <= 0) { if (at < n) { output.write(buf, at, n - at); output.flush() }; break }
                if (at < n) Thread.sleep(PAUSE_MIN_MS + rng.nextInt(PAUSE_SPREAD_MS).toLong())
            }
        }
    }

    /** TCP_WINDOW_CLAMP on the live socket. False where the platform refuses it. */
    private fun clamp(socket: Socket, bytes: Int): Boolean = try {
        android.os.ParcelFileDescriptor.fromSocket(socket).use { pfd ->
            android.system.Os.setsockoptInt(pfd.fileDescriptor, IPPROTO_TCP, TCP_WINDOW_CLAMP, bytes)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "window clamp $bytes: ${e.message}")
        false
    }

    private fun pump(input: InputStream, output: OutputStream, trace: Trace) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) return
                trace.down(n)
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) { }
    }

    /**
     * What crossed the relay in the first seconds, in each direction: which side goes quiet is
     * the difference between the filter dropping what we send and dropping what the server
     * sends back, and the fix for each is different. Sizes and times only, never bytes.
     */
    private class Trace(private val t0: Long) {
        private var ups = 0
        private var downs = 0
        @Volatile var upBytes = 0L
        @Volatile var downBytes = 0L
        fun up(n: Int) {
            upBytes += n
            if (++ups <= TRACE_READS) Log.i(TAG, "up   +$n at ${System.currentTimeMillis() - t0} ms")
        }
        fun down(n: Int) {
            downBytes += n
            if (++downs <= TRACE_READS) Log.i(TAG, "down +$n at ${System.currentTimeMillis() - t0} ms")
        }
        fun summary() = "up $upBytes B in $ups reads, down $downBytes B in $downs reads"
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } }; open.clear() }
    }

    companion object {
        private const val TAG = "OpenVpnRelay"
        private const val CONNECT_MS = 8_000
        private const val FAILOVER_CONNECT_MS = 4_000
        /** Less than this down means no certificate flight arrived: the session never started. */
        private const val HANDSHAKE_BYTES = 2048L
        private const val TRACE_READS = 12
        private const val IPPROTO_TCP = 6
        /** linux/tcp.h */
        private const val TCP_WINDOW_CLAMP = 10
        /** The kernel raises anything smaller to half its minimum buffer, about 1.1 KB. */
        private const val HANDSHAKE_WINDOW = 1024
        private const val OPEN_WINDOW = 16 * 1024 * 1024
        /** The certificate flight is a few KB; past this the session is in its data phase. */
        private const val UNCLAMP_AFTER_BYTES = 12 * 1024L
        private const val UNCLAMP_AFTER_MS = 20_000L
        /** The reset, the ClientHello and the key exchange all fit well inside this. */
        const val SPLIT_BYTES = 6 * 1024
        private const val MIN_PIECE = 3
        private const val MAX_PIECE = 29
        private const val PAUSE_MIN_MS = 2
        private const val PAUSE_SPREAD_MS = 6
    }
}
