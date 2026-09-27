package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.model.GameProfile
import com.mlmvpn.scanner.engines.game.booster.model.GameProfileCodec
import com.mlmvpn.scanner.engines.game.booster.model.GameRegion
import com.mlmvpn.scanner.engines.game.booster.model.LearnedServer
import com.mlmvpn.scanner.engines.game.booster.model.PortRange
import com.mlmvpn.scanner.engines.game.booster.model.RouteChoice
import com.mlmvpn.scanner.engines.game.booster.model.RoutePrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileCodecTest {

    private val profile = GameProfile(
        id = "custom:com.example.game",
        name = "Example",
        packages = listOf("com.example.game"),
        emoji = "🎮",
        builtIn = false,
        regions = listOf(GameRegion("ME", "aws-me-south-1", "بحرین", "Bahrain")),
        defaultRegion = "ME",
        udpPorts = PortRange.parseList("7085-7995, 8700"),
        loginHosts = listOf("login.example.com"),
        learned = listOf(LearnedServer("15.185.1.2", 10012, true, "ME", 7, 1L, 2L, 58, true)),
        prefs = RoutePrefs(route = RouteChoice.WARP, overlay = true),
    )

    @Test
    fun `port ranges parse, format and skip junk`() {
        val r = PortRange.parseList("7085-7995, 8700; x, 17000-20100")
        assertEquals(3, r.size)
        assertTrue(7100 in r[0])
        assertFalse(8000 in r[0])
        assertEquals("7085-7995, 8700, 17000-20100", PortRange.format(r))
        // Spaces around the dash are one range, not three ports.
        assertEquals(listOf(PortRange(7085, 7995)), PortRange.parseList("7085 - 7995"))
    }

    @Test
    fun `a profile survives a round trip`() {
        val back = GameProfileCodec.fromJson(GameProfileCodec.toJson(profile))
        assertEquals(profile, back)
        assertTrue(back!!.isCustom)
    }

    @Test
    fun `export leaves learned addresses out unless asked`() {
        val bare = GameProfileCodec.import(GameProfileCodec.export(profile, includeLearned = false))!!
        assertTrue(bare.learned.isEmpty())
        val full = GameProfileCodec.import(GameProfileCodec.export(profile, includeLearned = true))!!
        assertEquals(1, full.learned.size)
    }

    @Test
    fun `unknown fields are ignored and missing ones default`() {
        val o = JSONObject().put("id", "x").put("name", "X").put("future", 42)
        val p = GameProfileCodec.fromJson(o)!!
        assertEquals("🎮", p.emoji)
        assertEquals(1, p.regions.size)
        assertEquals(RouteChoice.AUTO, p.prefs.route)
    }

    @Test
    fun `a file that is not ours is refused`() {
        assertNull(GameProfileCodec.import("""{"format":"something-else","profile":{}}"""))
        assertNull(GameProfileCodec.import("not json"))
    }
}
