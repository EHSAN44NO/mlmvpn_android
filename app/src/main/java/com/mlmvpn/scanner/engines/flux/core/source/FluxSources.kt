package com.mlmvpn.scanner.engines.flux.core.source

import com.mlmvpn.scanner.engines.flux.core.model.FluxNode
import com.mlmvpn.scanner.engines.flux.core.parse.FluxLinkParser

/**
 * Where FLUX's nodes come from. A source is data, not code: adding or dropping one changes nothing
 * in the engine. FLUX's sources are its own -- none of them is one Free Configs or Quick Connect
 * reads -- so the three features never fight over the same public servers.
 *
 * Two kinds are deliberately mixed, because Cloudflare is cut on many Iranian networks:
 *  - Cloudflare-fronted nodes (WebSocket/TLS on a CDN edge): very resilient where Cloudflare
 *    reaches, multiplied by edge expansion;
 *  - direct nodes (VLESS REALITY, Hysteria2 over UDP, plain TLS): the only ones that work where
 *    Cloudflare does not.
 */
data class NodeSource(
    val id: String,
    val url: String,
    /** Refused above this size: a list is a few hundred KB at most, never worth a whole data plan. */
    val maxBytes: Int = 512 * 1024,
    /** Large lists are refreshed on Wi-Fi only. */
    val wifiOnly: Boolean = false,
    /** Nodes taken from this source at most: the first lines of a pre-tested list are its best. */
    val maxNodes: Int = 200,
    /** Refresh no more often than this. */
    val minIntervalMs: Long = 6 * 3600_000L,
)

/** A source's health, so one broken list never blocks a connect and its share can shrink. */
data class SourceHealth(
    val lastOkAt: Long = 0L,
    val lastFailAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val usable: Int = 0,
    val rejected: Int = 0,
    val etag: String? = null,
    val lastModified: String? = null,
)

object FluxSources {

    /**
     * The built-in catalog. Measured 2026-10-01: Free-Configs ships about 10 Cloudflare-fronted
     * nodes its publisher already tested from Iran; MatinGhanbari's filtered lists carry REALITY
     * (~140) and Hysteria2 (~190) nodes, which do not touch Cloudflare at all.
     */
    val BUILT_IN = listOf(
        NodeSource("pf-cf", "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt", maxBytes = 256 * 1024, minIntervalMs = 3600_000L),
        NodeSource("mg-vless", "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vless.txt"),
        NodeSource("mg-hy2", "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/hysteria2.txt"),
        NodeSource("mg-trojan", "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/trojan.txt"),
    )

    /** A user's own subscription, added from FLUX's diagnostics page. */
    fun userSource(index: Int, url: String) = NodeSource("user-$index", url.trim(), maxBytes = 1024 * 1024, maxNodes = 500, minIntervalMs = 3600_000L)

    fun isHttpsUrl(url: String): Boolean = url.trim().let { it.startsWith("https://") && it.length in 12..2048 && !it.contains(' ') }

    /** Parses one source's body into nodes, capped at [NodeSource.maxNodes]. */
    fun parse(source: NodeSource, body: String): FluxLinkParser.Batch {
        val batch = FluxLinkParser.parseBody(body, source.id)
        return batch.copy(nodes = batch.nodes.take(source.maxNodes))
    }

    /** All sources' nodes into one list without duplicates (the same server in two lists counts once). */
    fun merge(batches: List<List<FluxNode>>): List<FluxNode> {
        val seen = LinkedHashMap<String, FluxNode>()
        // Interleaved, so one big list cannot push a small curated one out of the race budget.
        val iters = batches.map { it.iterator() }
        var any = true
        while (any) {
            any = false
            for (it in iters) if (it.hasNext()) { any = true; val n = it.next(); seen.putIfAbsent(n.id, n) }
        }
        return seen.values.toList()
    }

    fun recordOk(h: SourceHealth, now: Long, usable: Int, rejected: Int, etag: String?, lastModified: String?) =
        h.copy(lastOkAt = now, consecutiveFailures = 0, usable = usable, rejected = rejected, etag = etag ?: h.etag, lastModified = lastModified ?: h.lastModified)

    fun recordNotModified(h: SourceHealth, now: Long) = h.copy(lastOkAt = now, consecutiveFailures = 0)

    fun recordFail(h: SourceHealth, now: Long) = h.copy(lastFailAt = now, consecutiveFailures = h.consecutiveFailures + 1)

    /** Due for a refresh: past its interval, and backing off after failures (1h, 2h, 4h ... 24h). */
    fun due(src: NodeSource, h: SourceHealth?, now: Long): Boolean {
        if (h == null) return true
        if (h.consecutiveFailures > 0) {
            val backoff = minOf(24 * 3600_000L, 3600_000L shl minOf(5, h.consecutiveFailures - 1))
            return now - h.lastFailAt >= backoff
        }
        return now - h.lastOkAt >= src.minIntervalMs
    }
}
