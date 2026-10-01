package com.mlmvpn.scanner.engines.flux.core.score

import com.mlmvpn.scanner.engines.flux.core.memory.FluxMetrics
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * One number per candidate, higher is better. Not latency alone: a 40 ms route that fails every
 * third connect loses to a steady 70 ms one.
 *
 * The weights are a starting point chosen to rank like a person would on the cases in the tests;
 * they are meant to be tuned against field measurements, not trusted as final.
 */
object FluxScorer {

    data class Weights(
        val rtt: Double = 0.35,
        val success: Double = 0.25,
        val throughput: Double = 0.15,
        val stability: Double = 0.10,
        val handshake: Double = 0.05,
        val fresh: Double = 0.10,
    )

    val DEFAULT = Weights()

    /** A switch has to beat the current route by this fraction of its score (hysteresis). */
    const val SWITCH_MARGIN = 0.15
    private const val MIN_SWITCH_GAIN = 0.05

    /**
     * [liveRttMs]: what the race just measured (null when scoring from memory alone).
     */
    fun score(c: FluxCandidate, m: FluxMetrics?, liveRttMs: Long?, now: Long, w: Weights = DEFAULT): Double {
        val rtt = liveRttMs ?: m?.p50
        val rttTerm = rtt?.let { 1.0 - min(it, 2_000L) / 2_000.0 } ?: 0.3
        val successTerm = m?.successEwma ?: 0.5
        val tputTerm = m?.kbps?.let { min(1.0, ln(1 + it / 1000.0) / ln(1 + 100.0)) } ?: 0.3
        val stabilityTerm = m?.let { mm ->
            val p50 = mm.p50; val p95 = mm.p95
            if (p50 == null || p95 == null || mm.rtts.size < 3) 0.5 else max(0.0, 1.0 - (p95 - p50).toDouble() / max(p50, 50L) / 3.0)
        } ?: 0.5
        val hsTerm = m?.handshakeMs?.let { 1.0 - min(it, 1_500L) / 1_500.0 } ?: 0.5
        val freshTerm = m?.lastOkAt?.takeIf { it > 0 }?.let { 1.0 - min(1.0, (now - it) / (7 * 86_400_000.0)) } ?: 0.0
        var s = w.rtt * rttTerm + w.success * successTerm + w.throughput * tputTerm +
            w.stability * stabilityTerm + w.handshake * hsTerm + w.fresh * freshTerm
        // Each failure in a row costs, so a flapping route sinks before the breaker opens.
        s -= 0.08 * (m?.consecutiveFailures ?: 0)
        // A fragment profile costs a little latency and CPU: only worth it when it wins clearly.
        if (c.fragment != FragmentProfile.OFF) s -= 0.03
        // Self-signed: kept for when nothing verified works, never preferred.
        if (c.node.insecure) s -= 0.25
        return s
    }

    /** Whether [challenger] should replace [current]: better by the hysteresis margin, not by noise. */
    fun shouldSwitch(current: Double, challenger: Double): Boolean =
        challenger - current > max(MIN_SWITCH_GAIN, current * SWITCH_MARGIN)
}
