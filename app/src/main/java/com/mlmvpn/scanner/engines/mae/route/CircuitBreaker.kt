package com.mlmvpn.scanner.engines.mae.route

import com.mlmvpn.scanner.engines.mae.model.Health

/**
 * Per-provider health with exponential backoff, so a dead provider is not re-tried for every
 * service and every flow (no retry storms, no quota burned on a Worker that is down).
 *
 * CLOSED (healthy) -> after [threshold] consecutive failures OPEN for a backoff that doubles each
 * time it re-opens, capped at [maxBackoffMs] -> HALF_OPEN: exactly one recovery probe is allowed;
 * success closes it, failure re-opens with a longer backoff.
 */
class CircuitBreaker(
    private val threshold: Int = 3,
    private val baseBackoffMs: Long = 30_000,
    private val maxBackoffMs: Long = 30 * 60_000,
) {
    private data class S(
        val consecutive: Int = 0,
        val openUntil: Long = 0,
        val opens: Int = 0,
        val probing: Boolean = false,
        val everOk: Boolean = false,
    )

    private val states = HashMap<String, S>()

    @Synchronized
    fun allow(id: String, now: Long): Boolean {
        val s = states[id] ?: return true
        if (s.openUntil == 0L) return true
        if (now < s.openUntil) return false
        if (s.probing) return false // one recovery probe at a time
        states[id] = s.copy(probing = true)
        return true
    }

    @Synchronized
    fun success(id: String) {
        states[id] = S(everOk = true)
    }

    @Synchronized
    fun failure(id: String, now: Long) {
        val s = states[id] ?: S()
        val c = s.consecutive + 1
        states[id] = if (c >= threshold || s.probing) {
            val opens = s.opens + 1
            val backoff = (baseBackoffMs shl (opens - 1).coerceAtMost(10)).coerceAtMost(maxBackoffMs)
            s.copy(consecutive = c, openUntil = now + backoff, opens = opens, probing = false)
        } else {
            s.copy(consecutive = c)
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
}
