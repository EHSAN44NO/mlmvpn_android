package com.mlmvpn.scanner.engines.mae.route

import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.probe.Observation
import com.mlmvpn.scanner.engines.mae.probe.Step

/**
 * Per-route health with exponential backoff, so a dead route is not re-tried for every service
 * and every flow (no retry storms, no quota burned on a Worker that is down).
 *
 * CLOSED (healthy) -> after [threshold] consecutive failures OPEN for a backoff that doubles each
 * time it re-opens, capped at [maxBackoffMs] -> after the backoff a probe is allowed again; success
 * closes it, failure re-opens with a longer backoff. MAE's discovery runs one service at a time, so
 * a single recovery probe needs no in-flight flag (the old one leaked: a route that was allowed but
 * then not probed stayed "being probed" -- and so excluded -- until the app restarted).
 *
 * The caller keys it per route AND network ([key]): a route that is dead on one operator says
 * nothing about the next.
 *
 * What counts as a failure is decided by [RouteHealth], never by whether one service answered:
 * direct "fails" for every filtered app, and three filtered apps in a row used to mark direct
 * unavailable -- taking ChatGPT's and Claude's proven direct IPv4 route away with it.
 */
class CircuitBreaker(
    private val threshold: Int = 3,
    private val baseBackoffMs: Long = 30_000,
    private val maxBackoffMs: Long = 30 * 60_000,
    /** A route seen carrying anything this recently is not declared dead by one bad service. */
    private val aliveGraceMs: Long = 10 * 60_000,
) {
    private data class S(
        val consecutive: Int = 0,
        val openUntil: Long = 0,
        val opens: Int = 0,
        val everOk: Boolean = false,
        val aliveAt: Long = 0,
    )

    private val states = HashMap<String, S>()

    @Synchronized
    fun allow(id: String, now: Long): Boolean {
        val s = states[id] ?: return true
        return s.openUntil == 0L || now >= s.openUntil
    }

    @Synchronized
    fun success(id: String, now: Long = 0L) {
        states[id] = S(everOk = true, aliveAt = maxOf(now, states[id]?.aliveAt ?: 0L))
    }

    @Synchronized
    fun failure(id: String, now: Long) {
        val s = states[id] ?: S()
        val c = s.consecutive + 1
        states[id] = if (c >= threshold || s.openUntil != 0L) {
            val opens = s.opens + 1
            val backoff = (baseBackoffMs shl (opens - 1).coerceAtMost(10)).coerceAtMost(maxBackoffMs)
            s.copy(consecutive = c, openUntil = now + backoff, opens = opens)
        } else {
            s.copy(consecutive = c)
        }
    }

    /**
     * One discovery's verdict on a route. DEAD counts as a failure only when the route has not been
     * seen alive recently (for any service): a bypass that carried YouTube a minute ago is not dead
     * because one site was unreachable through it.
     */
    @Synchronized
    fun observe(id: String, verdict: RouteHealth.Verdict, now: Long) {
        when (verdict) {
            RouteHealth.Verdict.ALIVE -> success(id, now)
            RouteHealth.Verdict.DEAD -> if (now - (states[id]?.aliveAt ?: 0L) > aliveGraceMs) failure(id, now)
            RouteHealth.Verdict.UNKNOWN -> Unit
        }
    }

    @Synchronized
    fun health(id: String, now: Long): Health {
        val s = states[id] ?: return Health.UNKNOWN
        return when {
            s.openUntil != 0L && now < s.openUntil -> Health.UNAVAILABLE
            s.consecutive > 0 -> Health.DEGRADED
            s.everOk -> Health.HEALTHY
            else -> Health.UNKNOWN
        }
    }

    companion object {
        /** Breaker key for a route on one network. */
        fun key(routeId: String, net: String) = "$routeId@$net"
    }
}

/**
 * Whether a route itself carried anything -- a transport-level question, deliberately not "did
 * this service answer". Pure.
 */
object RouteHealth {
    enum class Verdict { ALIVE, DEAD, UNKNOWN }

    fun verdict(kind: RouteKind, obs: List<Observation>): Verdict {
        if (obs.isEmpty()) return Verdict.UNKNOWN
        val alive = when (kind) {
            // A TCP connection completed: the path is up, whatever DPI then did to the handshake.
            RouteKind.DIRECT -> obs.any { it.tcp == Step.OK }
            // Through the probe core the SOCKS hop always "connects"; only an answer proves the
            // outbound carried something.
            RouteKind.BYPASS -> obs.any { it.answered }
            // The exit echoed its address, or the service answered through it.
            RouteKind.FOREIGN -> obs.any { it.exitAlive == true || it.answered }
        }
        return if (alive) Verdict.ALIVE else Verdict.DEAD
    }

    /** Nothing at all got through any route: the network itself is down, not the routes. */
    fun networkLooksDown(obs: List<Observation>): Boolean =
        obs.isNotEmpty() && obs.none { o -> (o.direct && o.tcp == Step.OK) || o.answered || o.exitAlive == true }
}
