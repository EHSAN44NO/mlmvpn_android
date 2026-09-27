package com.mlmvpn.scanner.engines.game.booster.brain

import org.json.JSONObject

/**
 * One boost session as the learning side sees it: what was decided, and later how it went.
 * Built by the orchestrator when the route is in place, closed by the service when the session
 * ends, and turned into the crowd service's anonymous row.
 */
data class SessionRecord(
    val gameId: String,
    /** Only built-in games are shared: a custom game's package name is the player's business. */
    val shareable: Boolean,
    /** The game region the boost measured. */
    val regionKey: String,
    /** [Brain.planCode] of the applied route. */
    val plan: String,
    /** Kind 1–5 on this line, 0 when the doctor could not tell. */
    val kind: Int,
    val warpMeasured: Boolean,
    val warpWon: Boolean,
    val chosenP50: Int?,
    /** Anti-sanction DNS trials from a fresh round on this line: 1 opened, 0 answered but did not, −1 no answer. */
    val sdnsTrials: Map<String, Int>,
    val networkKey: String,
    val startedAt: Long,
    /** Picked for the crowd sample when the session began. */
    val sampled: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("g", gameId).put("sh", shareable).put("r", regionKey).put("p", plan).put("k", kind)
        .put("wm", warpMeasured).put("ww", warpWon).put("p50", chosenP50 ?: JSONObject.NULL)
        .put("s", JSONObject(sdnsTrials as Map<*, *>)).put("n", networkKey).put("t", startedAt).put("sa", sampled)

    companion object {
        fun fromJson(o: JSONObject): SessionRecord? = try {
            SessionRecord(
                gameId = o.getString("g"),
                shareable = o.optBoolean("sh"),
                regionKey = o.getString("r"),
                plan = o.getString("p"),
                kind = o.optInt("k"),
                warpMeasured = o.optBoolean("wm"),
                warpWon = o.optBoolean("ww"),
                chosenP50 = if (o.isNull("p50")) null else o.optInt("p50"),
                sdnsTrials = o.optJSONObject("s")?.let { s -> s.keys().asSequence().associateWith { s.optInt(it) } }.orEmpty(),
                networkKey = o.optString("n"),
                startedAt = o.optLong("t"),
                sampled = o.optBoolean("sa"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * How a session went when the player said nothing. Pure.
 *
 * Only signals the booster can observe without watching the player: how long the route stayed up,
 * how much of that time the live reading was bad, whether the route vanished, and whether the
 * player stopped almost at once. Each comes with a weight -- a long clean session is decent
 * evidence it worked; a stop after a minute is weak evidence it did not (they may only have
 * wanted to look); an explicit answer, handled elsewhere, outweighs both.
 */
object SessionOutcome {

    data class Facts(
        val durationMs: Long,
        val userStopped: Boolean,
        /** Live reading shown as good / as degraded, lossy, unstable or down, in ms. */
        val goodMs: Long,
        val badMs: Long,
        /** Our route disappeared under the game (VPN stopped or replaced) before the player stopped. */
        val routeLost: Boolean,
    )

    data class Verdict(val outcome: Int, val weight: Double)

    const val LONG_MS = 10 * 60 * 1000L
    const val SHORT_MS = 3 * 60 * 1000L

    fun implicit(f: Facts): Verdict {
        val observed = f.goodMs + f.badMs
        val badShare = if (observed > 0) f.badMs.toDouble() / observed else 0.0
        return when {
            f.routeLost -> Verdict(-1, 1.0)
            observed >= 2 * 60 * 1000L && badShare >= 0.5 -> Verdict(-1, 1.0)
            f.userStopped && f.durationMs < SHORT_MS -> Verdict(-1, 0.5)
            f.durationMs >= LONG_MS && badShare < 0.3 -> Verdict(1, 1.0)
            else -> Verdict(0, 0.0)
        }
    }
}

/** The crowd service's row format (see worker-src/game-crowd/worker.js `cleanRow`). Pure. */
object CrowdRows {

    fun session(r: SessionRecord, outcome: Int): JSONObject = JSONObject()
        .put("g", r.gameId).put("r", r.regionKey).put("p", r.plan).put("o", outcome)
        .put("wm", if (r.warpMeasured) 1 else 0).put("ww", if (r.warpWon) 1 else 0)
        .put("k", r.kind)
        .apply { r.chosenP50?.let { put("p50", it) } }
        .apply { if (r.sdnsTrials.isNotEmpty()) put("s", JSONObject(r.sdnsTrials as Map<*, *>)) }

    /** The player's own answer: a vote on the plan, counted three times, adding no session. */
    fun feedback(r: SessionRecord, outcome: Int): JSONObject = JSONObject()
        .put("g", r.gameId).put("r", r.regionKey).put("p", r.plan).put("o", outcome).put("f", 1)
}
