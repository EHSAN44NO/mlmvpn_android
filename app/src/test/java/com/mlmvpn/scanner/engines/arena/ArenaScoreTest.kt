package com.mlmvpn.scanner.engines.arena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaScoreTest {

    private fun lat(vararg ms: Long) = ms.map { Sample(1, it) }

    private fun e(id: String, latency: List<Sample> = emptyList(), reach: Long? = null, reachTried: Boolean = false,
                  mbps: Double? = null, stab: List<Sample> = emptyList(), fail: ArenaFail? = null) =
        Entry(id, id, uri = if (fail == null) "vless://x@h:443" else null, fail = fail, latency = latency,
            reachMs = reach, reachTried = reachTried, mbps = mbps, stability = stab)

    @Test fun ratioScoring_halfAsFastScoresHalf() {
        val b = ArenaScore.score(listOf(e("A", lat(100, 100, 100)), e("B", lat(200, 200, 200))))
        assertEquals(listOf("A", "B"), b.standings.map { it.panelId })
        assertEquals(100.0, b.standings[0].parts[ArenaScore.LATENCY]!!, 0.01)
        assertEquals(50.0, b.standings[1].parts[ArenaScore.LATENCY]!!, 0.01)
    }

    @Test fun onlyRoundsThatRanCount_andWeightsRenormalise() {
        val b = ArenaScore.score(listOf(e("A", lat(100), reach = 300, reachTried = true), e("B", lat(100), reachTried = true)))
        assertEquals(setOf(ArenaScore.LATENCY, ArenaScore.REACH), b.weights.keys)
        assertEquals(1.0, b.weights.values.sum(), 1e-9)
        // B never reached a Cloudflare page: it scores 0 for that round, A wins.
        assertEquals("A", b.winner)
    }

    @Test fun failedCompetitorsAreNotScored() {
        val b = ArenaScore.score(listOf(e("A", lat(100)), e("B", fail = ArenaFail.UNREACHABLE)))
        assertEquals(listOf("A"), b.standings.map { it.panelId })
        // One qualified competitor: no category claims anything.
        assertTrue(b.categories.isEmpty())
    }

    @Test fun categoriesNeedTwoMeasurements() {
        val b = ArenaScore.score(listOf(e("A", lat(100), mbps = 10.0), e("B", lat(150))))
        assertEquals("A", b.categories[Category.LATENCY])
        assertNull(b.categories[Category.SPEED])   // only A has a speed
        assertEquals("A", b.categories[Category.OVERALL])
    }

    @Test fun stabilityRewardsAnswersAndSteadiness() {
        val steady = lat(100, 105, 100, 102, 101, 100)
        val shaky = listOf(Sample(1, 100), Sample(1, null), Sample(1, 900), Sample(1, 120), Sample(1, null), Sample(1, 400))
        val b = ArenaScore.score(listOf(e("A", lat(100), stab = steady), e("B", lat(100), stab = shaky)))
        assertEquals("A", b.categories[Category.STABLE])
    }

    @Test fun medianAndJitter() {
        val x = e("A", lat(300, 100, 200), stab = lat(100, 110, 120, 130, 140, 400))
        assertEquals(200L, x.latencyMedian)
        assertEquals(100L, x.latencyMin)
        assertTrue(x.jitter!! > 0)
        assertFalse(e("B", fail = ArenaFail.INVALID).qualified)
    }

    @Test fun historyTrimsToNewestFifty() {
        val list = (1..60).map { ArenaSession("s$it", it.toLong(), it.toLong(), ArenaMode.QUICK, ArenaNetwork("Wi-Fi", ""), emptyList()) }
        val t = ArenaStore.trim(list)
        assertEquals(ArenaStore.MAX, t.size)
        assertEquals("s60", t.first().id)
    }

    @Test fun sessionRoundTripsThroughJson() {
        val s = ArenaSession("id", 1, 2, ArenaMode.FULL, ArenaNetwork("Mobile", "MCI"),
            listOf(e("A", lat(100), reach = 50, reachTried = true, mbps = 8.5, stab = lat(100, 101)), e("B", fail = ArenaFail.TLS_REFUSED)))
        val back = ArenaSession.from(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s.entries.map { it.fail }, back.entries.map { it.fail })
        assertEquals(8.5, back.entries[0].mbps!!, 1e-9)
        assertEquals("MCI", back.network.operator)
    }
}
