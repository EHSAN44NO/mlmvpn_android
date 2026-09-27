package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * The on-device part of a boost: keep Wi-Fi out of power save for the session.
 *
 * Wi-Fi power save parks the radio between beacons, and a packet that arrives while it sleeps
 * waits for the next wake-up -- tens of milliseconds, periodically. That is jitter the line never
 * had. A `WIFI_MODE_FULL_HIGH_PERF` lock turns power save off for as long as it is held (it does on
 * API ≤ 33; from 34 the platform ignores it). `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) is stronger
 * but only while THIS app's UI is in front -- WifiLockManager checks for a foreground activity, and
 * a foreground service or overlay does not count -- so while the game is on screen it does nothing.
 * Both are taken; nothing is claimed for either in numbers.
 *
 * The wake lock only keeps measurements running with the screen dimmed; it is capped and released
 * with the session.
 */
class DeviceTuner(context: Context) {

    private val app = context.applicationContext
    private var highPerf: WifiManager.WifiLock? = null
    private var lowLatency: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    /** What was actually applied, for the result card. */
    data class Applied(val wifiPowerSaveOff: Boolean, val lowLatencyRequested: Boolean)

    fun apply(onWifi: Boolean): Applied {
        release()
        var powerSaveOff = false
        var lowLat = false
        if (onWifi) {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                try {
                    @Suppress("DEPRECATION")
                    highPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MLMVPN:GameBoost").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    powerSaveOff = Build.VERSION.SDK_INT <= 33
                } catch (e: Exception) {
                    Log.w(TAG, "high-perf wifi lock failed: ${e.message}")
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        lowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MLMVPN:GameBoostLL").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                        lowLat = true
                    } catch (e: Exception) {
                        Log.w(TAG, "low-latency wifi lock failed: ${e.message}")
                    }
                }
            }
        }
        return Applied(powerSaveOff, lowLat)
    }

    /** A bounded partial wake lock for the measurement itself. */
    fun holdForMeasurement(maxMs: Long = 90_000L) {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake?.let { if (it.isHeld) it.release() }
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MLMVPN:GameBoostProbe").apply {
                setReferenceCounted(false)
                acquire(maxMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed: ${e.message}")
        }
    }

    fun releaseWake() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
    }

    fun release() {
        try { highPerf?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { lowLatency?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        highPerf = null
        lowLatency = null
        releaseWake()
    }

    companion object {
        private const val TAG = "GameBoost"
    }
}
