package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.memory.NetworkKey
import com.mlmvpn.scanner.engines.game.booster.memory.RouteMemory
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RouteMemoryTest {

    private var clock = 1_790_000_000_000L
    private val mem = RouteMemory { clock }

    private fun entry(route: RouteKind = RouteKind.DIRECT, warpMeasured: Boolean = true, warpLost: Boolean = true) =
        RouteMemory.Entry(route, clock, 80, 80, warpMeasured, warpLost)

    @Test
    fun `a fresh decision is reused, a stale one is not`() {
        val k = mem.subKey("codm", "ME")
        mem.record("net1", k, entry())
        assertNotNull(mem.recent("net1", k))
        clock += RouteMemory.FRESH_MS + 1
        assertNull(mem.recent("net1", k))
    }

    @Test
    fun `an unknown network is neither read nor written`() {
        val k = mem.subKey("codm", "ME")
        mem.record(NetworkKey.UNKNOWN, k, entry())
        assertNull(mem.recent(NetworkKey.UNKNOWN, k))
    }

    @Test
    fun `warp is skipped only after repeated losses and no win`() {
        repeat(2) { mem.record("net1", mem.subKey("pubg", "ME"), entry()) }
        assertFalse(mem.shouldSkipWarp("net1", "pubg"))
        mem.record("net1", mem.subKey("pubg", "ME"), entry())
        assertTrue(mem.shouldSkipWarp("net1", "pubg"))
        mem.record("net1", mem.subKey("pubg", "ME"), entry(RouteKind.WARP_MASQUE_H3, warpLost = false))
        assertFalse(mem.shouldSkipWarp("net1", "pubg"))
        // Another game on the same network is unaffected.
        assertFalse(mem.shouldSkipWarp("net1", "codm"))
    }

    @Test
    fun `only the eight most recent networks are kept`() {
        for (i in 0 until 10) {
            clock += 1000
            mem.record("net$i", mem.subKey("codm", "ME"), entry())
        }
        assertNull(mem.recent("net0", mem.subKey("codm", "ME")))
        assertNull(mem.recent("net1", mem.subKey("codm", "ME")))
        assertNotNull(mem.recent("net9", mem.subKey("codm", "ME")))
    }

    @Test
    fun `memory survives a json round trip`() {
        val k = mem.subKey("codm", "ME")
        mem.record("net1", k, entry(RouteKind.WARP_MASQUE_H3, warpLost = false))
        val copy = RouteMemory { clock }.apply { loadJson(mem.toJson()) }
        assertEquals(RouteKind.WARP_MASQUE_H3, copy.recent("net1", k)?.route)
    }

    @Test
    fun `the day has four buckets`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(0, RouteMemory.bucket(0L, utc))                      // 00:00
        assertEquals(3, RouteMemory.bucket(21L * 3600_000L, utc))         // 21:00
    }
}
