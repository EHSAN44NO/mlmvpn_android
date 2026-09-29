package com.mlmvpn.scanner.engines.mae.probe

import android.content.Context
import android.net.Network
import com.mlmvpn.scanner.engines.game.DnsRaceTester
import com.mlmvpn.scanner.engines.game.booster.session.GameNetwork
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ProbeSpec
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real probes. Direct probes go out on the underlying (non-VPN) network; everything else goes
 * through the probe core's SOCKS inbound for that route, i.e. through the exact outbound the
 * live config will use. This app is excluded from its own VPN, so its sockets never loop back
 * into the tunnel.
 *
 * Every request is a tiny, fixed, anonymous GET: no cookies, no accounts, at most [MAX_READ]
 * bytes read, nothing kept but which steps passed.
 */
class NetProber(private val context: Context) : Prober {

    private val network: Network? get() = GameNetwork.pick(context)?.network

    override suspend fun observe(route: ProbeRoute, spec: ProbeSpec): Observation = withContext(Dispatchers.IO) {
        val uri = URI(spec.url)
        val host = uri.host ?: return@withContext Observation(route.routeId, route.kind == RouteKind.FOREIGN, route.kind == RouteKind.DIRECT)
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val foreign = route.kind == RouteKind.FOREIGN
        val direct = route.kind == RouteKind.DIRECT

        var dns: DnsEvidence? = null
        val raw: Socket
        val t0 = System.nanoTime()
        if (route.socksPort == null) {
            val v6 = route.family == FamilyPolicy.V6_ONLY
            val v4 = route.family == FamilyPolicy.V4_ONLY
            val system = runCatching { (network?.getAllByName(host) ?: java.net.InetAddress.getAllByName(host)).toList() }
                .getOrDefault(emptyList())
                .filter { a -> when { v4 -> a is java.net.Inet4Address; v6 -> a is java.net.Inet6Address; else -> true } }
                .map { it.hostAddress.orEmpty().substringBefore('%') }
            val doh = when {
                v6 -> dohResolve(host, 28)
                v4 -> dohResolve(host, 1)
                else -> dohResolve(host, 1).ifEmpty { runCatching { DnsRaceTester.resolveViaDohByIp(host) }.getOrDefault(emptyList()) }
            }
            dns = DnsEvidence(system, doh)
            val ip = doh.firstOrNull() ?: system.firstOrNull { !DnsEvidence.isBogus(it) }
                ?: return@withContext Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.ERROR)
            raw = network?.socketFactory?.createSocket() ?: Socket()
            try {
                raw.connect(InetSocketAddress(ip, 443), CONNECT_MS)
            } catch (e: SocketTimeoutException) {
                raw.close(); return@withContext Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.TIMEOUT)
            } catch (e: ConnectException) {
                raw.close(); return@withContext Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.RESET)
            } catch (e: Exception) {
                raw.close(); return@withContext Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.ERROR)
            }
        } else {
            raw = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", route.socksPort)))
            try {
                raw.connect(InetSocketAddress.createUnresolved(host, 443), CONNECT_MS * 2)
            } catch (e: Exception) {
                raw.close(); return@withContext Observation(route.routeId, foreign, direct, tcp = Step.ERROR)
            }
        }
        val (tls, http) = tlsGet(raw, host, path, spec)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.OK, tls = tls, http = http,
            rttMs = if (http != null) ms else null)
    }

    override suspend fun rtt(route: ProbeRoute, spec: ProbeSpec, samples: Int): Long? {
        val times = (0 until samples).mapNotNull { observe(route, spec).takeIf { it.answered }?.rttMs }
        return times.sorted().getOrNull(times.size / 2)
    }

    override suspend fun throughput(route: ProbeRoute, bytes: Int): Double? = withContext(Dispatchers.IO) {
        // Public test files over HTTPS with a Range header, so exactly [bytes] come back. More
        // than one, because none reaches every path: measured from MCI, CacheFly answered IPv4
        // only, OVH IPv6 only, and CacheFly refused the Worker's exit. None is behind Cloudflare,
        // so a Worker route can reach them. The first that returns real data is the sample.
        for ((host, path) in TPUT_TARGETS) {
            val bps = runCatching { sample(route, host, path, bytes) }.getOrNull()
            if (bps != null) return@withContext bps
        }
        null
    }

    private fun sample(route: ProbeRoute, host: String, path: String, bytes: Int): Double? {
        val raw = open(route, host, 443)
        raw.soTimeout = 8_000
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        ssl.use { s ->
            s.startHandshake()
            s.outputStream.write(("GET $path HTTP/1.1\r\nHost: $host\r\nRange: bytes=0-${bytes - 1}\r\n" +
                "User-Agent: $UA\r\nAccept: */*\r\nConnection: close\r\n\r\n").toByteArray())
            val t0 = System.nanoTime()
            val (got, lastByteAt) = drain(s.inputStream, bytes + 4096)
            // Up to the last byte: a trailing stall is not part of the route's speed.
            val secs = (lastByteAt - t0) / 1e9
            runCatching { android.util.Log.i("MAE", "throughput ${route.routeId}:${route.family} $host $got B in ${"%.1f".format(java.util.Locale.US, secs)} s") }
            return if (got < bytes / 4 || secs <= 0) null else got / secs
        }
    }

    /**
     * The site's public page through [route], up to [max] bytes, following up to two redirects
     * (a home page often answers with a redirect to `www.` or a language path). Null on failure.
     * Only for finding which other domains the page loads from -- see RelatedHosts.
     */
    suspend fun pageText(route: ProbeRoute, url: String, max: Int = 512 * 1024): String? = withContext(Dispatchers.IO) {
        var target = url
        repeat(3) {
            val uri = URI(target)
            val host = uri.host ?: return@withContext null
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val text = runCatching {
                val raw = open(route, host, 443)
                raw.soTimeout = READ_MS * 2
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
                ssl.use { s ->
                    s.startHandshake()
                    s.outputStream.write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $UA\r\n" +
                        "Accept: text/html,*/*\r\nAccept-Language: en\r\nConnection: close\r\n\r\n").toByteArray())
                    readUpTo(s.inputStream, max)
                }
            }.getOrNull() ?: return@withContext null
            val status = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext text
            val location = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)
            if (status !in 300..399 || location == null) return@withContext text
            target = if (location.startsWith("http")) location else "https://$host${if (location.startsWith("/")) "" else "/"}$location"
        }
        null
    }

    override suspend fun egress(route: ProbeRoute): EgressEcho? = withContext(Dispatchers.IO) {
        // The address comes from the route itself (plain TCP, so a Worker `connect()` path is
        // exercised exactly as traffic uses it); the country from two unrelated services.
        // ipify sits behind Cloudflare, which a Worker's connect() cannot reach (the US exit got no
        // echo at all): two echoes on other networks follow it.
        val ip = ECHOES.firstNotNullOfOrNull { (host, path) ->
            plainGet(route, host, path)?.trim()?.takeIf { it.length in 3..45 && it.all { c -> c.isLetterOrDigit() || c == '.' || c == ':' } }
        } ?: return@withContext null
        // The lookups are about [ip], not about the route, so they go wherever works: through the
        // route first, then directly (ip-api.com has no IPv6 address, so a forced-IPv6 exit
        // cannot reach it -- measured on the phone, 2026-09-29).
        val plain = ProbeRoute(route.routeId, RouteKind.DIRECT)
        fun lookup(host: String, path: String) = (plainGet(route, host, path) ?: plainGet(plain, host, path))?.trim()?.take(2)?.uppercase()
        val a = lookup("ip-api.com", "/line/$ip?fields=countryCode")
        val b = lookup("ipinfo.io", "/$ip/country")
        val country = when {
            a != null && b != null && a == b -> a
            a != null && b == null -> a
            b != null && a == null -> b
            else -> null // disagree: no claim
        }
        EgressEcho(ip, ip.contains(':'), country?.takeIf { it.length == 2 && it.all(Char::isLetter) })
    }

    /**
     * DoH by IP literal on the underlying network (no name to filter), A=1 or AAAA=28. Tries the
     * resolvers in order; the first that answers wins. Measured on MCI: only `8.8.8.8` by IP was
     * reachable, every DoH reached by name was filtered.
     */
    fun dohResolve(host: String, type: Int): List<String> {
        for (base in DOH_JSON) {
            val ips = runCatching {
                val url = java.net.URL("$base?name=$host&type=$type")
                val c = (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000
                c.setRequestProperty("accept", "application/dns-json")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val ans = org.json.JSONObject(body).optJSONArray("Answer") ?: return@runCatching emptyList<String>()
                (0 until ans.length()).map { ans.getJSONObject(it) }.filter { it.optInt("type") == type }.map { it.getString("data") }
            }.getOrNull()
            if (ips != null) return ips
        }
        return emptyList()
    }

    /** True when an RFC 8484 DoH endpoint answers through [route] (null socks = direct). */
    fun dohWorks(url: String, route: ProbeRoute): Boolean = runCatching {
        val q = DnsRaceTester.buildDnsQuery("example.com")
        val b64 = android.util.Base64.encodeToString(q, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        val u = java.net.URL("$url?dns=$b64")
        val c = (if (route.socksPort != null) u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", route.socksPort)))
            else (network?.openConnection(u) ?: u.openConnection())) as HttpsURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        c.setRequestProperty("accept", "application/dns-message")
        c.responseCode == 200 && c.inputStream.use { it.readBytes().size } > 12
    }.getOrDefault(false)

    private fun open(route: ProbeRoute, host: String, port: Int): Socket =
        if (route.socksPort == null) {
            (network?.socketFactory?.createSocket() ?: Socket()).also { it.connect(InetSocketAddress(host, port), CONNECT_MS) }
        } else {
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", route.socksPort)))
                .also { it.connect(InetSocketAddress.createUnresolved(host, port), CONNECT_MS * 2) }
        }

    private fun plainGet(route: ProbeRoute, host: String, path: String): String? = runCatching {
        open(route, host, 80).use { s ->
            s.soTimeout = READ_MS
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: curl/8\r\nConnection: close\r\n\r\n".toByteArray())
            val text = readUpTo(s.getInputStream(), 2048)
            if (!text.startsWith("HTTP/1.1 200") && !text.startsWith("HTTP/1.0 200")) null
            else text.substringAfter("\r\n\r\n")
        }
    }.getOrNull()

    private fun tlsGet(raw: Socket, host: String, path: String, spec: ProbeSpec): Pair<Step, HttpEvidence?> {
        val ssl: SSLSocket = try {
            raw.soTimeout = TLS_MS
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        } catch (e: Exception) {
            raw.close(); return Step.ERROR to null
        }
        try {
            ssl.startHandshake()
        } catch (e: SocketTimeoutException) {
            ssl.close(); return Step.TIMEOUT to null
        } catch (e: SSLException) {
            ssl.close()
            // An untrusted chain for a public name is somebody else's certificate.
            val mismatch = e.javaClass.simpleName.contains("Handshake") && (e.message ?: "").contains("certif", true)
            return (if (mismatch) Step.CERT_MISMATCH else Step.RESET) to null
        } catch (e: Exception) {
            ssl.close(); return Step.RESET to null
        }
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            ssl.close(); return Step.CERT_MISMATCH to null
        }
        return try {
            ssl.soTimeout = READ_MS
            ssl.outputStream.write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $UA\r\n" +
                "Accept: text/html,application/json,*/*\r\nAccept-Language: en\r\nConnection: close\r\n\r\n").toByteArray())
            val text = readUpTo(ssl.inputStream, if (spec.readBytes > 0) spec.readBytes else MAX_READ)
            val status = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: return Step.OK to null
            val lower = text.lowercase()
            Step.OK to HttpEvidence(
                status = status,
                geoSignature = spec.geoSignatures.any { lower.contains(it) },
                okSignature = spec.okSignatures.any { lower.contains(it) },
            )
        } catch (e: Exception) {
            Step.OK to null
        } finally {
            runCatching { ssl.close() }
        }
    }

    private fun readUpTo(input: InputStream, max: Int): String {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = try { input.read(buf, n, max - n) } catch (e: SocketTimeoutException) { -1 }
            if (r <= 0) break
            n += r
        }
        return String(buf, 0, n, Charsets.ISO_8859_1)
    }

    /** Bytes read until [max], EOF or a stall; a stall ends the sample instead of voiding it. */
    private fun drain(input: InputStream, max: Int): Pair<Int, Long> {
        val buf = ByteArray(16 * 1024)
        var n = 0
        var last = System.nanoTime()
        while (n < max) {
            val r = try { input.read(buf) } catch (e: SocketTimeoutException) { -1 }
            if (r <= 0) break
            n += r
            last = System.nanoTime()
        }
        return n to last
    }

    companion object {
        const val CONNECT_MS = 2500
        const val TLS_MS = 3000
        const val READ_MS = 4000
        const val MAX_READ = 4096
        val ECHOES = listOf("api64.ipify.org" to "/", "checkip.amazonaws.com" to "/", "ifconfig.me" to "/ip")
        val TPUT_TARGETS = listOf("cachefly.cachefly.net" to "/10mb.test", "proof.ovh.net" to "/files/10Mb.dat")
        val DOH_JSON =listOf("https://8.8.8.8/resolve", "https://8.8.4.4/resolve", "https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
    }
}
