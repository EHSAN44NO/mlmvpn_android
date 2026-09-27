package com.mlmvpn.core.aether

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The game booster starts Aether with `optionsOf(config)` to measure it, and a WARP route later
 * hands the tunnel `buildConfig(thoseOptions)`. The tunnel adopts the running engine only if the
 * options it parses back compare EQUAL -- if any field is lost or defaulted differently on the
 * way through the string, every WARP boost silently rescans for a gateway it already had.
 */
class AetherGameHandoffTest {

    @Test
    fun gameOptionsSurviveTheConfigString() {
        for (transport in listOf("h3", "h2")) {
            for (protocol in AetherProtocol.values()) {
                val measured = AetherTunEngine.optionsOf(
                    AetherTunEngine.buildGameConfig(protocol, AetherScan.TURBO, transport))
                val tunnel = AetherTunEngine.optionsOf(AetherTunEngine.buildConfig(measured))
                assertEquals("$protocol/$transport", measured, tunnel)
            }
        }
    }

    @Test
    fun gameConfigKeepsItsLatencyTuning() {
        val o = AetherTunEngine.optionsOf(AetherTunEngine.buildGameConfig(transport = "h3"))
        assertEquals("h3", o.transport)
        assertEquals("light", o.noize)
        assertEquals(5, o.keepalive)
        assertEquals(AetherIp.V4, o.ipFamily)
        assertEquals(AetherScan.TURBO, o.scan)
    }
}
