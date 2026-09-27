package com.mlmvpn.scanner.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Watches real device connectivity while a scan is running.
 *
 * Without this, a scanner that loses its network mid-run doesn't actually notice: every probe
 * (TCP connect / UDP send) fails almost instantly with "network unreachable" instead of waiting
 * out its real timeout, so the scan appears to speed up dramatically while it's really just
 * burning through IPs marking every single one "dead" -- a completely fake result, not a real
 * scan. This class gives scanners a real signal to pause on and resume from automatically.
 *
 * Two detectors, because the two failures look nothing alike:
 *
 *  - **The link went away** -- flight mode, Wi-Fi off, out of range. The OS says so immediately
 *    and [currentlyOnline] catches it.
 *  - **The link is still there and carries nothing** -- the data plan ran out mid-scan, or the
 *    router lost its uplink. The OS keeps reporting a connected network with INTERNET capability,
 *    so nothing in the callback API ever fires. Only moving a byte can tell, and moving a byte on
 *    a timer would be both wasteful and unreliable under scan load. So it is moved on evidence
 *    instead: the scanner reports each probe outcome here, and a long enough run of failures with
 *    no success at all is what triggers the real check. See [noteProbe].
 */
class NetworkWatchdog(context: Context) {

    companion object {
        private const val TAG = "NetworkWatchdog"

        /**
         * Consecutive failed probes with no success before the link itself becomes the suspect.
         *
         * Generous on purpose. Scanning random Cloudflare addresses on a healthy line produces
         * long failure runs all by itself -- most addresses in a /13 answer nothing -- so a small
         * number here would pause a perfectly good scan. What no healthy line ever produces is
         * this many in a row with not one success anywhere among them.
         */
        private const val FAILURE_STREAK = 400

        /** And the run has to have lasted this long, so a fast burst of refusals is not enough. */
        private const val QUIET_MS = 15_000L

        /** How often the loop re-checks while paused for lack of data. */
        private const val RECHECK_MS = 5_000L
    }

    /** Why the scan is paused, so the banner can say something the user can act on. */
    enum class Reason {
        /** No network at all: flight mode, Wi-Fi off, no signal. */
        NO_NETWORK,

        /** A network is connected but no byte moves. Data plan spent, or a router with no uplink. */
        NO_DATA_WIFI,

        /** The same, on mobile data -- almost always a spent quota. */
        NO_DATA_MOBILE,
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline

    private val _reason = MutableStateFlow<Reason?>(null)

    /** Why [isOnline] is false, or null while it is true. */
    val reason: StateFlow<Reason?> = _reason

    private var callback: ConnectivityManager.NetworkCallback? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dataWatcher: Job? = null

    private val failuresSinceSuccess = AtomicInteger(0)
    private val lastSuccessAt = AtomicLong(System.currentTimeMillis())

    /** True while the data probe -- not the OS -- is what is holding the scan. */
    @Volatile
    private var dataStarved = false

    fun start() {
        if (connectivityManager == null) return
        if (callback == null) {
            _isOnline.value = currentlyOnline()
            _reason.value = if (_isOnline.value) null else Reason.NO_NETWORK
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshFromSystem()
                    Log.d(TAG, "Network available -- online=${_isOnline.value}")
                }
                override fun onLost(network: Network) {
                    refreshFromSystem()
                    Log.w(TAG, "Network lost -- online=${_isOnline.value}")
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    refreshFromSystem()
                }
            }
            try {
                connectivityManager.registerNetworkCallback(request, cb)
                callback = cb
            } catch (e: Exception) {
                Log.w(TAG, "Could not register network callback, assuming online", e)
                _isOnline.value = true
                _reason.value = null
            }
        }
        if (dataWatcher == null) {
            failuresSinceSuccess.set(0)
            lastSuccessAt.set(System.currentTimeMillis())
            dataWatcher = scope.launch { watchForDataStarvation() }
        }
    }

    fun stop() {
        try {
            callback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Already unregistered or manager gone -- harmless.
        }
        callback = null
        dataWatcher?.cancel()
        dataWatcher = null
        dataStarved = false
    }

    /**
     * One probe's outcome, from the scanner.
     *
     * Cheap by design -- two atomics, called once per probed address -- because it is on the hot
     * path of a scan that probes thousands. The expensive part (an actual handshake to a known
     * address) happens on the watcher coroutine, and only once the evidence justifies it.
     */
    fun noteProbe(alive: Boolean) {
        if (alive) {
            failuresSinceSuccess.set(0)
            lastSuccessAt.set(System.currentTimeMillis())
        } else {
            failuresSinceSuccess.incrementAndGet()
        }
    }

    /**
     * The evidence-driven half.
     *
     * While the OS says the link is fine, this only wakes to look at two counters. It reaches for
     * the network exactly when nothing has answered for a long time -- and once it has decided the
     * line is dead, it keeps checking on a slow timer until a byte moves again, which is what lets
     * the scan resume by itself when the user tops up their data or the router comes back.
     */
    private suspend fun watchForDataStarvation() {
        while (true) {
            delay(RECHECK_MS)

            if (dataStarved) {
                if (ScanPreflight.carriesData()) {
                    Log.d(TAG, "Data is back")
                    dataStarved = false
                    failuresSinceSuccess.set(0)
                    lastSuccessAt.set(System.currentTimeMillis())
                    refreshFromSystem()
                }
                continue
            }

            // The OS already saying "offline" is a different (and already-handled) problem.
            if (!currentlyOnline()) continue

            val streak = failuresSinceSuccess.get()
            val quietFor = System.currentTimeMillis() - lastSuccessAt.get()
            if (streak < FAILURE_STREAK || quietFor < QUIET_MS) continue

            // Nothing has answered for a long time on a link the OS still calls connected. That is
            // the exact shape of a spent data plan, so now it is worth paying for a real handshake.
            if (!ScanPreflight.carriesData()) {
                Log.w(TAG, "Link is up but carries nothing (streak=$streak) -- pausing")
                dataStarved = true
                _reason.value = if (isOnWifi()) Reason.NO_DATA_WIFI else Reason.NO_DATA_MOBILE
                _isOnline.value = false
            } else {
                // It does carry data; the run of failures was just a cold patch of address space.
                failuresSinceSuccess.set(0)
                lastSuccessAt.set(System.currentTimeMillis())
            }
        }
    }

    private fun refreshFromSystem() {
        if (dataStarved) return       // the data probe owns the verdict until it clears
        val online = currentlyOnline()
        _isOnline.value = online
        _reason.value = if (online) null else Reason.NO_NETWORK
    }

    private fun isOnWifi(): Boolean = try {
        val cm = connectivityManager
        val network = cm?.activeNetwork
        val caps = if (cm == null || network == null) null else cm.getNetworkCapabilities(network)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) {
        false
    }

    private fun currentlyOnline(): Boolean {
        return try {
            val network = connectivityManager?.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            // Only require that a network with a route to the internet exists (NET_CAPABILITY_INTERNET),
            // NOT that it's currently VALIDATED. A heavy scan floods the link, so Android's own
            // validation probe (to gstatic) times out and the Wi-Fi flips to "connected, no internet"
            // (the "!" icon) even though the link still carries our scan traffic fine. Requiring
            // VALIDATED made the watchdog read that as "offline" and pause the scan at ~20% on weaker
            // routers/uplinks. The watchdog's real job is to catch a truly LOST network (no route →
            // instant "network unreachable" fails that would fake-mark IPs dead); that case has no
            // active network / no INTERNET capability and is still caught here. The subtler case --
            // a link that is present and carries nothing -- is what watchForDataStarvation covers.
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true // fail-open: never block scanning just because the check itself errored
        }
    }

    /** Suspends (polling) until the device is back online. */
    suspend fun waitUntilOnline() {
        while (!_isOnline.value) {
            delay(1000)
        }
    }
}
