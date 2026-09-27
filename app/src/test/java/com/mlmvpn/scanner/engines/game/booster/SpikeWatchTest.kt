package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.session.SpikeWatch
import com.mlmvpn.scanner.engines.game.booster.session.SpikeWatch.Cause
import com.mlmvpn.scanner.engines.game.booster.session.SpikeWatch.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpikeWatchTest {

    private fun calm(rtt: Double = 80.0, rssi: Int? = -55, rate: Int? = 400) =
        Tick(rtt, 0, 5, 5, 2, rssi, rate, 0)

    private fun warmed(): SpikeWatch = SpikeWatch().apply { repeat(10) { add(calm(80.0 + it % 3)) } }

    @Test
    fun `nothing is a spike before the session has a normal`() {
        val w = SpikeWatch()
        assertNull(w.add(calm(400.0)))
        assertEquals(0, w.spikes)
    }

    @Test
    fun `a jump is blamed on whatever moved with it`() {
        assertEquals(Cause.BACKGROUND, warmed().add(Tick(260.0, 0, 5, 900, 20, -55, 400, 0)))
        assertEquals(Cause.WIFI_SIGNAL, warmed().add(Tick(260.0, 0, 5, 5, 2, -70, 400, 0)))
        assertEquals(Cause.WIFI_RATE, warmed().add(Tick(260.0, 0, 5, 5, 2, -55, 90, 0)))
        assertEquals(Cause.HEAT, warmed().add(Tick(260.0, 0, 5, 5, 2, -55, 400, 3)))
        assertEquals(Cause.LINE, warmed().add(Tick(260.0, 0, 5, 5, 2, -55, 400, 0)))
    }

    @Test
    fun `losing most of a burst is a spike too, and small wobbles are not`() {
        val w = warmed()
        assertEquals(Cause.LINE, w.add(Tick(null, 4, 5, 5, 2, -55, 400, 0)))
        assertNull(w.add(calm(110.0)))   // above normal, not a spike
    }

    @Test
    fun `the session's main cause needs a clear majority`() {
        val w = warmed()
        repeat(3) { w.add(Tick(300.0, 0, 5, 900, 30, -55, 400, 0)) }
        w.add(Tick(300.0, 0, 5, 5, 2, -55, 400, 0))
        assertEquals(4, w.spikes)
        assertEquals(Cause.BACKGROUND, w.mainCause())
        val few = warmed()
        few.add(Tick(300.0, 0, 5, 900, 30, -55, 400, 0))
        assertNull(few.mainCause())
    }
}
