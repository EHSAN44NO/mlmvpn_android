package com.mlmvpn.scanner.data

import android.util.Log
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Where to scan, learned on the spot, and a one-second test that catches the addresses the new
 * filtering lets through the handshake and then silences.
 *
 * Measured on the phone, 2026-09-28, after Iran's filtering was tightened: of 16 Cloudflare ranges
 * only 172.67.0.0/16 still carried traffic (33 of 60 addresses answered a WebSocket upgrade on
 * BPB's own path); almost everywhere else TCP was closed, or TLS completed and the request was never
 * answered. The scanner walked the ranges in a fixed order -- 2,048 addresses of 104.16.0.0/13
 * before 172.67 -- so its 50-test budget was spent on silent addresses and it found nothing.
 *
 *  - [order] samples every range with the real check first and puts the ranges that pass at the
 *    front, best first; the ones that did not pass stay at the back, since the filter moves. Nothing
 *    about 172.67 is written down: next week it may be another range, and this follows it.
 *  - [alive] is TCP, TLS with the config's own name, then the config's own first request (a
 *    WebSocket upgrade on its path, or a plain GET): any HTTP status line passes. Under a second on
 *    a good address; the scan runs it before spending a real Xray test.
 */
object ScanScout {

    private const val TAG = "ScanScout"
    private const val SAMPLE_PER_RANGE = 6
    private const val TIMEOUT_MS = 2_500

    /** What the base config asks the edge for: the name, the host header, and its first request. */
    data class Target(val sni: String, val host: String, val path: String, val ws: Boolean, val tls: Boolean)

    fun target(baseConfig: String): Target? {
        val c = VpnConfig.parseUri(baseConfig) ?: return null
        if (c.tls.lowercase() == "reality") return null
        val sni = c.sni.ifBlank { c.wsHost }.ifBlank { c.xhttpHost }.ifBlank { c.address }
        val host = c.wsHost.ifBlank { c.xhttpHost }.ifBlank { sni }
        val ws = c.network.equals("ws", true) || c.network.equals("httpupgrade", true)
        val path = (if (ws) c.wsPath else c.xhttpPath).ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        return Target(sni, host, path, ws, c.tls.equals("tls", true))
    }

    /** True when [ip]:[port] answers the config's own first request. A target it cannot judge passes. */
    fun alive(ip: String, port: Int, t: Target?): Boolean {
        if (t == null || !t.tls || t.sni.isBlank()) return true
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }), null)
            val ssl = ctx.socketFactory.createSocket(raw, t.sni, port, true) as javax.net.ssl.SSLSocket
            ssl.use { s ->
                runCatching {
                    s.sslParameters = s.sslParameters.apply {
                        serverNames = listOf(javax.net.ssl.SNIHostName(t.sni))
                        applicationProtocols = arrayOf("http/1.1")
                    }
                }
                s.startHandshake()
                val req = if (t.ws)
                    "GET ${t.path} HTTP/1.1\r\nHost: ${t.host}\r\nUser-Agent: Mozilla/5.0\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
                else "GET ${t.path} HTTP/1.1\r\nHost: ${t.host}\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"
                s.outputStream.write(req.toByteArray()); s.outputStream.flush()
                val line = s.inputStream.bufferedReader().readLine().orEmpty()
                return line.startsWith("HTTP/")
            }
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { raw.close() }
        }
    }

    /** One line per range, for the screen and the log: how many of the sample passed. */
    @Volatile var lastReport: List<Pair<String, Int>> = emptyList()
        private set

    /**
     * The addresses to scan, best first, learned on the spot:
     *  1. every IPv4 range is sampled with [alive] (TCP, TLS, the config's first request);
     *  2. when the phone has an IPv6 route, Cloudflare's v6 prefixes are sampled the same way;
     *  3. the families are then settled with the REAL test ([CfFamily.measure]): the 2026-09-28
     *     filtering let IPv4 pass every cheap check and carry no data, so a cheap pass is not proof;
     *  4. the family that carries traffic goes first (a dead family is left out entirely, so its
     *     silent addresses cannot eat the test budget), and within v4 the ranges that passed lead.
     * Falls back to the plain order when the base config cannot be judged.
     */
    suspend fun order(
        context: android.content.Context,
        scanner: CloudflareScanner,
        ranges: List<String>,
        baseConfig: String,
        port: Int = 443,
    ): List<String> = coroutineScope {
        val t = target(baseConfig)
        if (t == null || !t.tls) return@coroutineScope scanner.generateIPs(ranges)
        val gate = Semaphore(32)
        val v6Route = async(Dispatchers.IO) { CfFamily.hasIpv6Route() }
        val scouted = ranges.map { r ->
            async(Dispatchers.IO) {
                val sample = scanner.generateIPs(listOf(r)).shuffled().take(SAMPLE_PER_RANGE)
                val passed = sample.map { ip -> async { gate.withPermit { ip to alive(ip, port, t) } } }.awaitAll()
                    .filter { it.second }.map { it.first }
                Triple(r, passed, sample.size)
            }
        }.awaitAll()
        val v6Sample = if (v6Route.await()) CfFamily.sampleV6(CfFamily.V6_PREFIXES.size)
            .map { ip -> async(Dispatchers.IO) { gate.withPermit { ip to alive(ip, port, t) } } }.awaitAll() else emptyList()
        val v6Alive = v6Sample.filter { it.second }.map { it.first }
        lastReport = scouted.map { it.first to it.second.size } + ("IPv6" to v6Alive.size)
        Log.i(TAG, "scout: " + scouted.joinToString { "${it.first}=${it.second.size}/${it.third}" } + ", IPv6=${v6Alive.size}/${v6Sample.size}")

        val good = scouted.filter { it.second.isNotEmpty() }.sortedByDescending { it.second.size.toFloat() / it.third }
        val bad = scouted.filter { it.second.isEmpty() }
        val verdict = CfFamily.measure(context, scanner, baseConfig, port, good.flatMap { it.second }, v6Alive)

        withContext(Dispatchers.Default) {
            val v4First = good.flatMap { it.second } + good.flatMap { scanner.generateIPs(listOf(it.first)).shuffled() }
            val v4Rest = bad.flatMap { scanner.generateIPs(listOf(it.first)).shuffled() }
            // v6 prefixes that answered lead; the rest still get their share, since any address in
            // a live prefix reaches the edge.
            val livePrefixes = v6Alive.map { it.split(':').take(3).joinToString(":") }.distinct()
            val v6 = v6Alive + CfFamily.sampleV6(400, livePrefixes.ifEmpty { CfFamily.V6_PREFIXES })
            when {
                verdict.v4 == false && verdict.v6 == true -> v6                       // v4 carries nothing here
                verdict.v4 == true && verdict.v6 == true -> (v4First + v6 + v4Rest)
                verdict.v6 == true -> v6 + v4First + v4Rest
                verdict.v4 == true -> v4First + v4Rest + v6
                else -> v6 + v4First + v4Rest                                           // unknown: newest evidence first
            }.distinct()
        }
    }
}
