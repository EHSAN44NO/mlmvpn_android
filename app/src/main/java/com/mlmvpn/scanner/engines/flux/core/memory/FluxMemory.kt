package com.mlmvpn.scanner.engines.flux.core.memory

import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.model.Tri

/**
 * Pure transformations of [FluxState]: every change FLUX makes to what it remembers goes through
 * here, so the unit tests exercise the same code the engine runs.
 */
object FluxMemory {

    /** Weight of the newest sample in the success EWMA: about the last ten attempts matter. */
    private const val ALPHA = 0.2
    /** Consecutive failures that open the breaker. */
    const val BREAKER_THRESHOLD = 3
    private const val COOLDOWN_BASE_MS = 60_000L
    private const val COOLDOWN_MAX_MS = 6 * 3600_000L
    /** A route that keeps failing everywhere is parked for a day rather than retried hourly. */
    private const val QUARANTINE_MS = 24 * 3600_000L
    private const val NET_PRUNE_MS = 30L * 24 * 3600_000L
    private const val EDGES_KEEP = 8

    fun metrics(s: FluxState, net: String, candidateId: String): FluxMetrics? = s.metrics[FluxState.metricsKey(net, candidateId)]

    fun recordSuccess(
        s: FluxState, net: String, candidateId: String, now: Long,
        rttMs: Long?, handshakeMs: Long? = null, dial: String? = null,
    ): FluxState {
        val key = FluxState.metricsKey(net, candidateId)
        val m = s.metrics[key] ?: FluxMetrics()
        val next = m.copy(
            successEwma = m.successEwma * (1 - ALPHA) + ALPHA,
            rtts = if (rttMs == null) m.rtts else (m.rtts + rttMs).takeLast(FluxMetrics.RTT_KEEP),
            handshakeMs = handshakeMs ?: m.handshakeMs,
            okCount = m.okCount + 1,
            consecutiveFailures = 0,
            lastOkAt = now,
            cooldownUntil = 0L,
            lastReason = null,
            dial = dial ?: m.dial,
        )
        return s.copy(metrics = s.metrics + (key to next))
    }

    fun recordFailure(s: FluxState, net: String, candidateId: String, now: Long, reason: FailReason): FluxState {
        val key = FluxState.metricsKey(net, candidateId)
        val m = s.metrics[key] ?: FluxMetrics()
        val streak = m.consecutiveFailures + 1
        val ewma = m.successEwma * (1 - ALPHA)
        val cooldown = when {
            // Failed many times and almost never worked: a dead public node. Park it.
            m.failCount + 1 >= 8 && ewma < 0.1 -> now + QUARANTINE_MS
            streak >= BREAKER_THRESHOLD -> now + minOf(COOLDOWN_MAX_MS, COOLDOWN_BASE_MS shl minOf(16, streak - BREAKER_THRESHOLD))
            else -> 0L
        }
        val next = m.copy(
            successEwma = ewma,
            failCount = m.failCount + 1,
            consecutiveFailures = streak,
            lastFailAt = now,
            cooldownUntil = cooldown,
            lastReason = reason,
        )
        var out = s.copy(metrics = s.metrics + (key to next))
        if (reason == FailReason.RESET_AFTER_SNI) out = updateNet(out, net) { it.copy(sniResets = it.sniResets + 1) }
        return out
    }

    fun recordThroughput(s: FluxState, net: String, candidateId: String, kbps: Double): FluxState {
        val key = FluxState.metricsKey(net, candidateId)
        val m = s.metrics[key] ?: FluxMetrics()
        val next = m.copy(kbps = m.kbps?.let { it * 0.6 + kbps * 0.4 } ?: kbps)
        return s.copy(metrics = s.metrics + (key to next))
    }

    fun recordEgress(s: FluxState, egressKey: String, identity: FluxEgressIdentity): FluxState =
        s.copy(egress = s.egress + (egressKey to identity))

    fun egress(s: FluxState, egressKey: String): FluxEgressIdentity? = s.egress[egressKey]

    /** Countries with at least one route proven on any network, for the country picker. */
    fun provenCountries(s: FluxState, now: Long): List<String> =
        s.egress.values.filter { it.valid(now) && it.confidence >= 0.6 }.mapNotNull { it.countryCode }.distinct().sorted()

    /**
     * Countries with a route that worked on THIS network in the last day: offered enabled. Proven
     * elsewhere only is still listed, but as unknown here (the picker greys it).
     */
    fun countriesHere(s: FluxState, net: String, now: Long): List<String> {
        val prefix = "$net|"
        val dayAgo = now - 24 * 3600_000L
        return s.metrics.filter { (k, m) -> k.startsWith(prefix) && m.lastOkAt > dayAgo && !m.coolingDown(now) }
            .keys.mapNotNull { k ->
                val cid = k.removePrefix(prefix)
                val egressKey = cid.substringBeforeLast('/')
                s.egress[egressKey]?.takeIf { it.valid(now) }?.countryCode
            }.distinct().sorted()
    }

    fun setBest(s: FluxState, net: String, country: String?, mode: IpMode, routes: List<String>): FluxState =
        updateNet(s, net) { it.copy(best = it.best + (FluxState.policyKey(country, mode) to routes.distinct())) }

    fun best(s: FluxState, net: String, country: String?, mode: IpMode): List<String> =
        s.nets[net]?.best?.get(FluxState.policyKey(country, mode)).orEmpty()

    fun setVerdict(s: FluxState, net: String, verdict: NetVerdict): FluxState = updateNet(s, net) { it.copy(verdict = verdict) }

    /**
     * Learns from the race itself: any Cloudflare-fronted candidate that worked proves the family
     * reaches Cloudflare here; every such candidate failing at the TCP step proves the opposite.
     */
    fun learnCloudflare(s: FluxState, net: String, family: Family, reachable: Boolean, now: Long): FluxState = updateNet(s, net) {
        val v = it.verdict
        val t = Tri.of(reachable)
        it.copy(verdict = (if (family == Family.V4) v.copy(cfV4 = t) else v.copy(cfV6 = t)).copy(at = now))
    }

    fun learnUdp(s: FluxState, net: String, works: Boolean, now: Long): FluxState =
        updateNet(s, net) { it.copy(verdict = it.verdict.copy(udp = Tri.of(works), at = maxOf(it.verdict.at, now))) }

    fun rememberEdges(s: FluxState, net: String, family: Family, goodFirst: List<String>): FluxState = updateNet(s, net) {
        val merged = (goodFirst + it.edges[family].orEmpty()).distinct().take(EDGES_KEEP)
        it.copy(edges = it.edges + (family to merged))
    }

    fun forgetEdge(s: FluxState, net: String, family: Family, edge: String): FluxState = updateNet(s, net) {
        it.copy(edges = it.edges + (family to it.edges[family].orEmpty().filter { e -> e != edge }))
    }

    fun setFragment(s: FluxState, net: String, profile: FragmentProfile?): FluxState = updateNet(s, net) { it.copy(fragment = profile) }

    fun setMux(s: FluxState, net: String, better: Boolean): FluxState = updateNet(s, net) { it.copy(mux = Tri.of(better)) }

    fun markSeen(s: FluxState, net: String, now: Long): FluxState = updateNet(s, net) { it.copy(lastSeen = now) }

    /** Networks unseen for a month, and everything learned on them, are dropped. */
    fun prune(s: FluxState, now: Long): FluxState {
        val stale = s.nets.filter { now - it.value.lastSeen > NET_PRUNE_MS }.keys
        if (stale.isEmpty()) return s
        return s.copy(
            nets = s.nets - stale,
            metrics = s.metrics.filterKeys { k -> stale.none { k.startsWith("$it|") } },
        )
    }

    /** Mobile-data budget for FLUX's own probes: refuses once [limit] bytes were spent today. */
    fun spend(s: FluxState, now: Long, bytes: Long): FluxState {
        val day = now / 86_400_000L
        val cur = if (s.data.day == day) s.data.bytes else 0L
        return s.copy(data = DataBudget(day, cur + bytes))
    }

    fun spentToday(s: FluxState, now: Long): Long = if (s.data.day == now / 86_400_000L) s.data.bytes else 0L

    private inline fun updateNet(s: FluxState, net: String, f: (NetProfile) -> NetProfile): FluxState =
        s.copy(nets = s.nets + (net to f(s.nets[net] ?: NetProfile())))
}
