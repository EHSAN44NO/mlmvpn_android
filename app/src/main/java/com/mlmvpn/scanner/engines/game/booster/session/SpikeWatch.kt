package com.mlmvpn.scanner.engines.game.booster.session

import kotlin.math.max

/**
 * Why did it lag just now? -- a port of the desktop booster's `watch.js`.
 *
 * Every live tick the monitor knows two things: how the ping behaved in that burst, and what the
 * PHONE was doing at that moment -- how much the rest of the phone was downloading or uploading,
 * the Wi-Fi signal and link rate, and how hot the phone was. A tick whose ping jumped well above
 * the session's own normal is a spike, and the spike is blamed on whatever moved with it. Nothing
 * moved -> the line itself (the operator, or the route to the game's region).
 *
 * The point is the one thing a player cannot see: "the lag was your phone downloading an update",
 * "your Wi-Fi signal dropped", "the phone was hot" -- each with a fix -- versus "the line itself",
 * which no setting on the phone will change. Pure; the service feeds it.
 */
class SpikeWatch {

    enum class Cause { BACKGROUND, WIFI_SIGNAL, WIFI_RATE, HEAT, LINE }

    /** One live tick: the burst, and the phone at that moment. Null fields were not readable. */
    data class Tick(
        val maxRttMs: Double?,
        val lost: Int,
        val sent: Int,
        /** The rest of the phone's traffic during the tick, KB/s each way. */
        val bgRxKBps: Int?,
        val bgTxKBps: Int?,
        val wifiRssi: Int?,
        val wifiLinkMbps: Int?,
        /** PowerManager thermal status, 0 (none) … 6 (shutdown). */
        val thermal: Int?,
    )

    private val rtts = ArrayList<Double>()
    private val rssis = ArrayList<Int>()
    private val rates = ArrayList<Int>()
    val counts: MutableMap<Cause, Int> = linkedMapOf()
    var ticks = 0
        private set
    var spikes = 0
        private set

    /** Feed one tick; the cause if it was a spike, else null. */
    fun add(t: Tick): Cause? {
        ticks++
        val median = rtts.median()
        val rssiMedian = rssis.medianInt()
        val rateMedian = rates.medianInt()
        t.maxRttMs?.let { rtts.addBounded(it) }
        t.wifiRssi?.let { rssis.addBounded(it) }
        t.wifiLinkMbps?.takeIf { it > 0 }?.let { rates.addBounded(it) }
        // A session needs a normal before anything can be above it.
        if (median == null || rtts.size < MIN_TICKS) return null

        val jumped = t.maxRttMs != null && t.maxRttMs > max(2 * median, median + SPIKE_OVER_MS)
        val dropped = t.sent > 0 && t.lost >= max(2, (t.sent + 1) / 2)
        if (!jumped && !dropped) return null

        spikes++
        val cause = when {
            (t.bgRxKBps ?: 0) >= BG_RX_KBPS || (t.bgTxKBps ?: 0) >= BG_TX_KBPS -> Cause.BACKGROUND
            t.wifiRssi != null && rssiMedian != null && (rssiMedian - t.wifiRssi >= RSSI_DROP_DB || t.wifiRssi < WEAK_RSSI) -> Cause.WIFI_SIGNAL
            t.wifiLinkMbps != null && rateMedian != null && t.wifiLinkMbps > 0 && t.wifiLinkMbps * 2 <= rateMedian -> Cause.WIFI_RATE
            (t.thermal ?: 0) >= HOT_STATUS -> Cause.HEAT
            else -> Cause.LINE
        }
        counts[cause] = (counts[cause] ?: 0) + 1
        return cause
    }

    /** The cause behind most spikes, when there were enough of them to say. */
    fun mainCause(): Cause? =
        counts.maxByOrNull { it.value }?.takeIf { spikes >= MIN_SPIKES_FOR_VERDICT && it.value * 2 >= spikes }?.key

    private fun <T> ArrayList<T>.addBounded(v: T) {
        add(v)
        if (size > WINDOW) removeAt(0)
    }

    private fun List<Double>.median(): Double? = if (isEmpty()) null else sorted()[size / 2]
    private fun List<Int>.medianInt(): Int? = if (isEmpty()) null else sorted()[size / 2]

    companion object {
        /** About two minutes of two-second ticks: the "normal" moves with the session. */
        const val WINDOW = 60
        const val MIN_TICKS = 5
        const val SPIKE_OVER_MS = 60.0
        const val BG_RX_KBPS = 150
        const val BG_TX_KBPS = 50
        const val RSSI_DROP_DB = 8
        const val WEAK_RSSI = -78
        /** PowerManager.THERMAL_STATUS_SEVERE. */
        const val HOT_STATUS = 3
        const val MIN_SPIKES_FOR_VERDICT = 3
    }
}
