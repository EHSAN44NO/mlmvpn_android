package com.mlmvpn.scanner.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Whether a scan can start, and under what conditions.
 *
 * A scan measures the line between this phone and Cloudflare's edge. Three things quietly ruin
 * that measurement, and none of them announces itself:
 *
 *  - **A VPN.** Traffic goes through a tunnel, so the numbers describe the tunnel rather than
 *    the line. Our own tunnel is the exception: [com.mlmvpn.scanner.MyVpnService] excludes this
 *    app from it (`addDisallowedApplication`), so a scan run under our own VPN already measures
 *    the real line. Another app's VPN has no such courtesy and cannot be turned off from here --
 *    but its underlying network can still be reached directly, see [underlyingNetwork].
 *  - **A connection that exists but carries nothing.** Mobile data switched on with no quota
 *    left, or Wi-Fi associated with a router that has no uplink. Android still reports a network
 *    with INTERNET capability in both cases, so asking the system is not enough -- the only
 *    honest test is to move a byte.
 *  - **A captive portal.** The hotel/airport splash page. Every HTTP request "succeeds", which is
 *    exactly why an HTTP-based reachability check is the wrong tool here.
 *
 * All three were previously invisible: the scan simply ran and produced a plausible-looking page
 * of wrong results, or marked every address dead in a few seconds.
 */
object ScanPreflight {

    private const val TAG = "ScanPreflight"

    /** What the caller has to deal with before a scan is worth running. */
    sealed class Verdict {
        /** Nothing in the way. */
        object Ready : Verdict()

        /** No usable network at all -- flight mode, no signal, Wi-Fi off. */
        object NoNetwork : Verdict()

        /**
         * A network is connected but nothing gets through. Data with no quota left, or a router
         * with no uplink. The distinction matters because the fix is different, and because the
         * phone's own indicator says "connected" in both cases.
         */
        data class NoInternet(val onWifi: Boolean) : Verdict()

        /**
         * A sign-in page stands between this device and the internet. Not the same problem as
         * "no data": the link works, something is intercepting it, and the fix is a browser.
         */
        object CaptivePortal : Verdict()

        /**
         * One of this app's own tunnels is up. Scanning is still accurate -- this app is outside
         * its own tunnel -- so this is a choice, not an obstacle. [engine] names which one, so the
         * dialog can say what it is about to switch off.
         */
        data class OwnVpn(val engine: String) : Verdict()

        /**
         * Another app's VPN is up. Its tunnel carries our traffic too. It cannot be switched off
         * from here; only its own app or Settings can. [canUseRealLine] says whether the TCP sweep
         * can at least be pinned to the underlying network, which is what makes "scan anyway"
         * worth offering rather than merely permitting.
         */
        data class ForeignVpn(val canUseRealLine: Boolean) : Verdict()
    }

    private const val PROBE_TIMEOUT_MS = 4000

    /**
     * Reachability, in this order: is there a network, does it carry data, is there a tunnel.
     *
     * The data probe comes before the VPN question on purpose -- "no internet" is the more
     * urgent problem and the more confusing one, and answering it first means the VPN prompt
     * never appears on a phone that could not have scanned anyway.
     */
    suspend fun check(context: Context): Verdict = withContext(Dispatchers.IO) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext Verdict.Ready          // fail open; never block on our own check

        val active = cm.activeNetwork ?: return@withContext Verdict.NoNetwork
        val caps = cm.getNetworkCapabilities(active) ?: return@withContext Verdict.NoNetwork
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return@withContext Verdict.NoNetwork
        }

        // The system's own verdict, when it has one. CAPTIVE_PORTAL is only ever set after
        // Android's probe actually hit a splash page, so it is a fact rather than a guess.
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return@withContext Verdict.CaptivePortal
        }

        val onVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        if (!carriesData()) {
            // Under a foreign VPN a failed probe may be the tunnel's fault rather than the line's,
            // so ask the real line before blaming the user's data plan.
            if (onVpn) {
                val real = underlyingNetwork(context)
                if (real != null && carriesData(real)) {
                    return@withContext vpnVerdict(context, realLineWorks = true)
                }
            }
            return@withContext Verdict.NoInternet(
                onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    (onVpn && underlyingIsWifi(cm))
            )
        }

        if (onVpn) {
            return@withContext vpnVerdict(context, realLineWorks = true)
        }

        Verdict.Ready
    }

    /** Ours or theirs, and for theirs, whether the real line is reachable. */
    private suspend fun vpnVerdict(context: Context, realLineWorks: Boolean): Verdict {
        val ours = ownEngine()
        if (ours != null) return Verdict.OwnVpn(ours)
        val real = underlyingNetwork(context)
        return Verdict.ForeignVpn(canUseRealLine = realLineWorks && real != null)
    }

    /** Whether the network under a VPN is Wi-Fi, for a message that names the right thing. */
    private fun underlyingIsWifi(cm: ConnectivityManager): Boolean = try {
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Does a byte actually move?
     *
     * A TCP handshake to two well-known addresses, on a short timeout. Deliberately not an HTTP
     * request: a captive portal answers HTTP happily and would read as working internet, while a
     * handshake to a random public address on 443 is refused or times out behind one.
     *
     * With [network] given, the handshake is pinned to that interface -- which is how the same
     * check answers "does the real line work" while somebody else's tunnel holds the default route.
     */
    fun carriesData(network: Network? = null): Boolean {
        for ((host, port) in listOf("1.1.1.1" to 443, "8.8.8.8" to 443)) {
            var s: Socket? = null
            try {
                s = network?.socketFactory?.createSocket() ?: Socket()
                s.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                return true
            } catch (e: Exception) {
                // Try the next one; one blocked resolver or address proves nothing.
            } finally {
                try { s?.close() } catch (e: Exception) {}
            }
        }
        return false
    }

    /** Which of this app's own engines holds the tunnel, or null if none does. */
    fun ownEngine(): String? {
        // Both services, not one. MyVpnService is the Xray family (nodes, Quick Connect, Aether,
        // VPN Gate, the game booster); TunnelStatus covers the five-transport stack, whose data
        // path is tun2socks and therefore invisible to TunnelEngine.isRunning() alone. Asking only
        // the first made a running transport read as SOMEBODY ELSE'S VPN, and the dialog then told
        // the user to go to Settings to switch off a tunnel this app had started.
        if (com.mlmvpn.scanner.MyVpnService.isRunning) {
            return com.mlmvpn.scanner.utils.S(com.mlmvpn.scanner.R.string.current_config)
        }
        if (com.mlmvpn.core.tunnel.TunnelStatus.isActive()) {
            val active = com.mlmvpn.scanner.ui.tunnel.TunnelController.state.value.active
            return active?.label
                ?: com.mlmvpn.scanner.utils.S(com.mlmvpn.scanner.R.string.current_tunnel)
        }
        return null
    }

    /**
     * A network that is NOT the VPN, for probing the real line while someone else's tunnel is up.
     *
     * Only the TCP sweep can use it: the verification stage runs through an Xray instance whose
     * sockets this cannot reach, so those numbers still describe whatever tunnel is in the way.
     * The screen says so rather than implying otherwise.
     *
     * `registerNetworkCallback`, not `requestNetwork`. The two look interchangeable and are not:
     * `requestNetwork` asks the system to BRING UP a matching network and needs CHANGE_NETWORK_STATE
     * (which this app does not hold, and which is not grantable to a normal app on modern Android),
     * while `registerNetworkCallback` merely observes ones that already exist and needs only
     * ACCESS_NETWORK_STATE. Under a VPN the underlying network always already exists -- the tunnel
     * is running over it -- so observing is both sufficient and the only form that works here.
     *
     * The callback is unregistered on every exit, including timeout and cancellation. It was not,
     * and Android caps an app at 100 live callbacks before it throws.
     */
    suspend fun underlyingNetwork(context: Context, timeoutMs: Long = 3000): Network? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null

        // The cheap answer first: if a matching network is already known, no callback is needed.
        try {
            cm.allNetworks.forEach { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@forEach
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    return n
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate networks", e)
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network?> { cont ->
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!done.compareAndSet(false, true)) return
                        try { cm.unregisterNetworkCallback(this) } catch (e: Exception) {}
                        if (cont.isActive) cont.resumeWith(Result.success(network))
                    }
                    override fun onUnavailable() {
                        if (!done.compareAndSet(false, true)) return
                        try { cm.unregisterNetworkCallback(this) } catch (e: Exception) {}
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                }
                // Cancellation and timeout both land here, which is the only reason the callback
                // does not leak when no non-VPN network ever appears.
                cont.invokeOnCancellation {
                    if (done.compareAndSet(false, true)) {
                        try { cm.unregisterNetworkCallback(cb) } catch (e: Exception) {}
                    }
                }
                try {
                    cm.registerNetworkCallback(request, cb)
                } catch (e: Exception) {
                    if (done.compareAndSet(false, true) && cont.isActive) {
                        cont.resumeWith(Result.success(null))
                    }
                }
            }
        }
    }
}
