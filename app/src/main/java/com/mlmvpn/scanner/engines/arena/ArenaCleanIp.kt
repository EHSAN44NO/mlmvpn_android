package com.mlmvpn.scanner.engines.arena

import android.content.Context
import com.mlmvpn.scanner.data.CombineEngine
import com.mlmvpn.scanner.data.GroupManager
import com.mlmvpn.scanner.quick.QuickScanner
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * «آی‌پی تمیز یکسان»: one clean IP, found as a fixed step of every race and put on every
 * competitor's config, so the race compares panels rather than the addresses each panel happens to
 * hand out. The user's rule (2026-09-28): always, for every operator -- fairer that way -- and the
 * IP itself must be both fast and stable.
 *
 * Chosen on the spot, on this network, by [choose].
 */
object ArenaCleanIp {

    private const val PREFS = "arena_prefs"
    private const val ARCHIVE_POOL = 30
    private const val SAMPLE_POOL = 30
    private const val TCP_POOL = 20
    private const val REAL_PARALLEL = 5
    private const val SPEED_POOL = 2
    private val CF_RANGES = listOf("104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "162.158.0.0/15", "188.114.96.0/20", "141.101.64.0/18")

    /** [tlsMedianMs] is the slower of two real requests through the base config, in ms. */
    data class Pick(val ip: String, val tlsMedianMs: Int, val spreadMs: Int, val mbps: Double?, val tried: Int)

    /**
     * Where to look, best evidence first: the IP that won the last race on THIS network, the IPs the
     * competitors' own configs already carry ([extra] -- whichever wins still goes on everybody),
     * the scanner's archive (newest first), then a fresh sample of Cloudflare's ranges -- so an operator
     * who has never run the scanner still gets a measured clean IP, and one who has gets theirs.
     */
    /** The IP that won the last race on this network, if any. */
    fun lastWinner(context: Context): String? {
        val net = com.mlmvpn.scanner.engines.github.GtCleanIp.networkKey(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ip_$net", null)
    }

    /** A bare address: `[2606:…]` from a link's authority loses its brackets, or a rewrite doubles them. */
    fun bare(ip: String) = ip.trim().removePrefix("[").removeSuffix("]")

    fun candidates(context: Context, extra: List<String> = emptyList()): List<String> {
        val last = lastWinner(context)
        val gm = GroupManager(context)
        runCatching { gm.loadIpArchives() }
        fun usable(a: String) = com.mlmvpn.scanner.engines.github.GtCleanIp.isIpv4(a) || com.mlmvpn.scanner.data.CfFamily.isV6(a)
        val archive = gm.ipArchives.find { it.id == GroupManager.UNIFIED_ARCHIVE }?.ips.orEmpty()
            .asReversed().filter(::usable).take(ARCHIVE_POOL)
        // Both families (2026-09-28: Cloudflare IPv4 carried no tunnel data while IPv6 did); v6
        // first when IPv4 is measured dead here. The real test decides either way.
        val v6 = if (com.mlmvpn.scanner.data.CfFamily.hasIpv6RouteCached()) com.mlmvpn.scanner.data.CfFamily.sampleV6(SAMPLE_POOL / 2) else emptyList()
        val v4 = sample(SAMPLE_POOL)
        val fresh = if (com.mlmvpn.scanner.data.CfFamily.preferV6(context)) v6 + v4 else v4 + v6
        return (listOfNotNull(last) + extra + archive + fresh).map(::bare).filter(::usable).distinct()
    }

    /** Remembered per network, and added to the scanner's archive where every other feature finds it. */
    fun remember(context: Context, ip: String) {
        val net = com.mlmvpn.scanner.engines.github.GtCleanIp.networkKey(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ip_$net", ip).apply()
        runCatching { GroupManager(context).mergeIntoUnifiedArchive(listOf(ip)) }
    }

    private fun sample(n: Int): List<String> {
        val rnd = java.security.SecureRandom()
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < n && guard++ < n * 4) {
            val (base, bits) = CF_RANGES[rnd.nextInt(CF_RANGES.size)].split('/')
            val b = base.split('.').map { it.toLong() }
            val start = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
            val size = 1L shl (32 - bits.toInt())
            val ip = start + 1 + (rnd.nextLong().let { if (it < 0) -it else it } % (size - 2))
            out += "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"
        }
        return out.toList()
    }

    /**
     * The best IP for [sample], the base config (BPB's when it works -- the user's rule): the way
     * the scanner does it, fast and on real traffic.
     *  1. up to [TCP_POOL] addresses that answer TCP 443 quickly (proven ones always included);
     *  2. each one carries a real request through the base config, twice, [REAL_PARALLEL] at a
     *     time; both must answer, and the SLOWER of the two is its score, so a lucky shot cannot
     *     win over a steady address;
     *  3. the best [SPEED_POOL] carry the same exact 1 MB download, one at a time, and the most
     *     Mbit/s wins; if neither download finishes, the best real delay does.
     * Null when no address carried the base config at all.
     */
    suspend fun choose(
        context: Context,
        sample: String,
        extra: List<String>,
        speed: suspend (VpnConfig) -> Double?,
        onStep: (String) -> Unit,
    ): Pick? = coroutineScope {
        val pool = candidates(context, extra)
        if (pool.isEmpty()) return@coroutineScope null
        val base = VpnConfig.parseUri(sample) ?: return@coroutineScope null

        onStep(com.mlmvpn.scanner.store.tr("پینگ ${pool.size} آی‌پی کلادفلر…", "Pinging ${pool.size} Cloudflare IPs…"))
        val gate = Semaphore(16)
        val answered = pool.map { ip -> async { gate.withPermit { ip to QuickScanner.tcpOpen(ip, 443, 1_200) } } }.awaitAll()
            .filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }
            // IPv4 measured dead here: its addresses would pass TCP and then carry nothing.
            .let { l -> if (com.mlmvpn.scanner.data.CfFamily.preferV6(context)) l.filter { com.mlmvpn.scanner.data.CfFamily.isV6(it) }.ifEmpty { l } else l }
        val proven = (listOfNotNull(lastWinner(context)) + extra).filter { it in answered }.distinct()
        val open = (proven + answered).distinct().take(maxOf(TCP_POOL, proven.size))
        if (open.isEmpty()) return@coroutineScope null

        onStep(com.mlmvpn.scanner.store.tr("تست واقعی ${open.size} آی‌پی با کانفیگ پایه…", "Real test of ${open.size} IPs with the base config…"))
        val gate2 = Semaphore(REAL_PARALLEL)
        val real = open.map { ip ->
            async {
                gate2.withPermit {
                    val uri = CombineEngine.rewrite(sample, ip, "arena", base.port)
                    val a = CombineEngine.measureUri(context, uri, 5_000)
                    if (a <= 0) return@withPermit null
                    val b = CombineEngine.measureUri(context, uri, 5_000)
                    if (b <= 0) return@withPermit null
                    Triple(ip, maxOf(a, b).toInt(), kotlin.math.abs(a - b).toInt())
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.second }
        if (real.isEmpty()) return@coroutineScope null

        onStep(com.mlmvpn.scanner.store.tr("سرعت ${minOf(SPEED_POOL, real.size)} آی‌پی برتر…", "Speed of the top ${minOf(SPEED_POOL, real.size)}…"))
        // One at a time: parallel downloads would share the line and measure each other.
        val timed = real.take(SPEED_POOL).map { (ip, ms, spread) ->
            val cfg = VpnConfig.parseUri(CombineEngine.rewrite(sample, ip, "arena", base.port))
            Pick(ip, ms, spread, cfg?.let { runCatching { speed(it) }.getOrNull() }, pool.size)
        }
        timed.filter { it.mbps != null }.maxWithOrNull(compareBy<Pick> { it.mbps!! }.thenBy { -it.tlsMedianMs })
            ?: timed.first()
    }

    /** [uri] on [ip]: the address swapped, the worker's own name kept as SNI and host, the remark kept. */
    fun onto(uri: String, ipIn: String): String {
        val ip = bare(ipIn)
        val remark = android.net.Uri.decode(uri.substringAfter('#', "")).ifBlank { "arena" }
        return CombineEngine.rewrite(uri, ip, remark, null)
    }
}
