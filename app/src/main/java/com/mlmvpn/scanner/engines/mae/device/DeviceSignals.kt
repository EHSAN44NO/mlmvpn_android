package com.mlmvpn.scanner.engines.mae.device

import android.content.Context
import android.telephony.TelephonyManager
import java.util.TimeZone

/**
 * What an app can read on the phone itself, beyond the network: no route changes these.
 *
 * TikTok, for one, sends the SIM card's country and the phone's time zone with its requests; with
 * an Iranian SIM it answers "no internet connection" through any exit, however foreign (the user's
 * report, 2026-09-30: five repairs, none opened it). An app's registry entry names what it checks
 * (`clientChecks`); when the phone says Iran on one of those, MAE says so instead of trying route
 * after route. No permission is needed for either reading.
 */
object DeviceSignals {
    const val SIM = "sim"
    const val TIMEZONE = "timezone"

    /** Iran's offset from UTC, +03:30, in milliseconds. */
    private const val IRAN_OFFSET_MS = (3 * 60 + 30) * 60_000

    @Volatile private var cached: Pair<Set<String>, Long>? = null
    private const val CACHE_MS = 60_000L

    /** Which of [checks] the phone would answer with Iran right now. */
    fun iranian(context: Context, checks: List<String>): List<String> {
        if (checks.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val all = cached?.takeIf { now - it.second < CACHE_MS }?.first ?: read(context).also { cached = it to now }
        return checks.filter { it in all }
    }

    private fun read(context: Context): Set<String> = buildSet {
        val tm = runCatching { context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager }.getOrNull()
        val sim = runCatching { tm?.simCountryIso.orEmpty() }.getOrDefault("")
        val network = runCatching { tm?.networkCountryIso.orEmpty() }.getOrDefault("")
        if (sim.equals("ir", true) || network.equals("ir", true)) add(SIM)
        val tz = TimeZone.getDefault()
        if (tz.id == "Asia/Tehran" || tz.rawOffset == IRAN_OFFSET_MS) add(TIMEZONE)
    }
}
