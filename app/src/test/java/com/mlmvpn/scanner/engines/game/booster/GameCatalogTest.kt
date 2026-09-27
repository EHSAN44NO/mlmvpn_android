package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.GameInfo
import com.mlmvpn.scanner.engines.game.GameServer
import com.mlmvpn.scanner.engines.game.booster.catalog.GameCatalog
import com.mlmvpn.scanner.engines.game.booster.model.BuiltInProfiles
import com.mlmvpn.scanner.engines.game.booster.model.GameProfile
import com.mlmvpn.scanner.engines.game.booster.model.GameProfileCodec
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import com.mlmvpn.scanner.engines.game.booster.model.RoutePrefs
import com.mlmvpn.scanner.engines.game.booster.model.TrafficClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameCatalogTest {

    /** The bundled asset, read from the module like the app reads it from assets. */
    private fun bundled(): GameCatalog {
        val f = listOf(File("src/main/assets/game_catalog.json"), File("app/src/main/assets/game_catalog.json"))
            .first { it.exists() }
        return GameCatalog.parse(f.readText())!!
    }

    @Test
    fun `the bundled catalog parses and covers the three games most players play`() {
        val c = bundled()
        for (id in listOf("codm", "pubg", "fifamobile")) {
            val e = c[id]
            assertNotNull("missing $id", e)
            assertTrue("$id has no regions", e!!.regions.isNotEmpty())
            assertTrue("$id has no hosts", e.classes.isNotEmpty())
            // Every region points at an anchor this build can measure.
            e.regions.forEach { assertNotNull(RegionCatalog.find(it.anchorRegion)) }
        }
        assertEquals("EA SPORTS FC Mobile", c["fifamobile"]!!.name)
    }

    @Test
    fun `no game defaults to a region known to be down`() {
        val c = bundled()
        c.entries.values.forEach { e ->
            val def = e.regions.first { it.key == (e.defaultRegion ?: e.regions.first().key) }
            assertTrue("${e.id} defaults to a dead region",
                RegionCatalog.byKey(def.anchorRegion).status != RegionCatalog.Status.DOWN)
        }
        assertTrue(RegionCatalog.byKey(RegionCatalog.DEFAULT_REGION).status == RegionCatalog.Status.OK)
    }

    @Test
    fun `junk and foreign formats are refused, a bad game is skipped`() {
        assertNull(GameCatalog.parse("not json"))
        assertNull(GameCatalog.parse("""{"format":"something-else","games":[]}"""))
        val c = GameCatalog.parse("""
            {"format":"mlmvpn-game-catalog","v":3,"games":[
              {"regions":[]},
              {"id":"x","regions":[{"key":"EU","anchor":"aws-eu-central-1"},{"key":"ZZ","anchor":"no-such-region"}],
               "defaultRegion":"ZZ","classes":{"LOGIN":["A.Example.com"],"NOPE":["b.example.com"]}}
            ]}
        """.trimIndent())!!
        assertEquals(3, c.version)
        assertEquals(setOf("x"), c.entries.keys)
        val x = c["x"]!!
        assertEquals(listOf("EU"), x.regions.map { it.key })   // unknown anchor dropped
        assertNull(x.defaultRegion)                             // pointed at the dropped region
        assertEquals(mapOf(TrafficClass.LOGIN to listOf("a.example.com")), x.classes)
    }

    @Test
    fun `the catalog overlays the old game list and leaves unknown games alone`() {
        val games = listOf(
            GameInfo("codm", "Call of Duty Mobile", "com.activision.callofduty.shooter", iconEmoji = "🔫",
                servers = listOf(GameServer("ME", "ME", listOf("old.example.com")))),
            GameInfo("other", "Other", "com.other", iconEmoji = "🎮",
                servers = listOf(GameServer("EU", "EU", listOf("login.other.com")))),
        )
        val profiles = BuiltInProfiles.from(games, bundled())
        val codm = profiles.first { it.id == "codm" }
        assertEquals("EU", codm.defaultRegion)
        assertTrue(codm.accessHosts().getValue(TrafficClass.LOGIN).isNotEmpty())
        assertFalse(codm.loginHosts.contains("old.example.com"))
        val other = profiles.first { it.id == "other" }
        assertEquals(listOf("login.other.com"), other.loginHosts)
        assertEquals(mapOf(TrafficClass.LOGIN to listOf("login.other.com")), other.accessHosts())
    }

    @Test
    fun `parts, steer, notes and the sanction switch survive a profile round trip`() {
        val p = GameProfile(
            id = "custom:x", name = "X", packages = listOf("x"), emoji = "🎮", builtIn = false,
            regions = BuiltInProfiles.custom("x", "X").regions, defaultRegion = "EU",
            classes = mapOf(TrafficClass.LOGIN to listOf("a.x.com"), TrafficClass.CDN to listOf("cdn.x.com")),
            steer = listOf("x.com"), noteFa = "یادداشت", noteEn = "note",
            prefs = RoutePrefs(sanctionDns = false),
        )
        assertEquals(p, GameProfileCodec.fromJson(GameProfileCodec.toJson(p)))
        // An old stored profile without the switch keeps it on.
        assertTrue(GameProfileCodec.prefsFromJson(org.json.JSONObject("""{"route":"AUTO"}""")).sanctionDns)
    }
}
