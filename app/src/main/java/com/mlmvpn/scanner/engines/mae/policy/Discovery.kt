package com.mlmvpn.scanner.engines.mae.policy

import com.mlmvpn.scanner.engines.mae.classify.Classifier
import com.mlmvpn.scanner.engines.mae.model.Axis
import com.mlmvpn.scanner.engines.mae.model.Diagnosis
import com.mlmvpn.scanner.engines.mae.model.EgressProof
import com.mlmvpn.scanner.engines.mae.model.Family
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ForeignEgressStatus
import com.mlmvpn.scanner.engines.mae.model.RouteMetrics
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.probe.EgressEcho
import com.mlmvpn.scanner.engines.mae.probe.Observation
import com.mlmvpn.scanner.engines.mae.probe.ProbeRoute
import com.mlmvpn.scanner.engines.mae.probe.Prober
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** How much probing a discovery may spend. Tighter on mobile data. */
data class ProbeBudget(
    val maxConcurrent: Int = 3,
    val rttSamples: Int = 3,
    /** Stage C only for this many finalists. */
    val finalists: Int = 2,
    val throughputBytes: Int = 1_000_000,
) {
    companion object {
        val WIFI = ProbeBudget()
        val CELLULAR = ProbeBudget(maxConcurrent = 2, rttSamples = 3, finalists = 2, throughputBytes = 256 * 1024)
    }
}

data class DiscoveryResult(
    val diagnosis: Diagnosis,
    val observations: List<Observation>,
    /** Per route id. */
    val metrics: Map<String, RouteMetrics>,
    /** Per route id + family. */
    val proofs: List<EgressProof>,
    val egress: ForeignEgressStatus,
    /** Stage C was run on these route ids (for tests and diagnostics). */
    val measured: Set<String>,
) {
    /**
     * Nothing got through anywhere -- no TCP connection, no answer, no exit echo: the network itself
     * was down (a captive portal, a dead minute). Such a result says nothing about the routes and
     * is not recorded.
     */
    val offline: Boolean get() = com.mlmvpn.scanner.engines.mae.route.RouteHealth.networkLooksDown(observations)
}

/**
 * Exit echoes are a property of a route and family on one network, not of a service: kept for a
 * few minutes across discoveries, so checking five apps does not ask the same exit where it is
 * five times (and does not run into ip-api's rate limit). A missing echo is kept only briefly.
 */
class EchoCache(private val ttlMs: Long = 10 * 60_000L, private val missTtlMs: Long = 2 * 60_000L) {
    private data class E(val echo: EgressEcho?, val at: Long)
    private val m = HashMap<String, E>()

    /** (known, echo): known is false when there is nothing fresh for [key]. */
    @Synchronized fun get(key: String, now: Long): Pair<Boolean, EgressEcho?> {
        val e = m[key] ?: return false to null
        val ttl = if (e.echo == null) missTtlMs else ttlMs
        return if (now - e.at < ttl) true to e.echo else false to null
    }

    @Synchronized fun put(key: String, echo: EgressEcho?, now: Long) { m[key] = E(echo, now) }

    @Synchronized fun clear() = m.clear()
}

/**
 * The staged discovery for one service on one network:
 *  A. every local route: DNS/TCP/TLS + one small GET (a few KB);
 *     foreign routes too, but only when geo is not already ruled out -- no Worker quota spent on
 *     a service that works fine from Iran;
 *  B. survivors: a few RTT samples;
 *  C. the top [ProbeBudget.finalists] only: one throughput sample.
 * Then the classifier reads everything.
 *
 * Foreign families are tried separately. A family counts for this service only if (1) the exit
 * really is abroad, seen through the route itself, and (2) the service's own probe was served
 * through it. "The IP is in the US" is never enough on its own.
 */
class Discovery(
    private val prober: Prober,
    private val budget: ProbeBudget = ProbeBudget.WIFI,
    private val now: () -> Long = System::currentTimeMillis,
    /** Shared across discoveries on one network (see [EchoCache]); a private one by default. */
    private val echoes: EchoCache = EchoCache(),
    /** The network the echoes are cached under. */
    private val net: String = "",
) {
    private suspend fun echo(r: ProbeRoute): EgressEcho? {
        val k = "$net|${key(r)}"
        val (known, cached) = echoes.get(k, now())
        if (known) return cached
        return prober.egress(r).also { echoes.put(k, it, now()) }
    }

    suspend fun run(
        service: ServiceDef,
        routes: List<ProbeRoute>,
        prior: Map<String, RouteMetrics> = emptyMap(),
        /** Probe foreign exits even when a local route looks fine (a repair asked for it). */
        forceForeign: Boolean = false,
    ): DiscoveryResult = coroutineScope {
        val gate = Semaphore(budget.maxConcurrent)
        // Every probe must pass: an API that answers is not the app working (Gemini's API said
        // "fine" through an exit whose web app still refused the country).
        suspend fun observeAll(r: ProbeRoute): com.mlmvpn.scanner.engines.mae.probe.Observation {
            var first: com.mlmvpn.scanner.engines.mae.probe.Observation? = null
            for (p in service.probes) {
                val o = prober.observe(r, p)
                if (first == null) first = o
                if (!o.usable) return o
            }
            return first!!
        }
        val spec = service.probes.firstOrNull()
            ?: return@coroutineScope DiscoveryResult(Diagnosis(), emptyList(), emptyMap(), emptyList(), ForeignEgressStatus.Untested, emptySet())

        // --- Stage A, local
        val local = routes.filter { it.kind != RouteKind.FOREIGN }
        val obs = local.map { r -> async { gate.withPermit { observeAll(r) } } }.map { it.await() }.toMutableList()

        // Local routes' per-family results are kept too: on some networks IPv4 and IPv6 of the
        // SAME direct path are different countries to a service.
        val proofs = mutableListOf<EgressProof>()
        for ((r, o) in local.zip(obs)) {
            proofs += EgressProof("${r.routeId}:${r.family}", familyOf(r.family), null, o.usable,
                if (o.usable) "served" else "not served (${o.tls}${o.http?.let { ", HTTP ${it.status}" } ?: ""})", now())
        }
        // A route probed per family is usable for BOTH when every probed family was.
        for ((id, rs) in local.zip(obs).groupBy { it.first.routeId }) {
            val fams = rs.map { it.first.family }.toSet()
            if (FamilyPolicy.BOTH !in fams && fams.size >= 2 && rs.all { it.second.usable }) {
                proofs += EgressProof("$id:${FamilyPolicy.BOTH}", Family.V4, null, true, "served on every family", now())
            }
        }

        // --- Stage A, foreign: only if geo is not already ruled out by a clean local answer.
        val first = Classifier.classify(obs, now())
        // An app that refuses Iran whatever its web page shows: a local page proves nothing.
        val geoRuledOut = first[Axis.GEO_RESTRICTION].state == Tri.NO && !service.hints.requiresForeign
        val localWorks = obs.any { it.usable }
        val foreignRoutes = routes.filter { it.kind == RouteKind.FOREIGN }
        val rejected = LinkedHashMap<String, String>()
        // Nothing suggests a geo block (no hint, no refusal text to look for) and a local route
        // works: a foreign exit has nothing to add, so no Worker quota is spent proving one.
        val nothingToProve = localWorks && !service.hints.likelyGeoRestricted && !service.hints.requiresForeign &&
            spec.geoSignatures.isEmpty() && obs.none { it.refusedCountry }
        val probeForeign = forceForeign || service.hints.requiresForeign || (!nothingToProve && (!geoRuledOut || !localWorks))
        if (probeForeign) {
            val results = foreignRoutes.map { r ->
                async {
                    gate.withPermit {
                        val echo = echo(r)
                        val o = observeAll(r).copy(exitAlive = echo != null, limitedExit = r.limited)
                        Triple(r, echo, o)
                    }
                }
            }.map { it.await() }
            for ((r, echo, o) in results) {
                obs += o
                val family = familyOf(r.family)
                val abroad = echo?.country != null && echo.country != "IR"
                // A forced family whose echo shows the other family: the strategy did not apply.
                val familyHonoured = when (r.family) {
                    FamilyPolicy.V4_ONLY -> echo?.isV6 == false
                    FamilyPolicy.V6_ONLY -> echo?.isV6 == true
                    FamilyPolicy.BOTH -> echo != null
                }
                val accepted = abroad && familyHonoured && o.usable
                val reason = when {
                    echo == null -> NO_ECHO
                    !familyHonoured -> "asked for ${r.family}, exit used the other family"
                    !abroad -> "exit country ${echo.country ?: "unknown"}"
                    !o.usable -> if (o.refusedCountry) "service refused this exit" else "service not reached (${o.tls})"
                    else -> "accepted (${echo.country})"
                }
                proofs += EgressProof("${r.routeId}:${r.family}", family, echo?.country, accepted, reason, now())
                if (!accepted) rejected["${r.routeId}:${r.family}"] = reason
            }
        }

        // --- Stage B: RTT on survivors
        val survivors = routes.filter { r -> proofs.any { p -> p.routeId == "${r.routeId}:${r.family}" && p.serviceAccepted } }
            .distinctBy { it.routeId to it.family }
        val rtts = survivors.map { r -> async { gate.withPermit { r to prober.rtt(r, spec, budget.rttSamples) } } }.map { it.await() }

        // --- Stage C: throughput, finalists only -- different routes: two families of one route
        // share its speed and would leave the runner-up route unmeasured.
        val finalists = rtts.filter { it.second != null }.sortedBy { it.second }.distinctBy { it.first.routeId }
            .take(budget.finalists).map { it.first }
        val tputs = finalists.map { r -> async { gate.withPermit { r to prober.throughput(r, budget.throughputBytes) } } }.map { it.await() }

        // --- Metrics per route id (families of one route merge; the scorer picks the family)
        val t = now()
        val metrics = HashMap<String, RouteMetrics>()
        for (r in routes.distinctBy { it.routeId }) {
            var m = prior[r.routeId] ?: RouteMetrics()
            val mine = obs.filter { it.routeId == r.routeId }
            if (mine.isEmpty()) continue
            val ok = mine.any { it.usable }
            val rtt = rtts.filter { it.first.routeId == r.routeId }.mapNotNull { it.second }.minOrNull()
                ?: mine.mapNotNull { it.rttMs }.minOrNull()
            m = m.record(ok, rtt?.toDouble(), t)
            tputs.filter { it.first.routeId == r.routeId }.mapNotNull { it.second }.maxOrNull()?.let { m = m.withThroughput(it) }
            metrics[r.routeId] = m
        }

        val diagnosis = Classifier.classify(obs, t)
        val foreignIds = foreignRoutes.map { it.routeId }.toSet()
        val accepted = proofs.filter { it.serviceAccepted && it.routeId.substringBefore(':') in foreignIds }
        val egress = when {
            accepted.isNotEmpty() -> {
                val best = accepted.first().routeId.substringBefore(':')
                val fams = accepted.filter { it.routeId.startsWith("$best:") }.map { FamilyPolicy.valueOf(it.routeId.substringAfter(':')) }.toSet()
                ForeignEgressStatus.Proven(best, when {
                    FamilyPolicy.BOTH in fams || (FamilyPolicy.V4_ONLY in fams && FamilyPolicy.V6_ONLY in fams) -> FamilyPolicy.BOTH
                    FamilyPolicy.V6_ONLY in fams -> FamilyPolicy.V6_ONLY
                    else -> FamilyPolicy.V4_ONLY
                })
            }
            foreignRoutes.isEmpty() || !probeForeign -> ForeignEgressStatus.Untested
            else -> ForeignEgressStatus.NoneFound(rejected)
        }
        DiscoveryResult(diagnosis, obs, metrics, proofs, egress, finalists.map { it.routeId }.toSet())
    }

    companion object {
        const val NO_ECHO = "exit not reachable / no echo"
    }

    private fun key(r: ProbeRoute) = "${r.routeId}:${r.family}"

    private fun familyOf(f: FamilyPolicy) = if (f == FamilyPolicy.V6_ONLY) Family.V6 else Family.V4
}
