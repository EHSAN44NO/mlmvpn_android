package com.mlmvpn.scanner.engines.game.booster.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.security.MessageDigest

/**
 * Which network the phone is on, as a short digest that means nothing on its own.
 *
 * Ported from NetDoctor's `core/scanner/NetworkKey.kt`, with one deliberate difference: that one
 * gives up under any VPN, because there a VPN always means "the numbers describe the tunnel". Here
 * the booster measures from this app's own uid, which is OUTSIDE this app's own VPN -- so it keys
 * on the network underneath the tunnel, the one whose route to Bahrain it actually measured.
 *
 *  - Mobile: the operator's name (two SIMs of one operator behave alike and share a key).
 *  - Wi-Fi: the network's name if readable, else the gateway and resolvers -- a router's own
 *    fingerprint, needing no permission.
 *  - Hashed: eight bytes of SHA-256, so no SSID or household fingerprint ever reaches disk.
 */
object NetworkKey {

    /** Nothing worth remembering: offline, or no network could be identified. */
    const val UNKNOWN = ""

    data class Info(val key: String, val isWifi: Boolean, val isCellular: Boolean)

    fun current(context: Context): Info {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Info(UNKNOWN, false, false)
        // The same network the booster measures -- see GameNetwork for why not simply the first.
        val picked = com.mlmvpn.scanner.engines.game.booster.session.GameNetwork.pick(app)
            ?: return Info(UNKNOWN, false, false)
        val net = picked.network
        val caps = picked.caps
        val lp = try { cm.getLinkProperties(net) } catch (e: Exception) { null }

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // The SIM that carries DATA. The default TelephonyManager speaks for the default
                // (voice) SIM: on a dual-SIM phone with MCI for calls and Irancell for data, every
                // mobile network looked like MCI, and switching the data SIM went unnoticed.
                val carrier = try {
                    val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    val dataSub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                    val dataTm = if (dataSub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm?.createForSubscriptionId(dataSub) else null
                    dataTm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.networkOperatorName
                } catch (e: Exception) {
                    null
                }
                Info(carrier?.takeIf { it.isNotBlank() }?.let { digest("m", it) } ?: digest("m"), false, true)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                @Suppress("DEPRECATION")
                val wifi = try {
                    (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
                } catch (e: Exception) {
                    null
                }
                val ssid = wifi?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                val bssid = wifi?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
                val name = ssid ?: bssid
                val key = when {
                    name != null -> digest("w", name)
                    lp != null -> {
                        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                            ?.gateway?.hostAddress.orEmpty()
                        val dns = lp.dnsServers.mapNotNull { it.hostAddress }.sorted().joinToString(",")
                        if (gw.isEmpty() && dns.isEmpty()) digest("w") else digest("w", gw, dns)
                    }
                    else -> digest("w")
                }
                Info(key, true, false)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Info(digest("e"), false, false)
            else -> Info(digest("o"), false, false)
        }
    }

    private fun digest(vararg parts: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(parts.joinToString(" ").toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }
}
