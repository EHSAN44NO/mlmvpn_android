package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * The physical network the game's own traffic leaves by -- the one every number is about.
 *
 * Not "the first non-VPN network with internet". With Wi-Fi up, many phones keep mobile data
 * connected as a standby, and `allNetworks` lists networks in no promised order, so that rule
 * could measure, remember and tune for mobile data while the game played on Wi-Fi.
 *
 * Android sends an app's traffic over its default network. This app sits outside its own tunnel,
 * so its `activeNetwork` is normally the phone's default physical network already. Where it is
 * a VPN instead (a foreign one, or this app's DNS-only game route, which includes the app), the
 * choice Android itself makes is copied: validated before unvalidated, then Wi-Fi or Ethernet
 * before mobile data.
 */
object GameNetwork {

    data class Picked(val network: Network, val caps: NetworkCapabilities) {
        val isWifi: Boolean get() = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular: Boolean get() = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun pick(context: Context): Picked? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return try {
            cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.takeIf { it.isPhysicalInternet() }?.let { Picked(n, it) } }
                ?: cm.allNetworks
                    .mapNotNull { n -> cm.getNetworkCapabilities(n)?.takeIf { it.isPhysicalInternet() }?.let { Picked(n, it) } }
                    .sortedWith(
                        compareByDescending<Picked> { it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                            .thenByDescending { it.isWifi || it.caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
                    )
                    .firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Mobile data that is up behind Wi-Fi, if the phone keeps it up (many do, as a standby) --
     * for comparing the two lines without waking the radio or spending anyone's data plan.
     */
    fun standbyCellular(context: Context): Network? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.isPhysicalInternet() && it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } == true
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun NetworkCapabilities.isPhysicalInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}
