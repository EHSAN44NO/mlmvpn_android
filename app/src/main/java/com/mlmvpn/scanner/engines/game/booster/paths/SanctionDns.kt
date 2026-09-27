package com.mlmvpn.scanner.engines.game.booster.paths

import android.net.Network
import android.util.Log
import com.mlmvpn.scanner.engines.game.DnsRaceTester
import com.mlmvpn.scanner.engines.game.booster.doctor.HostVerdict
import com.mlmvpn.scanner.engines.game.booster.doctor.SanctionProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Iranian anti-sanction DNS services, as a path for the parts of a game its publisher refuses
 * to Iran -- sign-in, store, updates -- with no setup and no tunnel.
 *
 * How they work: asked for a name on their list, they answer with the address of their own
 * transparent proxy, which forwards the TLS connection from a non-Iranian address. TLS stays end
 * to end (the host's own certificate comes back, and is checked). Anything not on their list
 * resolves normally. Only TCP/TLS goes through the proxy, so they fix sign-in and never touch the
 * match -- which is exactly the split the booster wants.
 *
 * None of them is trusted from a list. The desktop saw Radar resolve 0 of 8 names and one service
 * answer with root-server addresses, and carriers are known to intercept plain DNS on port 53. So
 * every provider is asked for the game's refused hosts on THIS line, each answer is connected to
 * with the host's real name, and a provider counts only for the hosts where the refusal is gone.
 */
object SanctionDns {

    private const val TAG = "SanctionDns"

    data class Provider(val id: String, val nameFa: String, val nameEn: String, val ips: List<String>)

    /** Same services as the desktop's dns-manager.js. The 10.202.x.x ones answer only inside Iran's network. */
    val PROVIDERS: List<Provider> = listOf(
        Provider("shecan", "شکن", "Shecan", listOf("178.22.122.100", "185.51.200.2")),
        Provider("403", "۴۰۳", "403.online", listOf("10.202.10.202", "10.202.10.102")),
        Provider("electro", "الکترو", "Electro", listOf("78.157.42.100", "78.157.42.101")),
        Provider("radar", "رادار", "Radar Game", listOf("10.202.10.10", "10.202.10.11")),
        Provider("begzar", "بگذار", "Begzar", listOf("185.55.226.26", "185.55.225.25")),
    )

    fun byId(id: String?): Provider? = PROVIDERS.firstOrNull { it.id == id }

    /** Which provider opens which host on a line changes over days, not boosts: kept this long. */
    const val TTL_MS = 6 * 60 * 60 * 1000L

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Outcome>>()

    fun cached(networkKey: String, gameId: String): Outcome? {
        if (networkKey.isEmpty()) return null
        val (at, o) = cache["$networkKey|$gameId"] ?: return null
        return o.takeIf { System.currentTimeMillis() - at in 0..TTL_MS }
    }

    /** Only an outcome that found something is kept: a failed round is worth asking again. */
    fun remember(networkKey: String, gameId: String, outcome: Outcome) {
        if (networkKey.isEmpty() || (outcome.pick == null && outcome.notGeo.isEmpty())) return
        cache["$networkKey|$gameId"] = System.currentTimeMillis() to outcome
    }

    fun forget() = cache.clear()

    /** One refused host, asked of one provider. */
    data class HostTrial(
        val host: String,
        val answer: String?,
        val outcome: SanctionProbe.Outcome?,
        val dnsMs: Long? = null,
        val connectMs: Long? = null,
    )

    /** One provider, tried on every refused host through one of its addresses. */
    data class ProviderTrial(val provider: Provider, val resolverIp: String?, val trials: List<HostTrial>) {
        val fixed: List<String> get() = trials.filter { it.outcome == SanctionProbe.Outcome.OPEN }.map { it.host }
        /** How far its proxy is: the slowest connect among the hosts it fixed. */
        val costMs: Long get() = trials.filter { it.outcome == SanctionProbe.Outcome.OPEN }
            .maxOfOrNull { (it.connectMs ?: 0L) + (it.dnsMs ?: 0L) } ?: Long.MAX_VALUE
    }

    data class Pick(val provider: Provider, val resolverIp: String, val fixedHosts: List<String>, val answers: Map<String, String>)

    data class Outcome(
        val pick: Pick?,
        /**
         * Hosts a provider's proxy DID answer for (a different address than the system's) and that
         * still said 403: the refusal is not about the country, so the host is not sanctioned and
         * must not be reported as such.
         */
        val notGeo: Set<String>,
        val trials: List<ProviderTrial>,
    )

    /**
     * The provider that fixes the most refused hosts; the one in use on this line if it is as
     * good as any; otherwise the one whose proxy is nearest. Pure.
     */
    fun rank(trials: List<ProviderTrial>, currentId: String?): ProviderTrial? {
        val useful = trials.filter { it.resolverIp != null && it.fixed.isNotEmpty() }
        if (useful.isEmpty()) return null
        val most = useful.maxOf { it.fixed.size }
        val top = useful.filter { it.fixed.size == most }
        return top.firstOrNull { it.provider.id == currentId } ?: top.minByOrNull { it.costMs }
    }

    /** Pure; see [Outcome.notGeo]. */
    fun notGeo(trials: List<ProviderTrial>, systemIps: Map<String, List<String>>): Set<String> {
        val out = HashSet<String>()
        trials.forEach { t ->
            t.trials.forEach { h ->
                val proxied = h.answer != null && h.answer !in systemIps[h.host].orEmpty()
                if (proxied && h.outcome == SanctionProbe.Outcome.GEO_BLOCKED) out += h.host
            }
        }
        // A host any provider fixed is sanctioned after all, whatever another one said.
        trials.forEach { t -> out -= t.fixed.toSet() }
        return out
    }

    /**
     * Try every provider on [hosts] (the game's refused hosts) from this line. [systemIps] are
     * what the system resolver said for each, to tell a proxied answer from a pass-through.
     */
    suspend fun pick(
        hosts: List<String>,
        systemIps: Map<String, List<String>>,
        network: Network?,
        currentId: String?,
        providers: List<Provider> = PROVIDERS,
    ): Outcome = coroutineScope {
        if (hosts.isEmpty()) return@coroutineScope Outcome(null, emptySet(), emptyList())
        val trials = providers.map { p -> async(Dispatchers.IO) { tryProvider(p, hosts, network) } }.awaitAll()
        trials.forEach { t ->
            Log.i(TAG, "${t.provider.id}@${t.resolverIp}: " + t.trials.joinToString { "${it.host}=${it.answer}/${it.outcome}" })
        }
        val best = rank(trials, currentId)
        val pick = best?.let { b ->
            Pick(b.provider, b.resolverIp!!, b.fixed,
                b.trials.filter { it.outcome == SanctionProbe.Outcome.OPEN && it.answer != null }.associate { it.host to it.answer!! })
        }
        Outcome(pick, notGeo(trials, systemIps), trials)
    }

    private suspend fun tryProvider(p: Provider, hosts: List<String>, network: Network?): ProviderTrial = coroutineScope {
        // The first address that answers at all is the one used; the second is only a fallback.
        var resolverIp: String? = null
        var answers: List<Pair<String, Pair<String, Long>?>> = emptyList()
        for (ip in p.ips) {
            answers = hosts.map { h -> async(Dispatchers.IO) { h to DnsRaceTester.queryA(ip, h, network, timeoutMs = 1500) } }.awaitAll()
            if (answers.any { it.second != null }) { resolverIp = ip; break }
        }
        if (resolverIp == null) return@coroutineScope ProviderTrial(p, null, hosts.map { HostTrial(it, null, null) })
        val trials = answers.map { (host, a) ->
            async(Dispatchers.IO) {
                val ip = a?.first?.takeIf { !HostVerdict.isFakeAnswer(it) }
                if (ip == null) HostTrial(host, a?.first, null, a?.second)
                else {
                    val r = withTimeoutOrNull(4_000) {
                        SanctionProbe.probe(host, ip, network, connectTimeoutMs = 1500, tlsTimeoutMs = 2000)
                    }
                    HostTrial(host, ip, r?.outcome, a.second, r?.connectMs)
                }
            }
        }.awaitAll()
        ProviderTrial(p, resolverIp, trials)
    }
}
