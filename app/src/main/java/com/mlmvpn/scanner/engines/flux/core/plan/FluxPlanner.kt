package com.mlmvpn.scanner.engines.flux.core.plan

import com.mlmvpn.scanner.engines.flux.core.country.EgressVerifier
import com.mlmvpn.scanner.engines.flux.core.memory.FluxMemory
import com.mlmvpn.scanner.engines.flux.core.memory.FluxState
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FluxNode
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.model.Proto
import com.mlmvpn.scanner.engines.flux.core.model.Tri
import com.mlmvpn.scanner.engines.flux.core.net.Cloudflare
import com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds
import com.mlmvpn.scanner.engines.flux.core.score.FluxScorer
import kotlin.random.Random

/**
 * Decides what to try, before anything is tried. Two paths:
 *
 *  - [warm]: this network has a known good route for the user's choice -> connect to it at once,
 *    with its standbys, no race. This is what makes a second connect near instant.
 *  - [candidates]: a cold start (or the warm route failed) -> a bounded, ordered list for the race.
 *
 * The Cloudflare question is answered here: when the network's verdict says Cloudflare is cut, no
 * candidate that dials a Cloudflare address is produced at all -- they cost the user nothing.
 */
object FluxPlanner {

    data class Input(
        val nodes: List<FluxNode>,
        val state: FluxState,
        val net: String,
        val now: Long,
        val country: String?,
        val mode: IpMode,
        val verdict: NetVerdict,
        /** A node's server resolved per family (literal servers need no entry). */
        val resolved: Map<String, Map<Family, String>> = emptyMap(),
        /** Fresh Cloudflare edges for a family when this network has none learned. */
        val sampleEdges: (Family, Int) -> List<String> = { f, n -> Cloudflare.sampleEdges(f, n) },
        /** Upper bound on candidates handed to the race. */
        val budget: Int = 32,
        /** 1 = verified TLS only; 2 = also self-signed Hysteria2 (only after wave 1 found nothing). */
        val wave: Int = 1,
        val random: Random = Random(0),
    )

    /** Families usable for this mode on this network. Empty = the mode cannot work here. */
    fun families(mode: IpMode, verdict: NetVerdict): List<Family> =
        mode.families.filter { it == Family.V4 || verdict.v6 != Tri.NO }

    /**
     * The known route for this policy on this network, primary first, rebuilt from memory. Empty
     * when there is none worth trusting (cooling down, stale, or the wrong country).
     */
    fun warm(inp: Input): List<FluxCandidate> {
        val byId = inp.nodes.associateBy { it.id }
        val fams = families(inp.mode, inp.verdict)
        return FluxMemory.best(inp.state, inp.net, inp.country, inp.mode).mapNotNull { cid ->
            val c = rebuild(cid, byId, inp) ?: return@mapNotNull null
            val m = FluxMemory.metrics(inp.state, inp.net, cid) ?: return@mapNotNull null
            val fresh = inp.now - m.lastOkAt < WARM_MAX_AGE_MS
            val countryOk = EgressVerifier.accepts(inp.state.egress[c.egressKey], inp.country, inp.now)
            val cfOk = !(inp.verdict.cf(c.family) == Tri.NO && Cloudflare.isCloudflare(c.dialAddress))
            c.takeIf { fresh && !m.coolingDown(inp.now) && countryOk && cfOk && c.family in fams }
        }
    }

    /** A candidate id back into a candidate, when its node is still in the sources. */
    fun rebuild(cid: String, nodes: Map<String, FluxNode>, inp: Input): FluxCandidate? {
        val at = cid.indexOf('@'); val slash = cid.lastIndexOf('/')
        if (at <= 0 || slash < at) return null
        val node = nodes[cid.substring(0, at)] ?: return null
        val edge = cid.substring(at + 1, slash).takeIf { it != "o" }
        val tail = cid.substring(slash + 1)
        val family = Family.of(tail.take(1)) ?: return null
        val fragment = FragmentProfile.of(tail.getOrNull(1)?.toString())
        val mux = tail.endsWith("m")
        val dial = edge ?: FluxMemory.metrics(inp.state, inp.net, cid)?.dial ?: originFor(node, family, inp) ?: return null
        return FluxCandidate(node, edge, family, fragment, mux, dial).takeIf { it.id == cid }
    }

    fun candidates(inp: Input): List<FluxCandidate> {
        val fams = families(inp.mode, inp.verdict)
        if (fams.isEmpty()) return emptyList()
        val net = inp.state.nets[inp.net]
        val fragment = net?.fragment ?: FragmentProfile.OFF
        val tryFragmentToo = fragment == FragmentProfile.OFF && (net?.sniResets ?: 0) >= 2
        val mux = net?.mux == Tri.YES
        val edgeCache = HashMap<Family, List<String>>()
        fun edgesFor(f: Family) = edgeCache.getOrPut(f) {
            net?.edges?.get(f).orEmpty().take(EDGES_PER_NODE).ifEmpty { inp.sampleEdges(f, EDGES_PER_NODE) }
        }

        val out = ArrayList<FluxCandidate>()
        for (node in inp.nodes) {
            if (node.insecure && inp.wave < 2) continue
            if (node.proto == Proto.HY2 && inp.verdict.udp == Tri.NO) continue
            for (f in fams) {
                val origin = originFor(node, f, inp)
                val originIsCf = origin != null && Cloudflare.isCloudflare(origin)
                val cfCut = inp.verdict.cf(f) == Tri.NO
                val dials = ArrayList<Pair<String?, String>>() // edge (null = origin) to address
                if (node.edgeExpandable && (originIsCf || origin == null)) {
                    // A CDN-fronted node: its own address and a few edges, all Cloudflare. None of
                    // them is worth a try where Cloudflare is cut.
                    if (cfCut) continue
                    if (origin != null) dials += null to origin
                    edgesFor(f).filter { it != origin }.forEach { dials += it to it }
                } else {
                    if (origin == null) continue
                    if (originIsCf && cfCut) continue
                    dials += null to origin
                }
                for ((edge, addr) in dials) {
                    val base = FluxCandidate(node, edge, f, FragmentProfile.OFF, mux && FluxOutbounds.muxable(FluxCandidate(node, edge, f, dialAddress = addr)), addr)
                    if (fragment != FragmentProfile.OFF && FluxOutbounds.fragmentable(base)) {
                        // The learned profile first, and the plain twin too: the day plain works
                        // again here, the learner sees it and drops fragmenting.
                        out += base.copy(fragment = fragment)
                        out += base
                    } else {
                        out += base
                        if (tryFragmentToo && FluxOutbounds.fragmentable(base)) out += base.copy(fragment = FragmentProfile.BALANCED)
                    }
                }
            }
        }
        return order(out.distinctBy { it.id }, inp).take(inp.budget)
    }

    /**
     * Known-good first (by score), then a share of untried ones so new nodes get their chance
     * (exploration), never more than [PER_NODE] variants of one node so a single flaky server
     * cannot fill the race. Candidates whose exit is proven in the wrong country are dropped;
     * proven in the right one go first.
     */
    private fun order(all: List<FluxCandidate>, inp: Input): List<FluxCandidate> {
        val usable = all.filter { c ->
            val m = FluxMemory.metrics(inp.state, inp.net, c.id)
            if (m?.coolingDown(inp.now) == true) return@filter false
            val e = inp.state.egress[c.egressKey]
            // Proven elsewhere: reject a known wrong country, keep unknowns (the race checks them).
            !(inp.country != null && e != null && e.valid(inp.now) && e.countryCode != null && !e.countryCode.equals(inp.country, true))
        }
        fun countryMatch(c: FluxCandidate) = inp.country != null && EgressVerifier.accepts(inp.state.egress[c.egressKey], inp.country, inp.now)
        val (known, unknown) = usable.partition { FluxMemory.metrics(inp.state, inp.net, it.id)?.lastOkAt?.let { t -> t > 0 } == true }
        val knownSorted = known.sortedWith(compareByDescending<FluxCandidate> { countryMatch(it) }
            .thenByDescending { FluxScorer.score(it, FluxMemory.metrics(inp.state, inp.net, it.id), null, inp.now) })
        val unknownSorted = unknown.shuffled(inp.random).sortedWith(compareByDescending<FluxCandidate> { countryMatch(it) }
            .thenBy { if (it.edge == null) 0 else 1 }
            .thenBy { it.fragment != FragmentProfile.OFF })
        val merged = ArrayList<FluxCandidate>(usable.size)
        var k = 0; var u = 0
        // Three known for every unknown while both last; then whatever remains.
        while (k < knownSorted.size || u < unknownSorted.size) {
            repeat(3) { if (k < knownSorted.size) merged += knownSorted[k++] }
            if (u < unknownSorted.size) merged += unknownSorted[u++]
        }
        val perNode = HashMap<String, Int>()
        return merged.filter { c -> (perNode.merge(c.node.id, 1, Int::plus) ?: 0) <= PER_NODE }
    }

    private fun originFor(node: FluxNode, f: Family, inp: Input): String? {
        val lit = Family.ofLiteral(node.server)
        if (lit != null) return node.server.takeIf { lit == f }
        return inp.resolved[node.id]?.get(f)
    }

    const val WARM_MAX_AGE_MS = 48 * 3600_000L
    const val EDGES_PER_NODE = 3
    const val PER_NODE = 4
}
