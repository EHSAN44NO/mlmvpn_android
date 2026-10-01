package com.mlmvpn.scanner.engines.flux.core.plan

import com.mlmvpn.scanner.engines.flux.core.memory.FluxMemory
import com.mlmvpn.scanner.engines.flux.core.memory.FluxState
import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.Proto
import com.mlmvpn.scanner.engines.flux.core.net.Cloudflare
import com.mlmvpn.scanner.engines.flux.core.race.FluxProbe
import com.mlmvpn.scanner.engines.flux.core.race.FluxRacer
import com.mlmvpn.scanner.engines.flux.core.score.FluxScorer

/**
 * Turns what a race (or a live check) observed into memory. Pure: in, state and observations;
 * out, the next state.
 */
object FluxLearner {

    /**
     * Everything a race taught: per-candidate success/failure, the exits it measured, whether
     * Cloudflare and UDP get through on this network, which edges answered, whether fragmenting
     * paid off -- and the routes to use (primary + standbys) for this policy.
     */
    fun afterRace(
        s0: FluxState, net: String, now: Long, country: String?, mode: IpMode,
        tried: List<FluxCandidate>, out: FluxRacer.Outcome,
        /** Off for a discovery race: it learns exits and health, but does not pick the routes in use. */
        setBest: Boolean = true,
    ): FluxState {
        var s = FluxMemory.markSeen(s0, net, now)
        val byId = tried.associateBy { it.id }

        // Stage 1 failures are failures of the dial address on this network.
        out.reach.forEach { (cid, r) ->
            if (!r.ok) s = FluxMemory.recordFailure(s, net, cid, now, r.reason ?: FailReason.OTHER)
        }
        out.real.forEach { (cid, r) ->
            val c = byId[cid] ?: return@forEach
            s = if (r.ok) FluxMemory.recordSuccess(s, net, cid, now, r.rttMs, r.handshakeMs ?: out.reach[cid]?.ms, if (c.edge == null) c.dialAddress else null)
            else FluxMemory.recordFailure(s, net, cid, now, r.reason ?: FailReason.OTHER)
            r.egress?.let { e -> if (e.countryCode != null || e.confidence > 0) s = FluxMemory.recordEgress(s, c.egressKey, mergeEgress(s.egress[c.egressKey], e)) }
        }
        // A route in the wrong country still worked: it is remembered as working (for Automatic
        // and for that other country), just not chosen here.
        out.refused.forEach { (c, r) -> r.egress?.let { e -> s = FluxMemory.recordEgress(s, c.egressKey, mergeEgress(s.egress[c.egressKey], e)) } }

        s = learnCloudflare(s, net, now, tried, out)
        s = learnUdp(s, net, now, tried, out)
        s = learnEdges(s, net, tried, out)
        s = learnFragment(s, net, out)

        if (setBest && out.ranked.isNotEmpty()) s = FluxMemory.setBest(s, net, country, mode, pickRoutes(out.ranked.map { it.first }))
        return s
    }

    /**
     * Primary plus standbys: best first, then the best of OTHER nodes -- a standby on the same
     * server as the primary dies with it.
     */
    fun pickRoutes(ranked: List<FluxCandidate>, n: Int = 3): List<String> {
        val out = ArrayList<FluxCandidate>()
        for (c in ranked) if (out.none { it.node.id == c.node.id }) { out += c; if (out.size == n) break }
        if (out.size < n) for (c in ranked) if (c !in out) { out += c; if (out.size == n) break }
        return out.map { it.id }
    }

    /** A live failure of the route in use (the canary through the tunnel failed). */
    fun liveFailure(s: FluxState, net: String, cid: String, now: Long, reason: FailReason) = FluxMemory.recordFailure(s, net, cid, now, reason)

    /** A live success: refreshes the route's freshness and its live round trip. */
    fun liveSuccess(s: FluxState, net: String, cid: String, now: Long, rttMs: Long?) = FluxMemory.recordSuccess(s, net, cid, now, rttMs)

    /**
     * Mux on vs off, measured on the same route: kept on only when clearly better (15%), because a
     * multiplexed tunnel that stalls stalls every connection at once.
     */
    fun decideMux(s: FluxState, net: String, offMs: Long, onMs: Long): FluxState =
        FluxMemory.setMux(s, net, onMs < offMs * (1 - FluxScorer.SWITCH_MARGIN))

    private fun mergeEgress(old: com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity?, new: com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity) =
        // A stronger (two-source) proof is not replaced by a weaker one taken during a race.
        if (old != null && old.confidence > new.confidence && old.countryCode == new.countryCode && new.verifiedAt - old.verifiedAt < 3600_000L) old.copy(verifiedAt = new.verifiedAt)
        else new.copy(ipv4 = new.ipv4 ?: old?.ipv4, ipv6 = new.ipv6 ?: old?.ipv6)

    private fun isCfDial(c: FluxCandidate) = c.edge != null || Cloudflare.isCloudflare(c.dialAddress)

    private fun learnCloudflare(s0: FluxState, net: String, now: Long, tried: List<FluxCandidate>, out: FluxRacer.Outcome): FluxState {
        var s = s0
        for (f in Family.values()) {
            val cf = tried.filter { it.family == f && isCfDial(it) && out.reach.containsKey(it.id) }
            if (cf.isEmpty()) continue
            val anyOk = cf.any { out.reach[it.id]?.ok == true }
            // "Cut" only when several Cloudflare addresses all failed at the TCP step: one dead
            // edge says nothing about the network.
            val allTcpDead = cf.size >= 3 && cf.all { out.reach[it.id]?.let { r -> !r.ok && (r.reason == FailReason.TCP_TIMEOUT || r.reason == FailReason.TCP_REFUSED || r.reason == FailReason.RESET_AFTER_SNI) } == true }
            if (anyOk) s = FluxMemory.learnCloudflare(s, net, f, true, now)
            else if (allTcpDead) s = FluxMemory.learnCloudflare(s, net, f, false, now)
        }
        return s
    }

    private fun learnUdp(s: FluxState, net: String, now: Long, tried: List<FluxCandidate>, out: FluxRacer.Outcome): FluxState {
        val hy = tried.filter { it.node.proto == Proto.HY2 && out.real.containsKey(it.id) }
        if (hy.isEmpty()) return s
        val ok = hy.any { out.real[it.id]?.ok == true }
        // Several Hysteria2 nodes all silent: UDP is shaped or blocked on this network.
        return if (ok) FluxMemory.learnUdp(s, net, true, now) else if (hy.size >= 3) FluxMemory.learnUdp(s, net, false, now) else s
    }

    private fun learnEdges(s0: FluxState, net: String, tried: List<FluxCandidate>, out: FluxRacer.Outcome): FluxState {
        var s = s0
        for (f in Family.values()) {
            val edged = tried.filter { it.edge != null && it.family == f }
            if (edged.isEmpty()) continue
            val good = edged.filter { out.reach[it.id]?.ok == true }.sortedBy { out.reach[it.id]?.ms ?: Long.MAX_VALUE }.mapNotNull { it.edge }.distinct()
            if (good.isNotEmpty()) s = FluxMemory.rememberEdges(s, net, f, good)
            edged.filter { out.reach[it.id]?.ok == false }.mapNotNull { it.edge }.distinct()
                .filter { it !in good }.forEach { s = FluxMemory.forgetEdge(s, net, f, it) }
        }
        return s
    }

    /**
     * Fragmenting is kept for a network only when a fragmented candidate worked AND its plain twin
     * did not; the moment plain works again it is dropped, so no network pays for it forever.
     */
    private fun learnFragment(s: FluxState, net: String, out: FluxRacer.Outcome): FluxState {
        val ok = out.ranked.map { it.first }
        if (ok.isEmpty()) return s
        val plainOk = ok.any { it.fragment == FragmentProfile.OFF && it.node.proto != Proto.HY2 }
        val fragOk = ok.filter { it.fragment != FragmentProfile.OFF }
        val current = s.nets[net]?.fragment
        return when {
            plainOk && current != null -> FluxMemory.setFragment(s, net, null)
            !plainOk && fragOk.isNotEmpty() -> FluxMemory.setFragment(s, net, fragOk.first().fragment)
            else -> s
        }
    }
}
