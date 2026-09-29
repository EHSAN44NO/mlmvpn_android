package com.mlmvpn.scanner.engines.arena

import org.json.JSONArray
import org.json.JSONObject

/**
 * «میدان کانفیگ»: one race between the user's cloud panels, every number measured in this session.
 *
 * Nothing here is estimated. A round a competitor did not finish is absent (null), never zero, so
 * the score and the categories can tell "slow" from "not measured".
 */

enum class ArenaMode { QUICK, FULL }

/** Why a competitor left the race, in the words the result screen shows. */
enum class ArenaFail(val fa: String, val en: String) {
    NOT_INSTALLED("نصب نشد", "Could not be installed"),
    NO_CONFIG("کانفیگی نداد", "Gave no config"),
    INVALID("کانفیگ نامعتبر", "Invalid configuration"),
    UNREACHABLE("سرور در دسترس نیست", "Server unreachable"),
    TLS_REFUSED("دست‌دادن TLS رد شد", "TLS handshake refused"),
    NO_RESPONSE("از تونل جوابی نیامد", "No response through the tunnel"),
}

/** One measured sample, stamped with when it was taken. */
data class Sample(val at: Long, val ms: Long?) {
    val ok get() = ms != null && ms > 0
    fun toJson() = JSONObject().put("at", at).put("ms", ms ?: -1)
    companion object { fun from(o: JSONObject) = Sample(o.optLong("at"), o.optLong("ms").takeIf { it > 0 }) }
}

/** Everything one competitor did in one race. */
data class Entry(
    val panelId: String,
    val name: String,
    /** The config that raced (the fastest of the panel's candidates at qualifying). */
    val uri: String? = null,
    val candidates: Int = 0,
    val fail: ArenaFail? = null,
    val failDetail: String? = null,
    val qualifyMs: Long? = null,
    val latency: List<Sample> = emptyList(),
    /** Time to the first answer from a Cloudflare-hosted page, or null when it never came. */
    val reachMs: Long? = null,
    val reachTried: Boolean = false,
    /** Megabits per second over the fixed 1 MB download (Full only). */
    val mbps: Double? = null,
    val stability: List<Sample> = emptyList(),
) {
    val qualified get() = fail == null && uri != null
    val latencyMedian: Long? get() = latency.mapNotNull { it.ms?.takeIf { v -> v > 0 } }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val latencyMin: Long? get() = latency.mapNotNull { it.ms?.takeIf { v -> v > 0 } }.minOrNull()
    val stabilitySuccess: Double? get() = if (stability.isEmpty()) null else stability.count { it.ok }.toDouble() / stability.size
    /** p90 − p10 of the answered samples, in ms: how much the delay wanders. */
    val jitter: Long? get() {
        val v = stability.mapNotNull { it.ms?.takeIf { x -> x > 0 } }.sorted()
        if (v.size < 3) return null
        return v[(v.size - 1) * 9 / 10] - v[(v.size - 1) / 10]
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", panelId); put("name", name); put("uri", uri ?: ""); put("cand", candidates)
        put("fail", fail?.name ?: ""); put("failDetail", failDetail ?: ""); put("q", qualifyMs ?: -1)
        put("lat", JSONArray(latency.map { it.toJson() })); put("reach", reachMs ?: -1); put("reachTried", reachTried)
        put("mbps", mbps ?: -1.0); put("stab", JSONArray(stability.map { it.toJson() }))
    }

    companion object {
        fun from(o: JSONObject) = Entry(
            panelId = o.optString("id"), name = o.optString("name"), uri = o.optString("uri").ifBlank { null },
            candidates = o.optInt("cand"), fail = o.optString("fail").ifBlank { null }?.let { runCatching { ArenaFail.valueOf(it) }.getOrNull() },
            failDetail = o.optString("failDetail").ifBlank { null }, qualifyMs = o.optLong("q").takeIf { it > 0 },
            latency = o.optJSONArray("lat").samples(), reachMs = o.optLong("reach").takeIf { it > 0 }, reachTried = o.optBoolean("reachTried"),
            mbps = o.optDouble("mbps").takeIf { it > 0 }, stability = o.optJSONArray("stab").samples(),
        )
        private fun JSONArray?.samples() = if (this == null) emptyList() else (0 until length()).map { Sample.from(getJSONObject(it)) }
    }
}

/** The network the race ran on: results are only ever "current" for the same network. */
data class ArenaNetwork(val kind: String, val operator: String) {
    val label get() = listOf(kind, operator).filter { it.isNotBlank() }.joinToString(" · ")
    fun toJson() = JSONObject().put("kind", kind).put("op", operator)
    companion object { fun from(o: JSONObject?) = ArenaNetwork(o?.optString("kind").orEmpty(), o?.optString("op").orEmpty()) }
}

data class ArenaSession(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long,
    val mode: ArenaMode,
    val network: ArenaNetwork,
    val entries: List<Entry>,
    /** The one clean IP every config raced on, or null when each raced on its own address. */
    val cleanIp: String? = null,
) {
    val board: Scoreboard by lazy { ArenaScore.score(entries) }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("start", startedAt).put("end", finishedAt).put("mode", mode.name)
        .put("net", network.toJson()).put("entries", JSONArray(entries.map { it.toJson() }))
        .apply { if (cleanIp != null) put("ip", cleanIp) }

    companion object {
        fun from(o: JSONObject) = ArenaSession(
            id = o.optString("id"), startedAt = o.optLong("start"), finishedAt = o.optLong("end"),
            mode = runCatching { ArenaMode.valueOf(o.optString("mode")) }.getOrDefault(ArenaMode.QUICK),
            network = ArenaNetwork.from(o.optJSONObject("net")),
            entries = o.optJSONArray("entries")?.let { a -> (0 until a.length()).map { Entry.from(a.getJSONObject(it)) } }.orEmpty(),
            cleanIp = o.optString("ip").ifBlank { null },
        )
    }
}

enum class Category(val fa: String, val en: String) {
    OVERALL("بهترین کلی", "Best overall"),
    LATENCY("کم‌تأخیرترین", "Lowest latency"),
    SPEED("سریع‌ترین", "Fastest"),
    STABLE("پایدارترین", "Most stable"),
    REACH("بهترین دسترسی", "Best reach"),
}

/** One competitor's standing: the parts that were measured and the weighted total. */
data class Standing(
    val panelId: String,
    val total: Double,
    val parts: Map<String, Double>,
)

data class Scoreboard(
    /** Qualified competitors, best first. */
    val standings: List<Standing>,
    val categories: Map<Category, String>,
    /** The rounds that entered the score, with the weights actually used (renormalised). */
    val weights: Map<String, Double>,
) {
    val winner: String? get() = standings.firstOrNull()?.panelId
}
