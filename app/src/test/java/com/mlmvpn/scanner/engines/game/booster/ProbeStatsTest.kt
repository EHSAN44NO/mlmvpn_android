package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.probe.RttSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The statistics must agree with the desktop booster (`G:\ip scanner\game\probe.js`) to the unit,
 * or the two apps would give different verdicts for the same line. Every expected value below was
 * produced by running probe.js's own `stats()` and `score()` on the same input (2026-09-24).
 */
class ProbeStatsTest {

    private fun seqd(vararg rtts: Double) = rtts.mapIndexed { i, r -> RttSample(i, r) }

    private fun assertStats(
        s: PathStats, n: Int, sent: Int, loss: Double, min: Int, p50: Int, p95: Int, p99: Int, max: Int,
        jitter: Double, spread: Int, spikes: Int, reorder: Int, spikePct: Double, score: Int,
    ) {
        assertEquals(n, s.n); assertEquals(sent, s.sent); assertEquals(loss, s.loss, 1e-9)
        assertEquals(min, s.min); assertEquals(p50, s.p50); assertEquals(p95, s.p95)
        assertEquals(p99, s.p99); assertEquals(max, s.max); assertEquals(jitter, s.jitter!!, 1e-9)
        assertEquals(spread, s.spread); assertEquals(spikes, s.spikes); assertEquals(reorder, s.reorder)
        assertEquals(spikePct, s.spikePct, 1e-9); assertEquals(score, ProbeStats.score(s))
    }

    @Test
    fun `steady line matches probe_js`() {
        val s = ProbeStats.stats(seqd(80.0, 81.0, 79.0, 82.0, 80.0, 83.0, 80.0, 81.0, 80.0, 79.0), 10)
        assertStats(s, 10, 10, 0.0, 79, 80, 83, 83, 83, 0.8, 4, 0, 0, 0.0, 90)
    }

    @Test
    fun `spiky line with loss matches probe_js`() {
        val s = ProbeStats.stats(
            seqd(60.0, 62.0, 61.0, 190.0, 63.0, 60.0, 240.0, 61.0, 62.0, 60.0, 61.0, 300.0), 14)
        assertStats(s, 12, 14, 14.29, 60, 62, 300, 300, 300, 42.0, 240, 3, 0, 25.0, 0)
    }

    @Test
    fun `reordered answers match probe_js`() {
        val samples = listOf(
            RttSample(0, 50.0), RttSample(2, 52.0), RttSample(1, 70.0),
            RttSample(3, 51.0), RttSample(5, 49.0), RttSample(4, 88.0),
        )
        val s = ProbeStats.stats(samples, 6)
        assertStats(s, 6, 6, 0.0, 49, 52, 88, 88, 88, 6.6, 39, 0, 2, 0.0, 74)
    }

    @Test
    fun `fractional rtts round the way JavaScript does`() {
        val s = ProbeStats.stats(seqd(45.4, 45.6, 47.5, 44.49, 120.5), 8)
        assertStats(s, 5, 8, 37.5, 44, 46, 121, 121, 121, 5.0, 76, 1, 0, 20.0, 14)
    }

    @Test
    fun `nothing answered is null, never zero`() {
        val s = ProbeStats.stats(emptyList(), 20)
        assertFalse(s.answered)
        assertEquals(100.0, s.loss, 1e-9)
        assertNull(s.p50)
        assertNull(s.jitter)
        assertEquals(0, ProbeStats.score(s))
    }

    @Test
    fun `loss is only meaningful from fifty probes`() {
        assertFalse(ProbeStats.stats(seqd(50.0), 10).lossIsMeaningful)
        assertTrue(ProbeStats.stats(seqd(50.0), 50).lossIsMeaningful)
    }

    @Test
    fun `slice medians follow send order and leave empty slices null`() {
        val samples = listOf(RttSample(0, 10.0), RttSample(1, 30.0), RttSample(2, 20.0), RttSample(6, 99.0))
        assertEquals(listOf(20, null, 99), ProbeStats.sliceMedians(samples, listOf(3, 6, 9)))
    }
}
