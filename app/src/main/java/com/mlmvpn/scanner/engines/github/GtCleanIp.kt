package com.mlmvpn.scanner.engines.github

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.data.GroupManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which Cloudflare addresses carry THIS line to the user's Worker — the Android side of
 * github-tunnel/gt-cleanip.js. Nothing is assumed: every candidate is tried through the real
 * outbound, and the winners are remembered per network (a café's clean addresses are not home's).
 *
 * Candidates, best evidence first: this network's winners from last time, the addresses the app's
 * own scanner found on this line (the unified archive), then a sample of Cloudflare's ranges.
 */
object GtCleanIp {
    private val CF_RANGES = listOf("104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "162.158.0.0/15", "188.114.96.0/20", "141.101.64.0/18")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val portCounter = AtomicInteger(0)

    fun isIpv4(s: String) = IPV4.matches(s) && s.split('.').all { (it.toIntOrNull() ?: 999) <= 255 }

    /** Which network this is: Wi-Fi or cellular, and the phone's own address on it. */
    fun networkKey(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val kind = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val link = net?.let { cm.getLinkProperties(it) }
        val gw = link?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress.orEmpty()
        "$kind|$gw"
    } catch (_: Exception) { "unknown" }

    private fun sample(n: Int): List<String> {
        val rnd = java.security.SecureRandom()
        val out = mutableListOf<String>()
        repeat(n * 3) {
            if (out.size >= n) return@repeat
            val (base, bits) = CF_RANGES[rnd.nextInt(CF_RANGES.size)].split('/')
            val b = base.split('.').map { it.toLong() }
            val start = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
            val size = 1L shl (32 - bits.toInt())
            val ip = start + 1 + (rnd.nextLong().let { if (it < 0) -it else it } % (size - 2))
            val s = "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"
            if (s !in out) out.add(s)
        }
        return out
    }

    fun candidates(context: Context, store: GtStore, network: String, limit: Int = 12): List<String> {
        val list = LinkedHashSet<String>()
        store.cleanIps(network).filter(::isIpv4).forEach { list.add(it) }
        try {
            val gm = GroupManager(context)
            gm.loadIpArchives()
            // The flow's snapshot, not the list behind it: that list is guarded by the manager's
            // own lock, and a scan can be writing to it right now.
            gm.ipArchivesFlow.value.flatMap { it.ips }.map { it.substringBefore(':').trim() }.filter(::isIpv4)
                .takeLast(limit).reversed().forEach { list.add(it) }
        } catch (_: Exception) {}
        sample(limit).forEach { list.add(it) }
        return list.take(limit + store.cleanIps(network).size)
    }

    /** One outbound, measured the way the app measures every config: a real request through it. */
    private fun probeConfig(outbound: JSONObject): String {
        val port = 34500 + (portCounter.getAndIncrement() % 400)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "socks").put("listen", "127.0.0.1")
                .put("port", port).put("protocol", "socks").put("settings", JSONObject().put("udp", false))))
            .put("outbounds", JSONArray().put(outbound.put("tag", "proxy")))
            .toString()
    }

    data class Result(val ip: String, val delayMs: Long)

    /**
     * Try every address through the real outbound, several at a time. Two shots each, the better
     * kept: the first pays for the TLS handshake, the Worker hop and the tunnel; the second shows
     * whether that was a one-off. Working ones fastest first.
     */
    suspend fun measure(context: Context, ips: List<String>, make: (String) -> JSONObject, timeoutMs: Long = 10_000): List<Result> {
        CombineEngine.ensureCoreEnv(context)
        val gate = Semaphore(6)
        return coroutineScope {
            ips.map { ip ->
                async {
                    gate.withPermit {
                        val json = probeConfig(make(ip))
                        val a = CombineEngine.measureDelay(json, GtXray.PROBE_URL, timeoutMs)
                        if (a <= 0) return@withPermit null
                        val b = CombineEngine.measureDelay(json, GtXray.PROBE_URL, timeoutMs)
                        Result(ip, if (b > 0) minOf(a, b) else a)
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.delayMs }
        }
    }

    /**
     * The addresses to use now (up to `want`), remembered for this network; empty when nothing got
     * through. `onLog` gets how many are being tried.
     */
    suspend fun pick(context: Context, store: GtStore, make: (String) -> JSONObject, want: Int = 2, onLog: (Int) -> Unit = {}): List<Result> {
        val network = networkKey(context)
        val list = candidates(context, store, network)
        onLog(list.size)
        val results = measure(context, list, make)
        if (results.isNotEmpty()) store.rememberCleanIps(network, results.map { it.ip })
        return results.take(maxOf(want, 1))
    }
}
