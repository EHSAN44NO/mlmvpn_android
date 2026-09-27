package com.mlmvpn.scanner.engines.game.booster.brain

import android.content.Context
import com.mlmvpn.scanner.engines.game.booster.crowd.CrowdClient
import org.json.JSONObject

/**
 * «بازی خوب کار کرد؟» -- the one question the booster asks, and not often.
 *
 * Asked after a session that ran at least [MIN_SESSION_MS], and only on every [EVERY]th such
 * session: a question after every game becomes noise players tap through. An answer is the
 * strongest signal the booster gets -- it weighs three sessions on this phone, and (when sharing
 * is on) counts as a vote for the plan among players on the same operator.
 */
object Feedback {

    private const val PREFS = "game_booster_prefs"
    private const val KEY_PENDING = "fb_pending"
    private const val KEY_AT = "fb_pending_at"
    private const val KEY_COUNT = "fb_sessions"
    const val MIN_SESSION_MS = 2 * 60 * 1000L
    const val EVERY = 3
    private const val EXPIRES_MS = 6 * 60 * 60 * 1000L
    const val ANSWER_WEIGHT = 3.0

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun offer(ctx: Context, record: SessionRecord, durationMs: Long) {
        if (durationMs < MIN_SESSION_MS) return
        val p = prefs(ctx)
        val n = p.getInt(KEY_COUNT, 0) + 1
        p.edit().putInt(KEY_COUNT, n).apply()
        if ((n - 1) % EVERY != 0) return
        p.edit().putString(KEY_PENDING, record.toJson().toString()).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun pending(ctx: Context): SessionRecord? {
        val p = prefs(ctx)
        val text = p.getString(KEY_PENDING, null) ?: return null
        if (System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > EXPIRES_MS) {
            dismiss(ctx)
            return null
        }
        return try { SessionRecord.fromJson(JSONObject(text)) } catch (e: Exception) { null }
    }

    fun answer(ctx: Context, worked: Boolean) {
        val r = pending(ctx) ?: return
        val outcome = if (worked) 1 else -1
        val store = BrainStore(ctx)
        store.brain.record(r.networkKey, r.gameId, r.plan, outcome, ANSWER_WEIGHT)
        store.save()
        if (r.shareable) CrowdClient.queue(ctx, CrowdRows.feedback(r, outcome))
        dismiss(ctx)
    }

    fun dismiss(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PENDING).remove(KEY_AT).apply()
    }
}
