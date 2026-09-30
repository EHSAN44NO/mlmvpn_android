package com.mlmvpn.scanner.engines.mae.egress

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.data.CfEdgeHeal
import com.mlmvpn.scanner.data.CfFamily
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.arena.ArenaCleanIp
import com.mlmvpn.scanner.engines.arena.ArenaPanels
import com.mlmvpn.scanner.engines.github.GtCleanIp
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreign exits from the user's OWN Cloudflare panels -- BPB, Edge, MLM, Netra and the others the
 * Cloud tab installs -- for the apps that refuse Iranian addresses (TikTok first).
 *
 * The user's rule (2026-09-30): WARP, WARP-in-WARP, WireGuard and MASQUE all leave with an Iranian
 * address, so for such an app only Cloudflare configs will do; take them from the user's panels
 * automatically, put them on a clean IPv6 edge first and fall back to IPv4, and use the fastest.
 * "Fastest" is left to discovery: every variant is a candidate, proven per app like any foreign
 * exit, and the scorer ranks the survivors by latency and throughput.
 *
 * Configs are read through the same adapters the arena («میدان کانفیگ») races with, so a panel
 * joins here the day it joins there, and MLM hands out the arena's own user -- never one of the
 * operator's people.
 */
object MaeCloudConfigs {
    private const val TAG = "MAE"
    /** Links are read again after this long, or at once when none were found. */
    const val REFRESH_MS = 12 * 3600_000L
    /** A failed read is retried after this long, not on every check. */
    const val RETRY_MS = 3600_000L
    /** Per panel: a few shapes of its config are enough; more only make checks slower. */
    private const val PER_PANEL = 2
    /** The panels the user named first, then the rest that hand links out without side effects. */
    private val PANELS = listOf("BPB", "EDG", "MLM", "NTR", "GZG", "NVA", "NHN")

    /** Links from every panel installed on the user's Cloudflare accounts. Network: off the main thread. */
    suspend fun fetch(context: Context): List<String> {
        val out = mutableListOf<String>()
        val panels = ArenaPanels.ALL.filter { it.id in PANELS }.sortedBy { PANELS.indexOf(it.id) }
        for (account in CloudManager(context).accounts.toList()) {
            for (p in panels) {
                if (!runCatching { p.installed(context, account) }.getOrDefault(false)) continue
                val links = withTimeoutOrNull(25_000) { runCatching { p.candidates(context, account) }.getOrNull() }.orEmpty()
                val usable = usable(links).take(PER_PANEL)
                Log.i(TAG, "cloud configs: ${p.id} gave ${links.size}, ${usable.size} usable")
                out += usable
            }
        }
        return out.distinct()
    }

    /**
     * VLESS / Trojan over TLS on Cloudflare only: those are the ones a clean edge address can carry,
     * and the ones the Worker behind them turns into a foreign exit.
     */
    fun usable(links: List<String>): List<String> = links.filter { l ->
        (l.startsWith("vless://") || l.startsWith("trojan://")) &&
            VpnConfig.parseUri(l)?.let { c -> CfEdgeHeal.isCfFronted(c) || (c.tls.equals("tls", true) && CfFamily.isV6(c.address)) } == true
    }

    private const val EDGES_MS = 6 * 3600_000L
    private val edgesByNet = HashMap<String, Pair<List<String>, Long>>()

    /**
     * The edges to put them on, on network [net]: a clean IPv6 address first, the link's own IPv4
     * edge (or the arena's IPv4 winner) as the fallback -- unless this network is measured to drop
     * one family (CfFamily), which then is not offered at all. `""` means "as the link is".
     *
     * Kept per network for hours: without a scanned IPv6 address the pick is a random sample, and a
     * different address on every call would make every variant a new route each time.
     */
    fun edges(context: Context, net: String): List<String> = synchronized(edgesByNet) {
        val now = System.currentTimeMillis()
        edgesByNet[net]?.takeIf { now - it.second < EDGES_MS }?.first ?: pickEdges(context).also { edgesByNet[net] = it to now }
    }

    private fun pickEdges(context: Context): List<String> {
        val verdict = runCatching { CfFamily.current(context) }.getOrNull()
        val winner = runCatching { ArenaCleanIp.lastWinner(context) }.getOrNull()?.let { ArenaCleanIp.bare(it) }
        val v6 = if (verdict?.v6 == false || !runCatching { CfFamily.hasIpv6RouteCached() }.getOrDefault(false)) null
            else winner?.takeIf { CfFamily.isV6(it) } ?: runCatching { CfEdgeHeal.goodV6(context) }.getOrNull()
        val v4 = if (verdict?.v4 == false) null else (winner?.takeIf { GtCleanIp.isIpv4(it) } ?: "")
        return listOfNotNull(v6, v4).distinct()
    }

    /** Every link on every edge, IPv6 edges first. Same Worker, same name as SNI and host: only the address moves. */
    fun variants(links: List<String>, edges: List<String>): List<String> =
        edges.flatMap { e -> links.map { l -> if (e.isEmpty()) l else runCatching { ArenaCleanIp.onto(l, e) }.getOrDefault(l) } }.distinct()
}
