package com.mlmvpn.scanner.engines.game.booster.memory

import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * What the booster learned per network, so the next boost on the same line is instant.
 *
 * Keyed by network (see [NetworkKey]), then by game + region + time of day. Time of day matters on
 * Iranian lines: international congestion follows the evening, so an answer measured at 21:00 is
 * not an answer for 09:00 (same four buckets as the desktop booster's `profiles.js`).
 *
 * Pure logic with an injected clock; [RouteMemoryStore] persists it.
 */
class RouteMemory(private val now: () -> Long = System::currentTimeMillis) {

    /** The outcome of one boost: which route won, with the headline numbers behind it. */
    data class Entry(
        val route: RouteKind,
        val decidedAt: Long,
        val directP50: Int?,
        val chosenP50: Int?,
        /** Whether WARP was measured this time, and whether it lost (to decide skipping it). */
        val warpMeasured: Boolean,
        val warpLost: Boolean,
    )

    private val networks = LinkedHashMap<String, NetworkRecord>()

    private data class NetworkRecord(var lastUsed: Long, val entries: MutableMap<String, MutableList<Entry>>)

    fun subKey(gameId: String, regionKey: String, at: Long = now()): String =
        "$gameId|$regionKey|${bucket(at)}"

    /** The latest decision for this game on this network in this part of the day, if fresh. */
    fun recent(networkKey: String, subKey: String, maxAgeMs: Long = FRESH_MS): Entry? {
        if (networkKey == NetworkKey.UNKNOWN) return null
        val e = networks[networkKey]?.entries?.get(subKey)?.lastOrNull() ?: return null
        return e.takeIf { now() - it.decidedAt in 0..maxAgeMs }
    }

    /**
     * Whether quick boosts should skip measuring WARP: it lost at least [SKIP_AFTER_LOSSES] times
     * for this game on this network in the last week and never won. The card says so, and a
     * thorough boost measures it anyway.
     */
    fun shouldSkipWarp(networkKey: String, gameId: String): Boolean {
        if (networkKey == NetworkKey.UNKNOWN) return false
        val cutoff = now() - WEEK_MS
        val all = networks[networkKey]?.entries
            ?.filterKeys { it.startsWith("$gameId|") }?.values?.flatten()
            ?.filter { it.decidedAt >= cutoff && it.warpMeasured } ?: return false
        val losses = all.count { it.warpLost }
        val wins = all.count { !it.warpLost }
        return wins == 0 && losses >= SKIP_AFTER_LOSSES
    }

    fun record(networkKey: String, subKey: String, entry: Entry) {
        if (networkKey == NetworkKey.UNKNOWN) return
        val rec = networks.getOrPut(networkKey) { NetworkRecord(now(), mutableMapOf()) }
        rec.lastUsed = now()
        val list = rec.entries.getOrPut(subKey) { mutableListOf() }
        list.add(entry)
        // Keep a short history per key: enough for "lost three times this week", no more.
        while (list.size > HISTORY_PER_KEY) list.removeAt(0)
        evict()
    }

    fun clear() = networks.clear()

    /** Drop entries older than a month and keep only the [MAX_NETWORKS] most recently used. */
    private fun evict() {
        val cutoff = now() - MONTH_MS
        networks.values.forEach { r ->
            r.entries.values.forEach { l -> l.removeAll { it.decidedAt < cutoff } }
            r.entries.entries.removeAll { it.value.isEmpty() }
        }
        networks.entries.removeAll { it.value.entries.isEmpty() }
        if (networks.size > MAX_NETWORKS) {
            networks.entries.sortedBy { it.value.lastUsed }.take(networks.size - MAX_NETWORKS)
                .map { it.key }.forEach { networks.remove(it) }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        networks.forEach { (net, r) ->
            put(net, JSONObject().put("used", r.lastUsed).put("e", JSONObject().apply {
                r.entries.forEach { (k, list) ->
                    put(k, org.json.JSONArray().apply {
                        list.forEach { e ->
                            put(JSONObject().put("r", e.route.name).put("t", e.decidedAt)
                                .put("d", e.directP50 ?: JSONObject.NULL).put("c", e.chosenP50 ?: JSONObject.NULL)
                                .put("wm", e.warpMeasured).put("wl", e.warpLost))
                        }
                    })
                }
            }))
        }
    }

    fun loadJson(o: JSONObject) {
        networks.clear()
        o.keys().forEach { net ->
            if (net == "v") return@forEach
            val r = o.optJSONObject(net) ?: return@forEach
            val entries = mutableMapOf<String, MutableList<Entry>>()
            val eo = r.optJSONObject("e") ?: return@forEach
            eo.keys().forEach { k ->
                val arr = eo.optJSONArray(k) ?: return@forEach
                entries[k] = (0 until arr.length()).mapNotNull { i ->
                    val x = arr.optJSONObject(i) ?: return@mapNotNull null
                    val route = RouteKind.entries.firstOrNull { it.name == x.optString("r") } ?: return@mapNotNull null
                    Entry(
                        route = route,
                        decidedAt = x.optLong("t"),
                        directP50 = if (x.isNull("d")) null else x.optInt("d"),
                        chosenP50 = if (x.isNull("c")) null else x.optInt("c"),
                        warpMeasured = x.optBoolean("wm"),
                        warpLost = x.optBoolean("wl"),
                    )
                }.toMutableList()
            }
            networks[net] = NetworkRecord(r.optLong("used"), entries)
        }
    }

    companion object {
        const val FRESH_MS = 6 * 60 * 60 * 1000L
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        const val MONTH_MS = 30 * 24 * 60 * 60 * 1000L
        const val MAX_NETWORKS = 8
        const val HISTORY_PER_KEY = 6
        const val SKIP_AFTER_LOSSES = 3

        /** Four parts of the day: night 0–6, morning 6–12, afternoon 12–18, evening 18–24. */
        fun bucket(at: Long, tz: TimeZone = TimeZone.getDefault()): Int {
            val c = Calendar.getInstance(tz).apply { timeInMillis = at }
            return c.get(Calendar.HOUR_OF_DAY) / 6
        }
    }
}

/** [RouteMemory] kept in `files/game_booster/memory.json`. */
class RouteMemoryStore(context: android.content.Context) {
    private val file = java.io.File(java.io.File(context.applicationContext.filesDir, "game_booster").apply { mkdirs() }, "memory.json")
    val memory = RouteMemory()

    init {
        try { if (file.exists()) memory.loadJson(JSONObject(file.readText())) } catch (_: Exception) {}
    }

    fun save() {
        try {
            val tmp = java.io.File(file.parentFile, "memory.json.tmp")
            tmp.writeText(memory.toJson().toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    fun clear() {
        memory.clear()
        save()
    }
}
