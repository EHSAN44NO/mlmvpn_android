package com.mlmvpn.scanner.engines.mae.model

enum class Family { V4, V6 }

/** How a route's outbound picks the destination's address family. */
enum class FamilyPolicy { V4_ONLY, V6_ONLY, BOTH }

enum class Health { HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }

/**
 * Proof that a route exits abroad for ONE service, on ONE family, on ONE network -- measured on
 * the very transport the traffic uses, never inferred from where a Worker runs.
 */
data class EgressProof(
    val routeId: String,
    val family: Family,
    /** ISO country seen by two independent IP-echo endpoints, or null when they disagreed. */
    val country: String?,
    /** The service's own acceptance probe passed through this route and family. */
    val serviceAccepted: Boolean,
    val reason: String = "",
    val at: Long = 0L,
)

/** Rolling numbers for one route × service × network. EWMA so old samples fade. */
data class RouteMetrics(
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val rttMs: Double? = null,
    /** Bytes per second, from stage-C samples and passive Xray counters. */
    val throughputBps: Double? = null,
    val lastOkAt: Long = 0L,
    val lastTestedAt: Long = 0L,
) {
    val successRate: Double
        get() = if (successes + failures == 0) 0.5 else successes.toDouble() / (successes + failures)

    fun record(ok: Boolean, rtt: Double?, now: Long): RouteMetrics = copy(
        successes = successes + if (ok) 1 else 0,
        failures = failures + if (ok) 0 else 1,
        consecutiveFailures = if (ok) 0 else consecutiveFailures + 1,
        rttMs = if (ok && rtt != null) ewma(rttMs, rtt) else rttMs,
        lastOkAt = if (ok) now else lastOkAt,
        lastTestedAt = now,
    )

    fun withThroughput(bps: Double): RouteMetrics = copy(throughputBps = ewma(throughputBps, bps))

    private fun ewma(old: Double?, new: Double) = if (old == null) new else old * 0.7 + new * 0.3
}

/** Where a service's foreign exit stands on one network. Honest about NONE_FOUND. */
sealed class ForeignEgressStatus {
    object Untested : ForeignEgressStatus()
    data class Proven(val routeId: String, val family: FamilyPolicy) : ForeignEgressStatus()
    /** Every provider tried was rejected; [rejected] says which and why, for diagnostics. */
    data class NoneFound(val rejected: Map<String, String>) : ForeignEgressStatus()
}

/** The chosen route for one service on one network, with why. */
data class ServicePolicy(
    val serviceId: String,
    val netKey: String,
    val routeId: String,
    val family: FamilyPolicy = FamilyPolicy.BOTH,
    val confidence: Double = 0.5,
    val why: String = "",
    val decidedAt: Long = 0L,
    /** User pinned a route: Auto never moves it. */
    val pinned: Boolean = false,
)

/**
 * How the tunnel's own DNS (the base's `no-filter-dns` DoH server) gets out on this network:
 * which DoH URL, through which outbound. [url] null = the base config's own choice, untouched.
 * Learned per network, because DoH that works on one operator is filtered on the next (MCI,
 * 2026-09-29: only `8.8.8.8` by IP answered; the base's DoH-through-fragment timed out).
 */
data class DnsPath(val url: String?, val via: String, val at: Long = 0L) {
    companion object {
        const val BASE_VIA = "tcp-fragment-tls"
        val BASE = DnsPath(null, BASE_VIA)
    }
}
