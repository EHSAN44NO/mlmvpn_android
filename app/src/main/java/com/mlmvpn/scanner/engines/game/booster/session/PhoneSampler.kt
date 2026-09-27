package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process

/**
 * What the phone is doing right now, for [SpikeWatch]: the rest of the phone's traffic since the
 * last sample, the Wi-Fi signal and link rate, and how hot it is. No permission is needed for any
 * of it; a reading that is not available comes back null and is simply not blamed.
 */
class PhoneSampler(context: Context) {

    data class Sample(
        val bgRxKBps: Int?,
        val bgTxKBps: Int?,
        val wifiRssi: Int?,
        val wifiLinkMbps: Int?,
        val thermal: Int?,
    )

    private val app = context.applicationContext
    private val uid = Process.myUid()
    private var last: LongArray? = null
    private var lastAt = 0L

    fun sample(): Sample {
        val now = System.currentTimeMillis()
        val cur = longArrayOf(
            TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(),
            TrafficStats.getUidRxBytes(uid), TrafficStats.getUidTxBytes(uid),
        ).takeIf { v -> v.none { it == TrafficStats.UNSUPPORTED.toLong() } }
        var rx: Int? = null
        var tx: Int? = null
        val prev = last
        if (cur != null && prev != null && now > lastAt) {
            val ms = now - lastAt
            // Everything but this app's own probes -- and the game itself, which on a direct route
            // is counted here too; a game's own traffic is a few KB/s, far under the thresholds.
            rx = (((cur[0] - prev[0]) - (cur[2] - prev[2])).coerceAtLeast(0) * 1000 / ms / 1024).toInt()
            tx = (((cur[1] - prev[1]) - (cur[3] - prev[3])).coerceAtLeast(0) * 1000 / ms / 1024).toInt()
        }
        last = cur
        lastAt = now

        @Suppress("DEPRECATION")
        val wifi = try {
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
                ?.takeIf { it.networkId != -1 || it.rssi > -127 && it.linkSpeed > 0 }
        } catch (e: Exception) {
            null
        }
        val onWifi = GameNetwork.pick(app)?.isWifi == true
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            try { (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus } catch (e: Exception) { null }
        } else null
        return Sample(
            bgRxKBps = rx,
            bgTxKBps = tx,
            wifiRssi = if (onWifi) wifi?.rssi?.takeIf { it in -126..-1 } else null,
            wifiLinkMbps = if (onWifi) wifi?.linkSpeed?.takeIf { it > 0 } else null,
            thermal = thermal,
        )
    }
}

/** The last session's lag, kept for the booster screen after the game: how many spikes, and why. */
object LastSession {

    data class Summary(val gameId: String, val durationMs: Long, val spikes: Int, val mainCause: SpikeWatch.Cause?, val at: Long)

    private const val PREFS = "game_booster_prefs"
    private const val SHOWN_FOR_MS = 12 * 60 * 60 * 1000L

    fun save(ctx: Context, gameId: String, durationMs: Long, spikes: Int, mainCause: SpikeWatch.Cause?) {
        if (durationMs < 60_000L) return
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ls_game", gameId).putLong("ls_dur", durationMs).putInt("ls_spikes", spikes)
            .putString("ls_cause", mainCause?.name).putLong("ls_at", System.currentTimeMillis())
            .apply()
    }

    fun read(ctx: Context): Summary? {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("ls_at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > SHOWN_FOR_MS) return null
        return Summary(
            gameId = p.getString("ls_game", null) ?: return null,
            durationMs = p.getLong("ls_dur", 0L),
            spikes = p.getInt("ls_spikes", 0),
            mainCause = p.getString("ls_cause", null)?.let { n -> SpikeWatch.Cause.entries.firstOrNull { it.name == n } },
            at = at,
        )
    }

    fun dismiss(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("ls_at").apply()
    }
}
