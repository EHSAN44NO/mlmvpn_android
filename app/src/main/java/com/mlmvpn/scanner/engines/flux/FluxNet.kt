package com.mlmvpn.scanner.engines.flux

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.model.Tri
import com.mlmvpn.scanner.engines.flux.core.net.Cloudflare
import com.mlmvpn.scanner.engines.game.booster.memory.NetworkKey
import com.mlmvpn.scanner.engines.game.booster.session.GameNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone's side of the network: which network this is, what it lets through, and how hard FLUX
 * may work on it. Everything here dials the underlying network directly (the app's own sockets are
 * outside the VPN), so a measurement taken while connected still describes the user's real line.
 */
object FluxNet {

    data class Here(val key: String, val network: Network?, val wifi: Boolean, val cellular: Boolean)

    fun current(context: Context): Here {
        val info = runCatching { NetworkKey.current(context) }.getOrNull()
        val picked = runCatching { GameNetwork.pick(context) }.getOrNull()
        return Here(
            key = info?.key?.takeIf { it.isNotEmpty() } ?: "unknown",
            network = picked?.network,
            wifi = picked?.isWifi ?: (info?.isWifi == true),
            cellular = picked?.isCellular ?: (info?.isCellular == true),
        )
    }

    fun online(context: Context): Boolean = runCatching { GameNetwork.pick(context) != null }.getOrDefault(true)

    /** A global IPv6 address and a default v6 route on the underlying network. */
    fun hasV6(context: Context, network: Network?): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(network ?: return false) ?: return false
        val global = lp.linkAddresses.any { la ->
            val a = la.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress &&
                (a.address[0].toInt() and 0xfe) != 0xfc // not ULA
        }
        global && lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
    }.getOrDefault(false)

    /**
     * How many probes at once. Wi-Fi 12, mobile 8, a low-RAM phone or Battery Saver 4: the race
     * must never be what makes a cheap phone stutter or a low battery die.
     */
    fun concurrency(context: Context, here: Here): Int {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return when {
            pm?.isPowerSaveMode == true || am?.isLowRamDevice == true -> 4
            here.wifi -> 12
            else -> 8
        }
    }

    /** Name -> literal per family, through the network's own resolver. Poisoned answers are dropped. */
    suspend fun resolve(network: Network?, host: String): Map<Family, String> = withContext(Dispatchers.IO) {
        val addrs = runCatching { (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList() }.getOrDefault(emptyList())
            .filterNot { poisoned(it) }
        buildMap {
            addrs.firstOrNull { it is Inet4Address }?.hostAddress?.let { put(Family.V4, it) }
            addrs.firstOrNull { it is Inet6Address }?.hostAddress?.substringBefore('%')?.let { put(Family.V6, it) }
        }
    }

    /** Iran's resolvers answer filtered names with the block page's address or a private one. */
    private fun poisoned(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress ||
            a.hostAddress?.startsWith("10.10.34.") == true

    /**
     * The network's verdict on Cloudflare, measured: a TCP connect to three random edges per
     * family. One that answers is enough for "reachable"; all three silent within the deadline
     * means "cut" -- the race then leaves every Cloudflare-fronted candidate out.
     */
    suspend fun measureVerdict(context: Context, here: Here, now: Long): NetVerdict = coroutineScope {
        val v6 = hasV6(context, here.network)
        val timeout = if (here.wifi) 1_500 else 2_000
        suspend fun cf(f: Family): Tri {
            val edges = Cloudflare.sampleEdges(f, 3)
            val ok = edges.map { ip -> async(Dispatchers.IO) { tcp(here.network, ip, 443, timeout) != null } }.awaitAll()
            return Tri.of(ok.any { it })
        }
        val c4 = async { cf(Family.V4) }
        val c6 = async { if (v6) cf(Family.V6) else Tri.NO }
        NetVerdict(cfV4 = c4.await(), cfV6 = c6.await(), v6 = Tri.of(v6), at = now)
    }

    /** One TCP connect on the underlying network; the time it took, or null. */
    fun tcp(network: Network?, ip: String, port: Int, timeoutMs: Int): Long? = runCatching {
        val start = System.nanoTime()
        (network?.socketFactory?.createSocket() ?: Socket()).use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
        }
        (System.nanoTime() - start) / 1_000_000
    }.getOrNull()
}
