package com.mlmvpn.scanner.engines.mae.probe

import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ProbeSpec
import com.mlmvpn.scanner.engines.mae.route.RouteKind

/** A route as the prober reaches it. [socksPort] null means "the plain network, no core". */
data class ProbeRoute(
    val routeId: String,
    val kind: RouteKind,
    val family: FamilyPolicy = FamilyPolicy.BOTH,
    val socksPort: Int? = null,
    /** The route cannot reach Cloudflare-hosted destinations (see [com.mlmvpn.scanner.engines.mae.route.Capabilities]). */
    val limited: Boolean = false,
)

/** What an exit looks like from outside, seen through the route itself. */
data class EgressEcho(val ip: String, val isV6: Boolean, val country: String?)

/**
 * The network-facing half of discovery, behind an interface so the discovery logic runs against a
 * scripted [FakeNet]-style implementation in unit tests.
 */
interface Prober {
    /** Stage A: DNS/TCP/TLS/small-GET of one probe spec through one route. */
    suspend fun observe(route: ProbeRoute, spec: ProbeSpec): Observation
    /** Stage B: a few tiny requests; median ms, or null when they failed. */
    suspend fun rtt(route: ProbeRoute, spec: ProbeSpec, samples: Int): Long?
    /** Stage C: download [bytes] through the route; bytes per second, or null. */
    suspend fun throughput(route: ProbeRoute, bytes: Int): Double?
    /** The exit's public address and country, from two independent echo services. */
    suspend fun egress(route: ProbeRoute): EgressEcho?
}
