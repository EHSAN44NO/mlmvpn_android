package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context

/**
 * Small facts about a network that decide how the NEXT boost on it starts, before anything is
 * measured -- kept per network key, and forgotten after [TTL_MS].
 *
 * Today one fact: foreign UDP did not come back on this line (Irancell drops it). WARP is started
 * at the very beginning of a boost so its gateway scan overlaps the other measurements, and on
 * such a line only the TCP variant (MASQUE over HTTP/2) can connect -- so the boost has to know
 * before the line says it again.
 */
object NetworkTraits {

    private const val PREFS = "game_booster_prefs"
    const val TTL_MS = 7 * 24 * 60 * 60 * 1000L

    fun udpSilent(ctx: Context, networkKey: String): Boolean {
        if (networkKey.isEmpty()) return false
        val at = prefs(ctx).getLong("udp_silent_$networkKey", 0L)
        return at > 0 && System.currentTimeMillis() - at < TTL_MS
    }

    fun setUdpSilent(ctx: Context, networkKey: String, silent: Boolean) {
        if (networkKey.isEmpty()) return
        val e = prefs(ctx).edit()
        if (silent) e.putLong("udp_silent_$networkKey", System.currentTimeMillis()) else e.remove("udp_silent_$networkKey")
        e.apply()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
