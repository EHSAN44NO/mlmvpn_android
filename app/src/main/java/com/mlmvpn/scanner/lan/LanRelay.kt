package com.mlmvpn.scanner.lan

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.utils.NetworkSettings
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// =================================================================================================
// The LAN-facing door to the tunnel, owned by the app instead of by the core.
//
// Every other device's proxy connection used to go straight to the engine's own listener, which
// meant the app could see nothing about it: not who was connected, not how many connections, not a
// byte of usage. [LanClients] tried to recover that from `/proc/net/tcp`, and on a real phone that
// file is simply not readable --
//
//     $ run-as com.mlmvpn.scanner cat /proc/net/tcp6
//     cat: /proc/net/tcp6: Permission denied
//
// -- so every read returned nothing and the whole feature reported "no devices connected" while a
// laptop was actively browsing through it. Worse than a missing number: the setup page read the
// same zero and told a correctly configured user to go back and check their settings. The comment
// that used to sit in LanClients, claiming an app still sees its own uid's sockets, was true of
// the Android 10 transition and is not true of the SELinux rule that shipped with it.
//
// There is no permission, API or file that gives an ordinary app the peers of a socket it does not
// own. So this owns the socket. It binds the port other devices are told to use, accepts their
// connections itself, and forwards each one to the engine's listener on loopback. Everything the
// screen wants then falls out of the accept loop as fact rather than inference: the peer address
// is the accepted socket's, the connection count is how many are open, and the byte counters --
// which `libv2ray` cannot break down per client at all, because its stats API is outbound-only --
// are just what the two pump loops copied.
//
// It is deliberately a byte pump and nothing else. It does not parse SOCKS or HTTP, so the
// engine's own authentication, UDP associate and protocol handling are untouched and keep working
// exactly as they did; a password set on the inbound is still checked by the inbound.
//
// The engine's own listener is left bound where it was. This is additive: it does not move the
// data plane, it puts a counted door in front of it, and a laptop somebody configured by hand
// against the old port keeps working (uncounted) instead of breaking on upgrade.
// =================================================================================================

object LanRelay {

    private const val TAG = "LanRelay"

    /**
     * Offset from the app's Local Port to this listener.
     *
     * +4 because +1 (the Psiphon-over-WARP chain), +2 (Psiphon's HTTP proxy) and +3
     * ([LanSetupServer]) are taken, and +10000 is the status probe. [com.mlmvpn.scanner.utils
     * .LocalPort.validate] keeps the whole `port .. port + 4` band clear of the delay tester's
     * range, so this is free at every value the setting will accept.
     */
    const val PORT_OFFSET = 4

    /** 16 KB per direction. Big enough that a video stream is not syscall-bound, small enough
     *  that a hundred idle connections cost a few megabytes rather than a hundred. */
    private const val BUFFER = 16 * 1024

    /**
     * Ceiling on simultaneous relayed connections.
     *
     * A browser opens six per host and a phone sharing to two laptops sits comfortably inside
     * this. The cap exists so that a client stuck in a reconnect loop cannot spawn threads until
     * the app is killed -- refusing the 257th connection is recoverable, dying is not.
     */
    private const val MAX_CONNECTIONS = 256

    /** How long a device stays on the list after its last connection closed. */
    private const val PEER_TTL_MS = 10 * 60 * 1000L

    /**
     * How long a half-closed connection may sit before both sockets are closed outright.
     *
     * Generous, because this is the only thing standing between a legitimately slow response and
     * a truncated download: 90 seconds is far longer than any server takes to finish sending
     * after its client stopped talking, and far shorter than "until the app is killed".
     */
    private const val HALF_CLOSE_GRACE_MS = 90_000L

    /**
     * Consecutive polls that must agree before the relay is torn down.
     *
     * [sync] runs off a two second poll of facts that flicker: a VPN service reconnecting reads
     * as "no tunnel" for one tick, and an interface walk can miss an address once. Acting on the
     * first false reading dropped every LAN client's live connection over a blip that had already
     * healed by the next poll -- observed as an unexplained rebind mid-download.
     */
    private const val STOP_AFTER_TICKS = 3

    private val running = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var listenPort: Int = 0

    @Volatile
    private var targetPort: Int = 0

    /** Whether the current upstream answers HTTP as well as SOCKS5. See [frontEnd]. */
    @Volatile
    private var upstreamSpeaksHttp: Boolean = true

    private val live = AtomicInteger(0)

    // Threads, not coroutines: every one of these blocks on a socket read for its whole life,
    // which is the one shape a dispatcher cannot help with. Cached so an idle share holds none.
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "lan-relay").apply {
            isDaemon = true
            // A relay thread must not be able to end the app. Every call site below already
            // catches, and this is the net under them: the default handler for a thread with no
            // handler of its own is the process-killing one, so a single missed catch on a
            // socket that closed at the wrong moment is a crash on somebody else's browsing.
            setUncaughtExceptionHandler { thread, error ->
                Log.w(TAG, "swallowed on ${thread.name}: $error")
            }
        }
    }

    private class Stat {
        val open = AtomicInteger(0)
        val total = AtomicLong(0)
        val bytes = AtomicLong(0)

        @Volatile
        var lastSeen: Long = System.currentTimeMillis()
    }

    private val peers = ConcurrentHashMap<String, Stat>()

    /** One thread, and only ever for the half-close reaper above. */
    private val reaperPool = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lan-relay-reaper").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, error -> Log.w(TAG, "reaper: $error") }
        }
    }

    /** Consecutive [sync] calls that said sharing is not wanted. See [STOP_AFTER_TICKS]. */
    private var unwantedTicks = 0

    /** The port other devices should be given, or null when the relay is not bound. */
    fun boundPort(): Int? = listenPort.takeIf { it > 0 && running.get() }

    val isRunning: Boolean get() = running.get()

    /**
     * Bring the relay up or down to match what sharing currently needs, and report the port.
     *
     * Bound on the CALLING thread rather than inside the accept thread, so the value it returns
     * is already true when the caller builds the status it is about to draw. Getting that
     * backwards would advertise the engine's port for one poll and the relay's for the next,
     * which is a QR code and a copy button changing under the user's thumb.
     */
    fun sync(context: Context, status: LanStatus): Int? {
        // Any engine with a listener, which since [frontEnd] exists means any engine at all
        // except the ones that publish nothing. A SOCKS-only upstream is fine now: the relay
        // answers HTTP on its behalf rather than forwarding an HTTP request to something that
        // would not understand it.
        val wanted = status.lanEnabled &&
            status.address != null &&
            status.upstreamPort > 0 &&
            (status.engine == EngineShare.FULL || status.engine == EngineShare.NO_AUTH)
        val port = status.socksPort + PORT_OFFSET
        if (!wanted) {
            if (running.get() && ++unwantedTicks >= STOP_AFTER_TICKS) stop()
            // The port is still reported while the grace runs: the relay really is up, and
            // saying otherwise would swap the address under the user mid-blip.
            return boundPort()
        }
        unwantedTicks = 0
        val unchanged = running.get() &&
            listenPort == port &&
            targetPort == status.upstreamPort &&
            upstreamSpeaksHttp == status.upstreamSpeaksHttp
        if (unchanged) return listenPort
        if (running.get()) stop()
        return start(context.applicationContext, port, status)
    }

    private fun start(context: Context, port: Int, status: LanStatus): Int? {
        val socket = try {
            ServerSocket(port, 64, InetAddress.getByName("0.0.0.0"))
        } catch (e: Exception) {
            Log.w(TAG, "could not bind $port: ${e.message}")
            return null
        }
        server = socket
        listenPort = port
        targetPort = status.upstreamPort
        upstreamSpeaksHttp = status.upstreamSpeaksHttp
        running.set(true)
        Log.i(
            TAG,
            "relay listening on 0.0.0.0:$port -> 127.0.0.1:${status.upstreamPort}" +
                if (status.upstreamSpeaksHttp) " (mixed)" else " (socks5, HTTP bridged here)"
        )
        val app = context.applicationContext
        // The accept loop captures its own socket. The fields above are for readers; this thread
        // never consults them, so a later stop()/start() pair cannot make it serve for a socket
        // it does not own -- the bug pattern that made the setup server's rebind unreliable.
        Thread({ acceptLoop(app, socket) }, "lan-relay-accept").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, error ->
                Log.w(TAG, "accept loop died: $error")
                if (server === socket) stop()
            }
        }.start()
        return port
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        listenPort = 0
        targetPort = 0
        unwantedTicks = 0
        // Neither `live` nor the per-peer open counts are reset here. Closing the listener does
        // not close the connections it already accepted -- those keep running and each still
        // owes exactly one [finish], so zeroing the counters underneath them would make the
        // screen show no devices while devices were plainly downloading, and then drive the
        // counts negative as each one ended.
        //
        // Peers are not cleared either: "which devices used this share" outlives one rebind of
        // the listener, and wiping the list on every address change is what made the screen
        // forget a laptop that was still connected.
    }

    private fun acceptLoop(context: Context, socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get() && !socket.isClosed) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            val address = client.inetAddress?.hostAddress.orEmpty()
            when {
                live.get() >= MAX_CONNECTIONS -> {
                    Log.w(TAG, "at $MAX_CONNECTIONS connections, refusing $address")
                    runCatching { client.close() }
                }
                // Read per connection rather than cached: blocking a device has to take effect
                // on the next connection it opens, not on the next time something happens to
                // reload a snapshot.
                address.isNotEmpty() && address in NetworkSettings.lanBlocked(context) -> {
                    runCatching { client.close() }
                }
                else -> {
                    live.incrementAndGet()
                    pool.execute { handle(client, address) }
                }
            }
        }
        runCatching { socket.close() }
    }

    private fun handle(client: Socket, address: String) {
        val stat = peers.getOrPut(address) { Stat() }
        stat.open.incrementAndGet()
        stat.total.incrementAndGet()
        stat.lastSeen = System.currentTimeMillis()

        val upstream = try {
            client.tcpNoDelay = true
            // Loopback, so the connect is either immediate or the engine is not listening; a
            // long timeout here would only make a dead engine look like a slow network. The
            // address is spelled out rather than taken from getLoopbackAddress(), which resolves
            // to ::1 on a v6-preferring device.
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort), 4_000)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "upstream 127.0.0.1:$targetPort refused for $address: ${t.message}")
            runCatching { client.close() }
            finish(stat)
            return
        }

        // The client's stream is taken once and reused, because the HTTP front-end below has to
        // read the request head off it before pumping starts and must not lose whatever it read
        // ahead of. A pushback of one byte is all the peek needs.
        val fromClient = java.io.PushbackInputStream(client.getInputStream(), 1)
        if (!upstreamSpeaksHttp && !frontEnd(fromClient, client.getOutputStream(), upstream)) {
            runCatching { client.close() }
            runCatching { upstream.close() }
            finish(stat)
            return
        }

        // The two directions are peers, and the LAST one to finish closes both sockets. The
        // first version closed on the first end instead, which truncated a response still on its
        // way -- and, worse, made "Socket closed" the ordinary outcome of the other pump.
        val remaining = AtomicInteger(2)

        // Armed when the first direction ends, cancelled if the second ends on its own. Without
        // it a peer that half-closes and then says nothing leaves a socket in FIN_WAIT2 and two
        // threads blocked on it for as long as the app lives -- a measured 12 of 18 sockets on
        // the relay port were sitting exactly like that. A close is what unblocks a thread
        // already inside read(), which is why this closes rather than setting a read timeout.
        var reaper: java.util.concurrent.ScheduledFuture<*>? = null

        fun leg(from: Socket, to: Socket, input: java.io.InputStream = from.getInputStream()) {
            try {
                pump(input, to.getOutputStream(), stat)
                // Half-close, so the far side sees a clean end of stream and can finish its own
                // direction rather than being cut off mid-response.
                runCatching { to.shutdownOutput() }
            } catch (t: Throwable) {
                // The ordinary end of a relayed connection arrives here: a reset from a client
                // that navigated away, a closed pipe from the core. Nothing is recoverable and
                // nothing is worth more than a debug line -- but it MUST be caught. An exception
                // escaping a Runnable handed to an executor reaches that thread's uncaught
                // handler, and on Android that ends the process: the crash the first build of
                // this shipped with, on the very first connection, which is also why no data ever
                // moved.
                Log.d(TAG, "leg for $address ended: ${t.message}")
            } finally {
                when (remaining.decrementAndGet()) {
                    1 -> reaper = reaperPool.schedule(
                        {
                            runCatching { client.close() }
                            runCatching { upstream.close() }
                        },
                        HALF_CLOSE_GRACE_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                    0 -> {
                        reaper?.cancel(false)
                        runCatching { client.close() }
                        runCatching { upstream.close() }
                        finish(stat)
                    }
                }
            }
        }

        // One direction on a pooled thread, the other on this one: two tasks per connection
        // instead of three, and this thread is already here.
        pool.execute { leg(upstream, client) }
        leg(client, upstream, fromClient)
    }

    private fun finish(stat: Stat) {
        stat.open.decrementAndGet()
        stat.lastSeen = System.currentTimeMillis()
        live.decrementAndGet()
    }

    // ---------------------------------------------------------------------------------------
    // Speaking HTTP to a SOCKS-only engine
    //
    // The Xray family publishes a `mixed` inbound, so one port answers both protocols and this
    // whole section is skipped. Nothing else does: the Rust core's `socks::serve`, Psiphon's
    // SOCKS listener and Tor's front-end are SOCKS5 and only SOCKS5.
    //
    // That matters because of what the other device can actually be configured with. A PAC file
    // is the only setting Windows, macOS, iOS and Android all accept as a single field, and the
    // entry every one of them honours is `PROXY host:port` -- an HTTP proxy. Android in
    // particular has no usable SOCKS support in its Wi-Fi settings at all. So a SOCKS-only
    // engine was unshareable in practice even once it had a listener.
    //
    // Rather than ask the user to pick a protocol their device may not have, the relay answers
    // HTTP itself and translates. It reads the first byte: 0x04/0x05 is a SOCKS client and is
    // passed through untouched, anything else is an HTTP request, which is turned into a SOCKS5
    // CONNECT and then pumped like any other connection.
    // ---------------------------------------------------------------------------------------

    /** Longest request head accepted before the client is treated as hostile rather than slow. */
    private const val MAX_HEAD_BYTES = 16 * 1024

    /**
     * Take whatever the client opened with and leave [upstream] connected to its destination.
     *
     * Returns false when nothing can be done with the request, having already told the client
     * why -- a proxy that closes silently is indistinguishable from a network fault.
     */
    private fun frontEnd(
        input: java.io.PushbackInputStream,
        output: OutputStream,
        upstream: Socket,
    ): Boolean = try {
        val first = input.read()
        when {
            first < 0 -> false
            // SOCKS4 and SOCKS5 both start with their version number, and neither value is a
            // legal first character of an HTTP method, so one byte separates them for certain.
            first == 0x04 || first == 0x05 -> {
                input.unread(first)
                true
            }
            else -> {
                input.unread(first)
                httpFrontEnd(input, output, upstream)
            }
        }
    } catch (t: Throwable) {
        Log.d(TAG, "front-end failed: ${t.message}")
        false
    }

    private fun httpFrontEnd(
        input: InputStream,
        output: OutputStream,
        upstream: Socket,
    ): Boolean {
        val head = readHead(input) ?: return refuse(output, 400, "Bad Request")
        val lines = head.split("\r\n")
        val request = lines.firstOrNull()?.split(' ') ?: return refuse(output, 400, "Bad Request")
        if (request.size < 3) return refuse(output, 400, "Bad Request")
        val method = request[0]
        val target = request[1]
        val version = request[2]

        if (method.equals("CONNECT", ignoreCase = true)) {
            val host = target.substringBeforeLast(':', "")
            val port = target.substringAfterLast(':', "").toIntOrNull()
            if (host.isEmpty() || port == null) return refuse(output, 400, "Bad Request")
            if (!socks5Connect(upstream, host, port)) return refuse(output, 502, "Bad Gateway")
            // The client sends nothing until it sees this, so it goes out before the pumps start.
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            return true
        }

        // Absolute-form: `GET http://host[:port]/path HTTP/1.1`. Only http:// arrives this way --
        // https:// is always a CONNECT -- so the default port is 80.
        if (!target.startsWith("http://", ignoreCase = true)) {
            return refuse(output, 400, "Bad Request")
        }
        val authority = target.removePrefix("http://").removePrefix("HTTP://").substringBefore('/')
        val path = target.removePrefix("http://").removePrefix("HTTP://")
            .substringAfter('/', "").let { "/$it" }
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "80").toIntOrNull() ?: 80
        if (host.isEmpty()) return refuse(output, 400, "Bad Request")
        if (!socks5Connect(upstream, host, port)) return refuse(output, 502, "Bad Gateway")

        // Rewritten to origin form, which is what an ORIGIN server expects: the absolute URI is
        // a proxy convention and this connection is no longer proxied once SOCKS has dialled.
        // The two hop-by-hop proxy headers are dropped for the same reason.
        val rebuilt = buildString {
            append("$method $path $version\r\n")
            lines.drop(1).forEach { header ->
                if (header.isBlank()) return@forEach
                val name = header.substringBefore(':').trim()
                if (name.equals("Proxy-Connection", ignoreCase = true)) return@forEach
                if (name.equals("Proxy-Authorization", ignoreCase = true)) return@forEach
                append(header).append("\r\n")
            }
            append("\r\n")
        }
        upstream.getOutputStream().write(rebuilt.toByteArray())
        upstream.getOutputStream().flush()
        return true
    }

    /** The request head up to the blank line, or null if it never arrives within the cap. */
    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        var run = 0
        while (buffer.length < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.append(b.toChar())
            // Counting the CRLFCRLF by hand, one byte at a time, so that not a single byte past
            // the head is consumed. Anything read ahead of here would be body the pump never
            // sees, and a POST would silently lose its first buffer.
            run = when {
                b == '\r'.code && (run == 0 || run == 2) -> run + 1
                b == '\n'.code && (run == 1 || run == 3) -> run + 1
                else -> 0
            }
            if (run == 4) return buffer.toString().removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun refuse(output: OutputStream, code: Int, reason: String): Boolean {
        runCatching {
            output.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
        return false
    }

    /**
     * A SOCKS5 CONNECT on [upstream], with the hostname left for the far end to resolve.
     *
     * Domain-form (`atyp` 3) rather than resolving here on purpose: resolving locally would send
     * the query out over the phone's own link, outside the tunnel, which is a DNS leak of exactly
     * what the other device is browsing.
     */
    private fun socks5Connect(upstream: Socket, host: String, port: Int): Boolean = runCatching {
        val out = upstream.getOutputStream()
        val input = upstream.getInputStream()

        // Greeting: version 5, one method, "no authentication".
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greeting = ByteArray(2)
        if (!readFully(input, greeting)) return false
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) return false

        val name = host.toByteArray(Charsets.US_ASCII)
        if (name.size > 255) return false
        val request = ByteArray(7 + name.size)
        request[0] = 0x05            // version
        request[1] = 0x01            // CONNECT
        request[2] = 0x00            // reserved
        request[3] = 0x03            // address type: domain name
        request[4] = name.size.toByte()
        System.arraycopy(name, 0, request, 5, name.size)
        request[5 + name.size] = (port shr 8 and 0xFF).toByte()
        request[6 + name.size] = (port and 0xFF).toByte()
        out.write(request)
        out.flush()

        val reply = ByteArray(4)
        if (!readFully(input, reply)) return false
        if (reply[0] != 0x05.toByte() || reply[1] != 0x00.toByte()) return false
        // The bound address has to be consumed even though nothing uses it, or its bytes would
        // be delivered to the client as the first bytes of the response.
        val addressLength = when (reply[3].toInt() and 0xFF) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> input.read().also { if (it < 0) return false }
            else -> return false
        }
        readFully(input, ByteArray(addressLength + 2))
    }.getOrElse {
        Log.d(TAG, "socks5 connect to $host:$port failed: ${it.message}")
        false
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun pump(input: InputStream, output: OutputStream, stat: Stat) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
            stat.bytes.addAndGet(read.toLong())
            stat.lastSeen = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------------------------------
    // What the screen reads
    // ---------------------------------------------------------------------------------------

    /** Every device that has used the share, most active first. */
    fun peers(): List<LanClient> {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        peers.entries.removeAll { it.value.open.get() == 0 && it.value.lastSeen < cutoff }
        return peers.map { (address, stat) ->
            LanClient(
                address = address,
                connections = stat.open.get(),
                openedSetupPage = false,
                bytes = stat.bytes.get(),
                lastSeen = stat.lastSeen,
            )
        }.sortedWith(compareByDescending<LanClient> { it.connections }.thenByDescending { it.lastSeen })
    }

    /** Open connections from one device, for the setup page's own live status line. */
    fun connectionsFrom(address: String): Int = peers[address]?.open?.get() ?: 0

    /**
     * True when this device has ever pushed traffic through the relay.
     *
     * The setup page's question is "did my settings take", not "am I downloading right now", and
     * those are different questions: a browser sitting on the instructions page has no proxy
     * connection open at that instant and never will until the user navigates somewhere. Asking
     * only about open connections is what made a working setup report itself as broken.
     */
    fun everConnected(address: String): Boolean = (peers[address]?.total?.get() ?: 0L) > 0L

    /** Forget everything, for the stop button. */
    fun reset() {
        peers.clear()
    }
}
