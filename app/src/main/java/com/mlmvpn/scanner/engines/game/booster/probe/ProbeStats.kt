package com.mlmvpn.scanner.engines.game.booster.probe

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One answered probe: its place in the send order, and how long the answer took.
 *
 * [txRelMs] is when it was SENT, relative to the start of its train -- what warm-up trimming
 * and slicing are decided on, since a reply's arrival time says nothing about which part of
 * the train it belongs to.
 */
data class RttSample(val seq: Int, val rttMs: Double, val txRelMs: Double = 0.0)

/**
 * The numbers that decide whether a path is playable.
 *
 * Nullable fields are null when nothing answered, never zero: a 0 ms p50 would read as the best
 * line anyone has ever had.
 */
data class PathStats(
    val n: Int,
    val sent: Int,
    /** Percent of [sent] that never came back, two decimals. */
    val loss: Double,
    val min: Int?,
    val p50: Int?,
    val p95: Int?,
    val p99: Int?,
    val max: Int?,
    /** RFC 3550 interarrival jitter, one decimal. */
    val jitter: Double?,
    /** p95 − min: what the player actually feels. */
    val spread: Int?,
    val spikes: Int,
    val spikePct: Double,
    val reorder: Int,
) {
    val answered: Boolean get() = n > 0

    /**
     * Whether a loss figure means anything yet.
     *
     * With ten probes loss moves in 10-point steps, so one late packet reads as "10 % loss" and
     * condemns a perfectly good line. Below fifty samples a loss figure is shown as approximate
     * and never used to make a claim.
     */
    val lossIsMeaningful: Boolean get() = sent >= MIN_SAMPLES_FOR_LOSS

    companion object {
        const val MIN_SAMPLES_FOR_LOSS = 50

        fun empty(sent: Int) = PathStats(
            n = 0, sent = sent, loss = if (sent > 0) 100.0 else 0.0,
            min = null, p50 = null, p95 = null, p99 = null, max = null,
            jitter = null, spread = null, spikes = 0, spikePct = 0.0, reorder = 0,
        )
    }
}

/**
 * Game-grade statistics, ported from the desktop booster's `game/probe.js` so both apps say the
 * same thing about the same line.
 *
 * ## Why percentiles and never an average
 *
 * Measured on an Iranian line (desktop, 2026-08-19): five STUN packets said "47–59 ms, great"; a
 * 240-packet train at a game's cadence said p95 = 582 ms with 30 spikes. An average hides the
 * tail, and the tail is what a player feels -- so everything here is order statistics plus the
 * RFC jitter, and the score is built from the spread rather than the mean.
 */
object ProbeStats {

    /**
     * [samples] are the answered probes (any order); [sent] is how many were sent in the same
     * window, so loss is counted from the sender's side rather than inferred from the survivors.
     */
    fun stats(samples: List<RttSample>, sent: Int, spikeOverMs: Double = 50.0): PathStats {
        if (samples.isEmpty()) return PathStats.empty(sent)

        val bySeq = samples.sortedBy { it.seq }
        val sorted = samples.map { it.rttMs }.sorted()
        fun at(p: Double): Double = sorted[min(sorted.size - 1, floor(sorted.size * p).toInt())]

        // RFC 3550 interarrival jitter: J += (|D| - J) / 16, in sequence order.
        var j = 0.0
        for (i in 1 until bySeq.size) j += (abs(bySeq[i].rttMs - bySeq[i - 1].rttMs) - j) / 16.0

        // Reordering: an answer that arrived after a later-sequenced one already had.
        var reorder = 0
        var high = -1
        for (s in samples) {
            if (s.seq < high) reorder++ else high = s.seq
        }

        val minRtt = sorted.first()
        val spikes = sorted.count { it > minRtt + spikeOverMs }
        val lost = max(0, sent - samples.size)

        return PathStats(
            n = samples.size,
            sent = sent,
            loss = if (sent > 0) round2(lost.toDouble() / sent * 100.0) else 0.0,
            min = jsRound(minRtt),
            p50 = jsRound(at(0.5)),
            p95 = jsRound(at(0.95)),
            p99 = jsRound(at(0.99)),
            max = jsRound(sorted.last()),
            jitter = round1(j),
            spread = jsRound(at(0.95) - minRtt),
            spikes = spikes,
            spikePct = round1(spikes.toDouble() / samples.size * 100.0),
            reorder = reorder,
        )
    }

    /**
     * One playability score, 0–100, so paths can be ranked by one number.
     *
     * Deliberately NOT a latency score -- the weights come from what breaks a game: a steady
     * 200 ms is playable, a 90 ms average with 500 ms spikes is not. Same weights as the desktop.
     */
    fun score(s: PathStats?): Int {
        if (s == null || s.n == 0) return 0
        var v = 100.0
        v -= min(35.0, max(0.0, ((s.min ?: 0) - 40).toDouble()) * 0.18)   // distance floor, gentle
        v -= min(30.0, (s.spread ?: 0) * 0.45)                             // the felt jitter
        v -= min(20.0, (s.jitter ?: 0.0) * 0.9)                            // RFC jitter
        v -= min(35.0, s.loss * 12.0)                                      // loss is brutal
        v -= min(20.0, s.spikePct * 0.8)                                   // stalls
        v -= min(5.0, s.reorder * 0.5)
        return max(0, jsRound(v))
    }

    /**
     * The p50 of each consecutive run of [perSlice] samples, in send order -- what "wins most of
     * the slices" is judged on. A slice with no answers is null (it lost).
     */
    fun sliceMedians(samples: List<RttSample>, sliceBounds: List<Int>): List<Int?> {
        val bySeq = samples.sortedBy { it.seq }
        return sliceBounds.indices.map { i ->
            val lo = if (i == 0) 0 else sliceBounds[i - 1]
            val hi = sliceBounds[i]
            val inSlice = bySeq.filter { it.seq in lo until hi }.map { it.rttMs }.sorted()
            if (inSlice.isEmpty()) null
            else jsRound(inSlice[min(inSlice.size - 1, floor(inSlice.size * 0.5).toInt())])
        }
    }

    /** JavaScript's Math.round: half rounds towards +infinity, so the ports agree to the unit. */
    internal fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

    private fun round1(x: Double): Double = floor(x * 10.0 + 0.5) / 10.0
    private fun round2(x: Double): Double = floor(x * 100.0 + 0.5) / 100.0

    /** Kotlin's own rounding, kept for callers that format for display. */
    fun display(x: Double?): String = x?.roundToInt()?.toString() ?: "—"
}
