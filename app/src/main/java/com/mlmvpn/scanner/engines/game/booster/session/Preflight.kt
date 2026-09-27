package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mlmvpn.scanner.R

/**
 * The phone-side things that ruin a game's ping and that no route can fix, checked before a boost.
 * Advisory only: each tip says what is wrong and, where Android allows it, opens the setting.
 */
object Preflight {

    enum class Severity { WARN, BAD }

    data class Tip(
        val id: String,
        val severity: Severity,
        @androidx.annotation.StringRes val titleRes: Int,
        @androidx.annotation.StringRes val detailRes: Int,
        /** A settings screen that fixes it, or null where only the user can. */
        val action: String? = null,
        /** Format arguments of [detailRes]. */
        val detailArgs: List<Any> = emptyList(),
    )

    /** Traffic of every other app, measured while the booster measured: KB/s each way. */
    data class Load(val rxKBps: Int, val txKBps: Int) {
        /** Enough to fill an uplink's queue and push a game's packets behind it. */
        val heavy: Boolean get() = rxKBps >= 150 || txKBps >= 50
    }

    /**
     * How much the REST of the phone is sending and receiving right now -- app updates, cloud
     * backup, a messenger's media -- over [windowMs]. That traffic sits in the same queues as the
     * game's packets and is the most common cause of lag the player makes themself.
     *
     * No permission: the device totals minus this app's own uid (whose probes run meanwhile).
     * Null where the kernel does not report totals.
     */
    suspend fun backgroundLoad(windowMs: Long = 2000): Load? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val uid = android.os.Process.myUid()
        fun snap(): LongArray? {
            val v = longArrayOf(
                android.net.TrafficStats.getTotalRxBytes(), android.net.TrafficStats.getTotalTxBytes(),
                android.net.TrafficStats.getUidRxBytes(uid), android.net.TrafficStats.getUidTxBytes(uid),
            )
            return if (v.any { it == android.net.TrafficStats.UNSUPPORTED.toLong() }) null else v
        }
        val a = snap() ?: return@withContext null
        kotlinx.coroutines.delay(windowMs)
        val b = snap() ?: return@withContext null
        val rx = ((b[0] - a[0]) - (b[2] - a[2])).coerceAtLeast(0)
        val tx = ((b[1] - a[1]) - (b[3] - a[3])).coerceAtLeast(0)
        Load((rx * 1000 / windowMs / 1024).toInt(), (tx * 1000 / windowMs / 1024).toInt())
    }

    /** The tip for [load], or null when the rest of the phone is quiet. */
    fun loadTip(load: Load?, onCellular: Boolean): Tip? {
        if (load == null || !load.heavy) return null
        return if (onCellular) {
            // On mobile data Android's Data Saver is a free "focus mode": it stops background apps
            // and leaves the app in front -- the game -- alone.
            Tip("bg_load", Severity.BAD, R.string.gb_tip_bg_load_title, R.string.gb_tip_bg_load_cell_detail,
                Settings.ACTION_DATA_USAGE_SETTINGS, listOf(load.rxKBps, load.txKBps))
        } else {
            Tip("bg_load", Severity.BAD, R.string.gb_tip_bg_load_title, R.string.gb_tip_bg_load_wifi_detail,
                null, listOf(load.rxKBps, load.txKBps))
        }
    }

    /** Most damaging first, so the card's three slots go to what matters most. */
    fun ranked(tips: List<Tip>): List<Tip> = tips.sortedWith(
        compareBy<Tip> { if (it.severity == Severity.BAD) 0 else 1 }.thenBy { ORDER.indexOf(it.id).let { i -> if (i < 0) ORDER.size else i } },
    )

    private val ORDER = listOf(
        "foreign_vpn", "bg_load", "hot", "bt_24", "wifi_scan", "wifi_weak", "wifi_24", "battery_saver", "private_dns",
    )

    data class Snapshot(
        val onWifi: Boolean,
        val onCellular: Boolean,
        val foreignVpn: Boolean,
        val ownVpn: Boolean,
        val tips: List<Tip>,
    )

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tips = mutableListOf<Tip>()

        // The network the game will leave by (not merely one that exists: mobile data often stays
        // up behind Wi-Fi), and whether a VPN (ours or someone else's) is up.
        val picked = GameNetwork.pick(app)
        val onWifi = picked?.isWifi == true
        val onCell = picked?.isCellular == true
        var vpnUp = false
        try {
            cm?.allNetworks?.forEach { n ->
                if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) vpnUp = true
            }
        } catch (_: Exception) {
        }
        // No tip for this app's own connection: the boost itself replaces or stops it (the screen
        // asks first), so by the time the card shows, a tip about it would already be untrue.
        val ownVpn = com.mlmvpn.scanner.utils.ScanPreflight.ownEngine() != null
        val foreignVpn = vpnUp && !ownVpn

        if (foreignVpn) {
            tips += Tip("foreign_vpn", Severity.BAD, R.string.gb_tip_foreign_vpn_title, R.string.gb_tip_foreign_vpn_detail, Settings.ACTION_VPN_SETTINGS)
        }

        if (onWifi) {
            @Suppress("DEPRECATION")
            val info = try { (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo } catch (_: Exception) { null }
            if (info != null) {
                if (info.frequency in 2400..2500) {
                    tips += Tip("wifi_24", Severity.WARN, R.string.gb_tip_wifi_24_title, R.string.gb_tip_wifi_24_detail)
                }
                if (info.rssi != 0 && info.rssi < -70) {
                    tips += Tip("wifi_weak", Severity.WARN, R.string.gb_tip_wifi_weak_title, R.string.gb_tip_wifi_weak_detail)
                }
                // Bluetooth and 2.4 GHz Wi-Fi share one radio on most phones: headphones playing
                // audio take airtime from the game's packets, which shows as jitter and loss.
                if (info.frequency in 2400..2500 && bluetoothAudioOn(app)) {
                    tips += Tip("bt_24", Severity.WARN, R.string.gb_tip_bt_24_title, R.string.gb_tip_bt_24_detail)
                }
            }
            // Wi-Fi scanning for location keeps the radio hopping channels every couple of
            // minutes -- a periodic ping spike every game notices. Apps cannot turn it off.
            val scanAlways = try {
                Settings.Global.getInt(app.contentResolver, "wifi_scan_always_enabled", 0) == 1
            } catch (_: Exception) { false }
            if (scanAlways) {
                tips += Tip("wifi_scan", Severity.WARN, R.string.gb_tip_wifi_scan_title, R.string.gb_tip_wifi_scan_detail, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
        }

        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isPowerSaveMode == true) {
            tips += Tip("battery_saver", Severity.WARN, R.string.gb_tip_battery_saver_title, R.string.gb_tip_battery_saver_detail, Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val thermal = try { pm?.currentThermalStatus ?: 0 } catch (_: Exception) { 0 }
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                tips += Tip("hot", Severity.BAD, R.string.gb_tip_hot_title, R.string.gb_tip_hot_detail)
            }
        }
        // Data Saver used to be a warning here. It is the opposite: on mobile data it holds back
        // background apps and leaves the game in front alone, so it is suggested (with the
        // background-load tip) rather than warned about.
        // Strict Private DNS (a hostname set by the user) bypasses any resolver we hand the game.
        if (Build.VERSION.SDK_INT >= 28) {
            val strict = try {
                cm?.activeNetwork?.let { cm.getLinkProperties(it)?.privateDnsServerName } != null
            } catch (_: Exception) { false }
            if (strict) {
                tips += Tip("private_dns", Severity.WARN, R.string.gb_tip_private_dns_title, R.string.gb_tip_private_dns_detail)
            }
        }
        return Snapshot(onWifi, onCell, foreignVpn, ownVpn, tips)
    }

    fun intentFor(tip: Tip): Intent? = tip.action?.let { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Headphones or a speaker playing over Bluetooth right now. No permission needed. */
    private fun bluetoothAudioOn(app: Context): Boolean = try {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val bt = mutableSetOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            bt += android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            bt += android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type in bt } == true
    } catch (_: Exception) {
        false
    }
}
