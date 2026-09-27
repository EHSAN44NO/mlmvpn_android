package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.decide.PathMeasurement
import com.mlmvpn.scanner.engines.game.booster.decide.ProbeMethod
import com.mlmvpn.scanner.engines.game.booster.decide.RouteDecider
import com.mlmvpn.scanner.engines.game.booster.decide.Verdict
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.probe.RttSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDeciderTest {

    /** A train of [n] answers around [base] ms with [wobble] alternating spread, out of [sent]. */
    private fun stats(base: Double, wobble: Double, n: Int = 60, sent: Int = 60): PathStats =
        ProbeStats.stats((0 until n).map { RttSample(it, base + if (it % 2 == 0) 0.0 else wobble) }, sent)

    private fun path(route: RouteKind, s: PathStats, slices: List<Int?> = emptyList()) =
        PathMeasurement(route, ProbeMethod.ECHO, s, slices)

    @Test
    fun `a clearly better and consistent tunnel wins`() {
        val direct = path(RouteKind.DIRECT, stats(90.0, 60.0), listOf(120, 118, 125, 119))
        val warp = path(RouteKind.WARP_MASQUE_H3, stats(70.0, 4.0), listOf(72, 71, 73, 70))
        val v = RouteDecider.decide(direct, listOf(warp))
        assertTrue(v is Verdict.Switch)
        v as Verdict.Switch
        assertTrue(v.claims.steadier)
    }

    @Test
    fun `a small win stays direct`() {
        val direct = path(RouteKind.DIRECT, stats(80.0, 4.0))
        val warp = path(RouteKind.WARP_MASQUE_H3, stats(78.0, 3.0))
        assertTrue(RouteDecider.decide(direct, listOf(warp)) is Verdict.StayDirect)
    }

    @Test
    fun `stability bought with a much higher typical ping is refused`() {
        val direct = path(RouteKind.DIRECT, stats(60.0, 70.0))
        val warp = path(RouteKind.WARP_MASQUE_H3, stats(140.0, 1.0))
        assertTrue(RouteDecider.decide(direct, listOf(warp)) is Verdict.StayDirect)
    }

    @Test
    fun `winning one lucky slice is not enough`() {
        val direct = path(RouteKind.DIRECT, stats(90.0, 60.0), listOf(80, 81, 79, 150))
        val warp = path(RouteKind.WARP_MASQUE_H3, stats(70.0, 4.0), listOf(90, 92, 91, 70))
        assertTrue(RouteDecider.decide(direct, listOf(warp)) is Verdict.StayDirect)
    }

    @Test
    fun `a blocked direct line takes any tunnel that works`() {
        val direct = path(RouteKind.DIRECT, PathStats.empty(60))
        val warp = path(RouteKind.WARP_MASQUE_H3, stats(160.0, 30.0))
        val v = RouteDecider.decide(direct, listOf(warp))
        assertTrue(v is Verdict.MustTunnel)
    }

    @Test
    fun `nothing answering anywhere says so`() {
        val direct = path(RouteKind.DIRECT, PathStats.empty(60))
        assertTrue(RouteDecider.decide(direct, emptyList()) === Verdict.NothingWorks)
    }

    @Test
    fun `claims need a real margin`() {
        val before = stats(100.0, 2.0)
        assertFalse(RouteDecider.claims(before, stats(97.0, 2.0)).lowerPing)   // 3 ms: noise
        assertTrue(RouteDecider.claims(before, stats(90.0, 2.0)).lowerPing)    // 10 ms
    }

    @Test
    fun `loss is never claimed from too few probes`() {
        val before = ProbeStats.stats((0 until 8).map { RttSample(it, 50.0) }, 10)
        val after = ProbeStats.stats((0 until 10).map { RttSample(it, 50.0) }, 10)
        val c = RouteDecider.claims(before, after)
        assertFalse(c.lessLoss)
        assertEquals(null, c.lossDelta)
    }
}
