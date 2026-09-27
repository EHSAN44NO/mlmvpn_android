package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.decide.ProbeMethod
import com.mlmvpn.scanner.engines.game.booster.decide.RegionAdvisor
import com.mlmvpn.scanner.engines.game.booster.decide.RegionAdvisor.Sample
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.probe.RttSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionAdvisorTest {

    private fun train(ms: Double, n: Int = 24, sent: Int = 24): PathStats =
        ProbeStats.stats((0 until n).map { RttSample(it, ms + (it % 3)) }, sent)

    @Test
    fun `a clearly faster region is recommended, with the gain`() {
        val a = RegionAdvisor.advise("ME", listOf(
            Sample("ME", train(140.0), train(150.0)),
            Sample("EU", train(95.0), train(100.0)),
        ))!!
        assertTrue(a.switch)
        assertEquals("EU", a.best)
        assertEquals(ProbeMethod.ECHO, a.method)
        assertEquals(45, a.gainMs)
    }

    @Test
    fun `a small difference keeps the player where they are`() {
        val a = RegionAdvisor.advise("EU", listOf(
            Sample("EU", train(100.0), null),
            Sample("AS", train(94.0), null),   // 6 ms: under the 10 ms / 10 % bar
        ))!!
        assertFalse(a.switch)
        assertEquals("EU", a.best)
    }

    @Test
    fun `a dead region sends the player to the best live one`() {
        // Bahrain since March 2026: nothing answers.
        val a = RegionAdvisor.advise("ME", listOf(
            Sample("ME", PathStats.empty(24), PathStats.empty(4)),
            Sample("EU", train(110.0), train(115.0)),
            Sample("AS", train(190.0), train(200.0)),
        ))!!
        assertTrue(a.switch)
        assertFalse(a.currentAnswered)
        assertEquals("EU", a.best)
        assertNull(a.gainMs)
    }

    @Test
    fun `regions are compared on the same method`() {
        // AS answers only TCP, so everyone is compared on TCP -- not EU's echo against AS's TCP.
        val a = RegionAdvisor.advise("EU", listOf(
            Sample("EU", train(60.0), train(130.0)),
            Sample("AS", null, train(100.0)),
        ))!!
        assertEquals(ProbeMethod.TCP, a.method)
        assertEquals("AS", a.best)
        assertEquals(30, a.gainMs)
    }

    @Test
    fun `a trickle of echo answers is not an answer`() {
        val trickle = ProbeStats.stats(listOf(RttSample(0, 50.0)), 24)
        val s = Sample("EU", trickle, null)
        assertFalse(s.answered)
        assertNull(RegionAdvisor.advise("EU", listOf(s)))
    }

    @Test
    fun `more loss is not traded for a little ping`() {
        val lossy = ProbeStats.stats((0 until 50).map { RttSample(it, 70.0) }, 60)  // ~17 % loss
        val clean = ProbeStats.stats((0 until 60).map { RttSample(it, 100.0) }, 60)
        val a = RegionAdvisor.advise("EU", listOf(Sample("EU", clean, null), Sample("AS", lossy, null)))!!
        assertFalse(a.switch)
    }
}
