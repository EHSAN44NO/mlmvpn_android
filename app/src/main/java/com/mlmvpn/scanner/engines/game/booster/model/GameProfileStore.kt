package com.mlmvpn.scanner.engines.game.booster.model

import android.content.Context
import android.content.pm.PackageManager
import com.mlmvpn.scanner.engines.game.GameDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every game the booster knows: built-ins merged with the user's custom games, preference
 * overrides and learned servers.
 *
 * Only what the user changed is written, to `files/game_booster/profiles.json`:
 *
 *     {"v":1,"custom":[<profile>…],"prefs":{id:<prefs>},"regions":{id:"EU"},"learned":{id:[…]}}
 *
 * Built-ins themselves are never stored, so an app update that corrects one is never overruled by
 * a stale copy on disk. Writes go to a temp file first and are renamed into place, so a crash
 * mid-write cannot leave half a file behind.
 */
class GameProfileStore(context: Context) {

    private val app = context.applicationContext
    private val file = File(File(app.filesDir, "game_booster").apply { mkdirs() }, "profiles.json")

    private val lock = Any()

    fun all(): List<GameProfile> = synchronized(lock) {
        val state = read()
        val builtIns = BuiltInProfiles.from(GameDatabase.games, com.mlmvpn.scanner.engines.game.booster.catalog.GameCatalog.get(app))
        (builtIns + state.custom).map { apply(it, state) }
    }

    fun get(id: String): GameProfile? = all().firstOrNull { it.id == id }

    /** Installed first (in their list order), then the rest; customs always count as installed. */
    fun allSorted(): List<Pair<GameProfile, Boolean>> {
        val pm = app.packageManager
        return all().map { p -> p to (p.isCustom || p.packages.any { isInstalled(pm, it) }) }
            .sortedByDescending { it.second }
    }

    /** The first of the game's packages that is actually installed, for launching and routing. */
    fun installedPackage(p: GameProfile): String? {
        val pm = app.packageManager
        return p.packages.firstOrNull { isInstalled(pm, it) }
    }

    fun saveCustom(profile: GameProfile) = mutate { s ->
        require(profile.isCustom)
        s.copy(custom = s.custom.filterNot { it.id == profile.id } + profile.copy(learned = emptyList()))
    }

    fun removeCustom(id: String) = mutate { s ->
        s.copy(custom = s.custom.filterNot { it.id == id }, prefs = s.prefs - id,
            regions = s.regions - id, learned = s.learned - id)
    }

    fun setPrefs(id: String, prefs: RoutePrefs) = mutate { s -> s.copy(prefs = s.prefs + (id to prefs)) }

    fun setRegion(id: String, regionKey: String) = mutate { s -> s.copy(regions = s.regions + (id to regionKey)) }

    /**
     * Merge [servers] into the game's learned list: same ip:port adds hits and moves lastSeen,
     * new ones are appended, pinned ones are never dropped, and the list is capped at
     * [GameProfile.MAX_LEARNED] keeping pinned first, then the most-seen.
     */
    fun mergeLearned(id: String, servers: List<LearnedServer>) = mutate { s ->
        val existing = s.learned[id].orEmpty().associateBy { "${it.ip}:${it.port}:${it.udp}" }.toMutableMap()
        for (n in servers) {
            val k = "${n.ip}:${n.port}:${n.udp}"
            val old = existing[k]
            existing[k] = if (old == null) n else old.copy(
                hits = old.hits + n.hits,
                lastSeen = maxOf(old.lastSeen, n.lastSeen),
                firstSeen = minOf(old.firstSeen, n.firstSeen),
                icmpMinMs = n.icmpMinMs ?: old.icmpMinMs,
                regionKey = n.regionKey ?: old.regionKey,
            )
        }
        val capped = existing.values.sortedWith(compareByDescending<LearnedServer> { it.pinned }.thenByDescending { it.hits })
            .take(GameProfile.MAX_LEARNED)
        s.copy(learned = s.learned + (id to capped))
    }

    fun setLearned(id: String, servers: List<LearnedServer>) = mutate { s ->
        s.copy(learned = s.learned + (id to servers.take(GameProfile.MAX_LEARNED)))
    }

    // ── storage ────────────────────────────────────────────────────────────────────────────────

    private data class State(
        val custom: List<GameProfile> = emptyList(),
        val prefs: Map<String, RoutePrefs> = emptyMap(),
        val regions: Map<String, String> = emptyMap(),
        val learned: Map<String, List<LearnedServer>> = emptyMap(),
    )

    private fun apply(p: GameProfile, s: State): GameProfile = p.copy(
        prefs = s.prefs[p.id] ?: p.prefs,
        defaultRegion = s.regions[p.id]?.takeIf { key -> p.regions.any { it.key == key } } ?: p.defaultRegion,
        learned = s.learned[p.id] ?: p.learned,
    )

    private fun mutate(change: (State) -> State) {
        synchronized(lock) { write(change(read())) }
    }

    private fun read(): State {
        if (!file.exists()) return State()
        return try {
            val o = JSONObject(file.readText())
            State(
                custom = o.optJSONArray("custom")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(GameProfileCodec::fromJson) }
                }.orEmpty(),
                prefs = o.optJSONObject("prefs")?.let { m ->
                    m.keys().asSequence().associateWith { k -> GameProfileCodec.prefsFromJson(m.optJSONObject(k)) }
                }.orEmpty(),
                regions = o.optJSONObject("regions")?.let { m ->
                    m.keys().asSequence().associateWith { k -> m.optString(k) }
                }.orEmpty(),
                learned = o.optJSONObject("learned")?.let { m ->
                    m.keys().asSequence().associateWith { k -> GameProfileCodec.learnedFromJson(m.optJSONArray(k)) }
                }.orEmpty(),
            )
        } catch (e: Exception) {
            // A damaged file must not take the whole booster down; start clean, keep the bad copy.
            try { file.copyTo(File(file.parentFile, "profiles.corrupt.json"), overwrite = true) } catch (_: Exception) {}
            State()
        }
    }

    private fun write(s: State) {
        val o = JSONObject()
            .put("v", GameProfileCodec.VERSION)
            .put("custom", JSONArray().apply { s.custom.forEach { put(GameProfileCodec.toJson(it, includeLearned = false)) } })
            .put("prefs", JSONObject().apply { s.prefs.forEach { (k, v) -> put(k, GameProfileCodec.prefsToJson(v)) } })
            .put("regions", JSONObject().apply { s.regions.forEach { (k, v) -> put(k, v) } })
            .put("learned", JSONObject().apply { s.learned.forEach { (k, v) -> put(k, GameProfileCodec.learnedToJson(v)) } })
        val tmp = File(file.parentFile, "profiles.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }
}
