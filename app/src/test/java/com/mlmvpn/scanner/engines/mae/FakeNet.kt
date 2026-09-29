package com.mlmvpn.scanner.engines.mae

import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ProbeSpec
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.model.ServiceHints
import com.mlmvpn.scanner.engines.mae.probe.DnsEvidence
import com.mlmvpn.scanner.engines.mae.probe.EgressEcho
import com.mlmvpn.scanner.engines.mae.probe.HttpEvidence
import com.mlmvpn.scanner.engines.mae.probe.Observation
import com.mlmvpn.scanner.engines.mae.probe.ProbeRoute
import com.mlmvpn.scanner.engines.mae.probe.Prober
import com.mlmvpn.scanner.engines.mae.probe.Step
import com.mlmvpn.scanner.engines.mae.route.RouteKind

/**
 * A scripted network for MAE tests. Each route (and family) is told how the service behaves
 * through it; unscripted routes time out. Records what was spent, so budget rules can be checked.
 */
class FakeNet : Prober {
    enum class Behave { OK, GEO_403_SIGNATURE, BARE_403, TLS_RESET, TCP_TIMEOUT, SINKHOLE, CERT_MISMATCH, SERVER_500 }

    data class Script(val behave: Behave, val rttMs: Long = 100, val bps: Double = 1_000_000.0)

    private val scripts = HashMap<String, Script>()
    private val echoes = HashMap<String, EgressEcho?>()
    val observed = mutableListOf<String>()
    val throughputAsked = mutableListOf<String>()
    val echoAsked = mutableListOf<String>()

    private fun k(routeId: String, f: FamilyPolicy) = "$routeId:$f"

    fun route(routeId: String, behave: Behave, rttMs: Long = 100, bps: Double = 1_000_000.0, family: FamilyPolicy = FamilyPolicy.BOTH) = apply {
        scripts[k(routeId, family)] = Script(behave, rttMs, bps)
    }

    fun echo(routeId: String, family: FamilyPolicy, country: String?, v6: Boolean = family == FamilyPolicy.V6_ONLY) = apply {
        echoes[k(routeId, family)] = EgressEcho(if (v6) "2001:db8::1" else "203.0.113.9", v6, country)
    }

    private fun script(r: ProbeRoute) = scripts[k(r.routeId, r.family)] ?: scripts[k(r.routeId, FamilyPolicy.BOTH)]

    override suspend fun observe(route: ProbeRoute, spec: ProbeSpec): Observation {
        observed += k(route.routeId, route.family)
        val foreign = route.kind == RouteKind.FOREIGN
        val direct = route.kind == RouteKind.DIRECT
        val s = script(route) ?: return Observation(route.routeId, foreign, direct, tcp = Step.TIMEOUT)
        val dns = if (direct) DnsEvidence(listOf(if (s.behave == Behave.SINKHOLE) "10.10.34.35" else "142.250.1.1"), listOf("142.250.1.1")) else null
        fun http(status: Int, geo: Boolean, ok: Boolean) =
            Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.OK, tls = Step.OK, http = HttpEvidence(status, geo, ok), rttMs = s.rttMs)
        return when (s.behave) {
            Behave.OK -> http(200, false, true)
            Behave.GEO_403_SIGNATURE -> http(403, true, false)
            Behave.BARE_403 -> http(403, false, false)
            Behave.SERVER_500 -> http(500, false, false)
            Behave.TLS_RESET -> Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.OK, tls = Step.RESET)
            Behave.CERT_MISMATCH -> Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.OK, tls = Step.CERT_MISMATCH)
            Behave.TCP_TIMEOUT -> Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.TIMEOUT)
            Behave.SINKHOLE -> Observation(route.routeId, foreign, direct, dns = dns, tcp = Step.OK, tls = Step.CERT_MISMATCH)
        }
    }

    override suspend fun rtt(route: ProbeRoute, spec: ProbeSpec, samples: Int): Long? =
        script(route)?.takeIf { it.behave == Behave.OK || it.behave == Behave.BARE_403 }?.rttMs

    override suspend fun throughput(route: ProbeRoute, bytes: Int): Double? {
        throughputAsked += k(route.routeId, route.family)
        return script(route)?.bps
    }

    override suspend fun egress(route: ProbeRoute): EgressEcho? {
        echoAsked += k(route.routeId, route.family)
        return echoes[k(route.routeId, route.family)]
    }

    companion object {
        fun service(id: String, hints: ServiceHints = ServiceHints(), domains: List<String> = listOf("$id.com")) =
            ServiceDef(id, id, id, domains = domains, probes = listOf(ProbeSpec("https://$id.com/")), hints = hints)

        /** The five route shapes MAE has, as the engine would build them for a probe run. */
        fun routes(withWorker: Boolean = true) = buildList {
            add(ProbeRoute("direct", RouteKind.DIRECT))
            add(ProbeRoute("serverless", RouteKind.BYPASS, socksPort = 1))
            add(ProbeRoute("fragment", RouteKind.BYPASS, socksPort = 2))
            if (withWorker) {
                add(ProbeRoute("worker", RouteKind.FOREIGN, FamilyPolicy.V4_ONLY, 3))
                add(ProbeRoute("worker", RouteKind.FOREIGN, FamilyPolicy.V6_ONLY, 4))
                add(ProbeRoute("worker", RouteKind.FOREIGN, FamilyPolicy.BOTH, 5))
            }
        }
    }
}
