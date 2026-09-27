package com.mlmvpn.scanner.engines.game.booster.catalog

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.game.booster.model.GameProfileCodec
import com.mlmvpn.scanner.engines.game.booster.model.GameRegion
import com.mlmvpn.scanner.engines.game.booster.model.PortRange
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import com.mlmvpn.scanner.engines.game.booster.model.TrafficClass
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the booster knows about each built-in game beyond its name and packages: which region
 * stands in for each of its regions, the hosts of each part of its traffic, its UDP ports, and a
 * note for the result card.
 *
 * Data, not code (`assets/game_catalog.json`), because it goes out of date on the network's
 * schedule, not the app's: in 2026 the Middle East servers of nearly every game moved within a
 * week. The same format is what a signed remote update will carry.
 *
 * An overlay: [com.mlmvpn.scanner.engines.game.GameDatabase] still says which games exist, and a
 * game with no entry here keeps what it derives from there.
 */
class GameCatalog(val version: Int, val updated: String, val entries: Map<String, Entry>) {

    data class Entry(
        val id: String,
        val name: String? = null,
        val regions: List<GameRegion> = emptyList(),
        val defaultRegion: String? = null,
        val udpPorts: List<PortRange> = emptyList(),
        val classes: Map<TrafficClass, List<String>> = emptyMap(),
        val steer: List<String> = emptyList(),
        val noteFa: String? = null,
        val noteEn: String? = null,
        /** False until a lab session with the game installed has confirmed the host lists. */
        val verified: Boolean = false,
    )

    operator fun get(id: String): Entry? = entries[id]

    companion object {
        private const val TAG = "GameCatalog"
        const val FORMAT = "mlmvpn-game-catalog"
        const val ASSET = "game_catalog.json"

        val EMPTY = GameCatalog(0, "", emptyMap())

        /**
         * Parse a catalog, or null if it is not one. A single malformed game is skipped rather
         * than taking the whole catalog down; a region pointing at an anchor this build does not
         * know is dropped, so an old app never measures a region it has no endpoints for.
         */
        fun parse(text: String): GameCatalog? = try {
            val o = JSONObject(text)
            if (o.optString("format") != FORMAT) null
            else {
                val games = o.optJSONArray("games") ?: JSONArray()
                val entries = LinkedHashMap<String, Entry>()
                for (i in 0 until games.length()) {
                    val g = games.optJSONObject(i) ?: continue
                    parseEntry(g)?.let { entries[it.id] = it }
                }
                GameCatalog(o.optInt("v", 0), o.optString("updated", ""), entries)
            }
        } catch (e: Exception) {
            null
        }

        private fun parseEntry(g: JSONObject): Entry? {
            val id = g.optString("id").takeIf { it.isNotBlank() } ?: return null
            val regions = g.optJSONArray("regions")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    val key = r.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val anchor = r.optString("anchor")
                    if (RegionCatalog.find(anchor) == null) return@mapNotNull null
                    GameRegion(key, anchor, r.optString("fa", key), r.optString("en", key))
                }
            }.orEmpty().distinctBy { it.key }
            return Entry(
                id = id,
                name = g.optString("name").takeIf { it.isNotBlank() },
                regions = regions,
                defaultRegion = g.optString("defaultRegion").takeIf { k -> k.isNotBlank() && regions.any { it.key == k } },
                udpPorts = PortRange.parseList(g.optString("udpPorts", "")),
                classes = GameProfileCodec.classesFromJson(g.optJSONObject("classes")),
                steer = g.optJSONArray("steer")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).trim().lowercase().takeIf { s -> s.isNotBlank() } }
                }.orEmpty(),
                noteFa = g.optString("noteFa").takeIf { it.isNotBlank() },
                noteEn = g.optString("noteEn").takeIf { it.isNotBlank() },
                verified = g.optBoolean("verified", false),
            )
        }

        @Volatile
        private var cached: GameCatalog? = null

        /** The bundled catalog, read once per process; [EMPTY] if it cannot be read. */
        fun get(context: Context): GameCatalog {
            cached?.let { return it }
            val loaded = try {
                com.mlmvpn.scanner.store.StoreFiles.open(context.applicationContext, ASSET).bufferedReader().use { it.readText() }
                    .let { parse(it) }
            } catch (e: Exception) {
                Log.w(TAG, "catalog unreadable: ${e.message}")
                null
            } ?: EMPTY
            cached = loaded
            return loaded
        }
    }
}
