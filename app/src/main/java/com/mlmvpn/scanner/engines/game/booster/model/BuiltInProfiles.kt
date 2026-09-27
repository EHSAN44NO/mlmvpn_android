package com.mlmvpn.scanner.engines.game.booster.model

import com.mlmvpn.scanner.engines.game.GameInfo
import com.mlmvpn.scanner.engines.game.booster.catalog.GameCatalog

/**
 * The built-in games, rebuilt from the existing [com.mlmvpn.scanner.engines.game.GameDatabase] on
 * every load so the old Game tab and the new booster can never disagree about which games exist.
 *
 * What the old list lacked comes from the [GameCatalog] overlay: which datacentre region stands
 * in for each of the game's regions, the hosts of each part of its traffic, its UDP ports and a
 * note. A game the catalog does not know keeps what can be derived from the old list.
 */
object BuiltInProfiles {

    /** A game region code from GameDatabase → the anchor region measured for it, and its labels. */
    private data class RegionMap(val anchor: String, val fa: String, val en: String)

    private val REGION_MAP = mapOf(
        // Where the Middle East servers of most games were until March 2026. Kept: the region
        // sweep shows it as down and says which region to pick instead.
        "ME" to RegionMap("aws-me-south-1", "خاورمیانه", "Middle East"),
        "EU" to RegionMap("aws-eu-central-1", "اروپا", "Europe"),
        "AS" to RegionMap("aws-ap-southeast-1", "آسیا", "Asia"),
    )

    fun from(games: List<GameInfo>, catalog: GameCatalog = GameCatalog.EMPTY): List<GameProfile> = games.map { g ->
        val derived = g.servers.map { s ->
            val m = REGION_MAP[s.region] ?: RegionMap(RegionCatalog.DEFAULT_REGION, s.displayName, s.displayName)
            GameRegion(key = s.region, anchorRegion = m.anchor, labelFa = m.fa, labelEn = m.en)
        }.distinctBy { it.key }
        val entry = catalog[g.id]
        val regions = entry?.regions?.takeIf { it.isNotEmpty() } ?: derived.ifEmpty {
            listOf(GameRegion("EU", RegionCatalog.DEFAULT_REGION, "اروپا", "Europe"))
        }
        val legacyDefault = g.servers.firstOrNull { it.region == g.defaultRegion }?.region
        val defaultRegion = entry?.defaultRegion
            ?: legacyDefault?.takeIf { k -> regions.any { it.key == k } }
            ?: regions.first().key
        val classes = entry?.classes.orEmpty()
        GameProfile(
            id = g.id,
            name = entry?.name ?: g.name,
            packages = listOf(g.packageName) + g.alternatePackages,
            emoji = g.iconEmoji,
            builtIn = true,
            regions = regions,
            defaultRegion = defaultRegion,
            udpPorts = entry?.udpPorts.orEmpty(),
            // Sign-in and the game's own services, for anything that still reads loginHosts; the
            // doctor reads every part from [classes].
            loginHosts = if (classes.isNotEmpty()) {
                (classes[TrafficClass.LOGIN].orEmpty() + classes[TrafficClass.API].orEmpty()).distinct()
            } else {
                g.servers.flatMap { it.testEndpoints }.distinct()
            },
            classes = classes,
            steer = entry?.steer.orEmpty(),
            noteFa = entry?.noteFa,
            noteEn = entry?.noteEn,
        )
    }

    /** A fresh profile for an app the user picked from their installed apps. */
    fun custom(packageName: String, label: String, regionKey: String = "EU"): GameProfile {
        val m = REGION_MAP[regionKey] ?: REGION_MAP.getValue("EU")
        return GameProfile(
            id = GameProfile.CUSTOM_PREFIX + packageName,
            name = label,
            packages = listOf(packageName),
            emoji = "🎮",
            builtIn = false,
            regions = listOf(GameRegion(regionKey, m.anchor, m.fa, m.en)),
            defaultRegion = regionKey,
        )
    }

    /** The regions a custom game can be put in, for the add-game sheet. */
    val CUSTOM_REGION_CHOICES: List<Pair<String, String>> =
        REGION_MAP.map { (key, m) -> key to m.fa }
}
