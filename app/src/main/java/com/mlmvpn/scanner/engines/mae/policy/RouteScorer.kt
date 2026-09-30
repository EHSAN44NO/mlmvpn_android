package com.mlmvpn.scanner.engines.mae.policy

import com.mlmvpn.scanner.engines.mae.model.FailMode
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.model.ObservedRequirements
import com.mlmvpn.scanner.engines.mae.model.RouteMetrics
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** One route as the scorer sees it, for one service on one network. */
data class Candidate(
    val routeId: String,
    val kind: RouteKind,
    val metrics: RouteMetrics = RouteMetrics(),
    val health: Health = Health.UNKNOWN,
    val cost: Double = 0.0,
    val quotaLimited: Boolean = false,
    /** Probed and the service really answered through it (null: never probed). */
    val usable: Boolean? = null,
    /**
     * Families on which THIS service was served through this route. For a foreign route that
     * also means the exit was proven abroad; for a local one, simply that it worked (IPv4 and
     * IPv6 of one network can be different countries to a service).
     */
    val acceptedFamilies: Set<FamilyPolicy> = emptySet(),
)

sealed class Decision {
    data class Use(val routeId: String, val family: FamilyPolicy, val score: Double, val why: String) : Decision()
    data class Block(val why: String) : Decision()
}

/**
 * Picks a route for one service. Requirements filter first (a fast route with an Iranian IP is not
 * a candidate for a geo-restricted service, however fast), then the survivors are scored on more
 * than "it opened": success, throughput, latency, cost, quota and health.
 */
object RouteScorer {
    /** A challenger must beat the current route by this much to replace it (no flapping). */
    const val HYSTERESIS = 0.15

    fun decide(
        req: ObservedRequirements,
        failMode: FailMode,
        heavy: Boolean,
        candidates: List<Candidate>,
        current: String? = null,
        pinned: String? = null,
        /**
         * Where this network sends traffic MAE knows nothing about (Serverless, or direct where
         * Serverless is dead). A fail-open app with no working route goes there, never to block.
         */
        fallbackRoute: String? = null,
    ): Decision {
        if (pinned != null) {
            val c = candidates.firstOrNull { it.routeId == pinned }
            if (c != null) return Decision.Use(pinned, familyFor(c), score(c, heavy), "pinned by user")
        }

        // Nothing probed yet on this network for this app.
        val cold = candidates.none { it.usable != null }
        val alive = candidates.filter { it.health != Health.UNAVAILABLE }
        val valid = alive.filter { c ->
            when {
                req.wantsForeign -> c.kind == RouteKind.FOREIGN && c.acceptedFamilies.isNotEmpty() && c.usable != false
                c.kind == RouteKind.FOREIGN -> c.acceptedFamilies.isNotEmpty() && c.usable != false
                // An app that must not look Iranian does not take an unchecked Iranian route: on a
                // network MAE has not seen yet, "cold" used to make every local route valid, and
                // ChatGPT, Claude and Gemini left from an Iranian address until the check finished.
                failMode == FailMode.CLOSED && cold -> false
                req.wantsBypass && c.kind == RouteKind.DIRECT -> false
                else -> c.usable != false
            }
        }.filter { c ->
            // Local routes need the service to have actually answered through them (on some
            // family), unless nothing has been probed yet (cold start).
            c.kind == RouteKind.FOREIGN || cold ||
                (c.usable == true && (c.acceptedFamilies.isNotEmpty() || candidates.none { it.acceptedFamilies.isNotEmpty() }))
        }

        if (valid.isEmpty()) {
            return if (failMode == FailMode.CLOSED) {
                // An app that must not be seen from an Iranian IP never takes the local fast
                // default: a foreign exit the app has not refused is kept (its proof may just have
                // missed a probe), and only with none is it blocked. Irancell, 2026-09-30: Gemini
                // on the "fast default" said "not available in your country".
                val abroad = alive.filter { it.kind == RouteKind.FOREIGN && it.usable != false }
                    .maxByOrNull { score(it, heavy) }
                if (abroad != null) Decision.Use(abroad.routeId, familyFor(abroad), 0.0, "no proof right now; an app that must not look Iranian keeps a foreign exit")
                else Decision.Block("needs a foreign exit and none is available; blocked rather than leak")
            } else {
                // Nothing proven: the network's own default path -- a plain site slow is better
                // than dead, and a route the breaker has paused is still better than a blackhole
                // (a captive portal or a dead minute used to leave every app blocked).
                val fallback = alive.firstOrNull { it.routeId == fallbackRoute && it.usable != false }
                    ?: alive.firstOrNull { it.kind == RouteKind.BYPASS && it.usable != false }
                    ?: alive.firstOrNull { it.usable != false }
                    ?: candidates.firstOrNull { it.routeId == fallbackRoute }
                    ?: alive.firstOrNull()
                    ?: candidates.firstOrNull()
                if (fallback == null) Decision.Block("no route available")
                else Decision.Use(fallback.routeId, FamilyPolicy.BOTH, 0.0, "no proven route; the network's default path")
            }
        }

        // Unknown foreign need: a local route that works is preferred (keeps Serverless speed);
        // a foreign one only competes on score.
        val scored = valid.map { it to score(it, heavy) }.sortedByDescending { it.second }
        val (best, bestScore) = scored.first()
        val cur = scored.firstOrNull { it.first.routeId == current }
        // An absolute floor on the margin: scores can be small or negative, and a relative 15% of
        // those is no hysteresis at all.
        if (cur != null && cur.first.routeId != best.routeId && bestScore < cur.second + maxOf(MIN_MARGIN, kotlin.math.abs(cur.second) * HYSTERESIS)) {
            return Decision.Use(cur.first.routeId, familyFor(cur.first), cur.second,
                "kept current: challenger ${best.routeId} not clearly better")
        }
        return Decision.Use(best.routeId, familyFor(best), bestScore, explain(best, req))
    }

    /** The smallest score gain that justifies moving an app (see [HYSTERESIS]). */
    const val MIN_MARGIN = 0.05

    fun familyFor(c: Candidate): FamilyPolicy = when {
        c.acceptedFamilies.isEmpty() -> FamilyPolicy.BOTH
        FamilyPolicy.BOTH in c.acceptedFamilies -> FamilyPolicy.BOTH
        FamilyPolicy.V4_ONLY in c.acceptedFamilies && FamilyPolicy.V6_ONLY in c.acceptedFamilies -> FamilyPolicy.BOTH
        FamilyPolicy.V6_ONLY in c.acceptedFamilies -> FamilyPolicy.V6_ONLY
        else -> FamilyPolicy.V4_ONLY
    }

    fun score(c: Candidate, heavy: Boolean): Double {
        val m = c.metrics
        val tput = m.throughputBps?.let { bps ->
            // 10 KB/s .. 10 MB/s on a log scale.
            min(1.0, max(0.0, log10(max(bps, 1.0) / 10_000.0) / 3.0))
        } ?: 0.4
        val rtt = m.rttMs?.let { 1.0 - min(1.0, it / 1500.0) } ?: 0.4
        val quota = if (c.quotaLimited) (if (heavy) 0.35 else 0.1) else 0.0
        val health = when (c.health) { Health.DEGRADED -> 0.1; else -> 0.0 }
        val streak = min(0.3, m.consecutiveFailures * 0.1)
        return 0.35 * m.successRate + (if (heavy) 0.35 else 0.25) * tput + 0.25 * rtt - c.cost * 0.2 - quota - health - streak
    }

    private fun explain(c: Candidate, req: ObservedRequirements): String = buildString {
        append(c.routeId)
        if (req.wantsForeign) append(": service needs a foreign exit; proven for it")
        else if (req.needsForeignGeo.state == Tri.UNKNOWN && c.kind == RouteKind.FOREIGN) append(": best score while geo is unknown")
        else if (req.wantsBypass) append(": direct is filtered; fastest bypass")
        else append(": fastest working route")
        c.metrics.rttMs?.let { append(", ${it.toInt()} ms") }
        c.metrics.throughputBps?.let { append(", ${(it / 125_000).let { mb -> "%.1f".format(mb) }} Mbps") }
    }
}
