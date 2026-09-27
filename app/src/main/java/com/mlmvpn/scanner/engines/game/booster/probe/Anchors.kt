package com.mlmvpn.scanner.engines.game.booster.probe

import android.net.Network
import com.mlmvpn.scanner.engines.game.DnsRaceTester
import com.mlmvpn.scanner.engines.game.booster.doctor.HostVerdict
import com.mlmvpn.scanner.engines.game.booster.model.RegionCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress

/** Turning a region's anchor names into addresses that answer. */
object Anchors {

    /**
     * [host] as an IPv4 address: the system resolver first (bounded -- it can hang for tens of
     * seconds on a bad line -- and never a filtering answer), then DNS over HTTPS by IP address,
     * which passes where the named resolvers are filtered.
     */
    suspend fun resolve(host: String): InetAddress? = coroutineScope {
        val sys = async(Dispatchers.IO) { resolveV4(host) }
        withTimeoutOrNull(2_000) { sys.await() }
            ?.firstOrNull { a -> a.hostAddress?.let { !HostVerdict.isFakeAnswer(it) && !HostVerdict.isPrivate(it) } == true }
            ?.let { return@coroutineScope it }
        val doh = withTimeoutOrNull(3_000) { DnsRaceTester.resolveViaDohByIp(host) }.orEmpty()
        doh.firstOrNull { !HostVerdict.isFakeAnswer(it) && !HostVerdict.isPrivate(it) }?.let {
            try { InetAddress.getByName(it) } catch (e: Exception) { null }
        }
    }

    /** The region's UDP echo beacon, resolved, or null where it has none or it does not resolve. */
    suspend fun echoTarget(region: RegionCatalog.Region): InetSocketAddress? =
        region.echo.firstNotNullOfOrNull { e -> resolve(e.host)?.let { InetSocketAddress(it, e.port) } }

    /**
     * The fastest of the region's TCP endpoints that answers one handshake. Picking by answer, not
     * by list order, is what keeps one dead name from making a live region read as dark.
     */
    suspend fun pickTcp(region: RegionCatalog.Region, network: Network?, timeoutMs: Int = 1500): InetSocketAddress? = coroutineScope {
        region.tcp.map { t ->
            async {
                val ip = resolve(t.host) ?: return@async null
                val target = InetSocketAddress(ip, t.port)
                val r = tcpTrain(target, count = 1, timeoutMs = timeoutMs, network = network)
                r.stats.min?.let { target to it }
            }
        }.awaitAll().filterNotNull().minByOrNull { it.second }?.first
    }
}
