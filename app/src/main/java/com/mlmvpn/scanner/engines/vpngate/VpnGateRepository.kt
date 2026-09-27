package com.mlmvpn.scanner.engines.vpngate

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches the VPN Gate server list.
 *
 * Three tiers, tried in order, so the tab is never empty:
 *   1. the live API,
 *   2. the on-disk copy of the last successful fetch,
 *   3. the CSV bundled in assets.
 *
 * Follows the app's existing repository idiom (singleton + StateFlow, no ViewModel).
 */
class VpnGateRepository private constructor(private val appCtx: Context) {

    enum class Source { LIVE, CACHE, BUNDLED }

    companion object {
        private const val TAG = "VpnGateRepository"
        private const val API_URL = "http://www.vpngate.net/api/iphone/"

        /**
         * Every route to the CSV, tried in order.
         *
         * www.vpngate.net is DNS-poisoned on Iranian ISPs (it resolves to the 10.10.34.35 block
         * page), so the direct fetch rarely succeeds there. One shared proxy used to be the only
         * other route, and it died (HTTP 402) -- which left the refresh button unable to succeed
         * by any path. These are independent relays: one being down does not take the others
         * with it. The upstream stays http://, since proxying the https:// variant fails.
         *
         * Direct is first whenever it works: no third party sees the request. The rest are plain
         * text relays; the CSV is a public list with no credentials in it, so passing it through
         * one leaks nothing about the user beyond the fact that they asked for it.
         */
        private fun fetchRoutes(userRelays: List<String>): List<Pair<String, String>> {
            val enc = java.net.URLEncoder.encode(API_URL, "UTF-8")
            val encHttps = java.net.URLEncoder.encode(
                API_URL.replaceFirst("http://", "https://"), "UTF-8"
            )
            return buildList {
                add("direct" to API_URL)
                // Our own DNS, then a direct connection. No third party sees the request, and it
                // does not depend on anyone's server staying up. Works where the ISP only poisons
                // DNS; not where it also inspects the Host header.
                add("resolved" to "")
                // The user's own Workers, before anything shared. Their account, their quota,
                // nobody else's to exhaust.
                userRelays.forEachIndexed { i, url -> add("relay${i + 1}" to url) }
                // Everything below is shared with every other install and is only a long shot.
                add("allorigins" to "https://api.allorigins.win/raw?url=$enc")
                add("codetabs" to "https://api.codetabs.com/v1/proxy?quest=$enc")
                add("jina" to "https://r.jina.ai/$API_URL")
                add("allorigins-tls" to "https://api.allorigins.win/raw?url=$encHttps")
            }
        }
        private const val ASSET_PATH = "vpngate/default_servers.csv"
        private const val CACHE_DIR = "vpngate"
        private const val CACHE_FILE = "servers.csv"

        @Volatile
        private var instance: VpnGateRepository? = null

        operator fun invoke(context: Context): VpnGateRepository =
            instance ?: synchronized(this) {
                instance ?: VpnGateRepository(context.applicationContext).also { instance = it }
            }
    }

    private val _servers = MutableStateFlow<List<VpnGateServer>>(emptyList())
    val serversFlow: StateFlow<List<VpnGateServer>> = _servers.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loadingFlow: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val errorFlow: StateFlow<String?> = _error.asStateFlow()

    private val _source = MutableStateFlow<Source?>(null)
    val sourceFlow: StateFlow<Source?> = _source.asStateFlow()

    /**
     * When the list on disk was last fetched from VPN Gate, or 0 if it never was.
     *
     * Taken from the cache file's own timestamp rather than a separate record: the file is only
     * ever written after a successful live fetch, so the two cannot drift apart.
     */
    private val _fetchedAt = MutableStateFlow(
        // Seeded from the file rather than left at 0 until something refreshes: the screen shows
        // this the moment it opens, and "unknown" for a list fetched an hour ago is just wrong.
        runCatching { File(File(appCtx.filesDir, CACHE_DIR), CACHE_FILE).lastModified() }
            .getOrDefault(0L)
    )
    val fetchedAtFlow: StateFlow<Long> = _fetchedAt.asStateFlow()

    /**
     * How long a cached list is used without asking the network.
     *
     * VPN Gate rotates its public window constantly, so a week-old list is mostly servers that no
     * longer exist -- past that the app refreshes on its own rather than presenting a catalogue of
     * dead hosts as if it were current. Inside the week, entering the screen costs nothing and the
     * refresh button is there for whoever wants a newer one.
     */
    private val cacheMaxAgeMs = 7L * 24 * 60 * 60 * 1000

    /**
     * How long after a fetch ATTEMPT the app stops trying again on its own.
     *
     * Separate from [cacheMaxAgeMs], which is about how old a good list may be. This one exists
     * because on a network where VPN Gate is blocked the fetch does not fail fast -- it fails
     * after both the direct and the proxied attempt time out. With no cache to fall back on, that
     * whole wait was paid again on every cold start, only to land on the same bundled list. The
     * attempt is recorded whatever its outcome, so a blocked network costs the wait once.
     */
    private val attemptCooldownMs = 6L * 60 * 60 * 1000

    private val prefs by lazy {
        appCtx.getSharedPreferences("vpngate_repo", Context.MODE_PRIVATE)
    }

    private var lastAttemptAt: Long
        get() = prefs.getLong("last_attempt", 0L)
        set(value) { prefs.edit().putLong("last_attempt", value).apply() }

    /**
     * Whether any connected Cloudflare account has the relay deployed.
     *
     * The screen uses this to explain a failed refresh: with no relay there is no route that
     * works from a filtered network, and saying "check your connection" would be wrong.
     */
    private val _hasRelay = MutableStateFlow(false)
    val hasRelayFlow: StateFlow<Boolean> = _hasRelay.asStateFlow()

    /** The bundled list, the last resort after every route has been tried. */
    private suspend fun publishBundled(): Boolean {
        return try {
            val csv = appCtx.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val parsed = VpnGateCsvParser.parse(csv)
            if (parsed.isEmpty()) false else { publish(parsed, Source.BUNDLED); true }
        } catch (e: Exception) {
            Log.w(TAG, "bundled asset read failed", e)
            false
        }
    }

    // Own client on purpose: never touch the one SubscriptionManager built.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val cacheFile: File
        get() = File(File(appCtx.filesDir, CACHE_DIR).apply { mkdirs() }, CACHE_FILE)

    /**
     * Loads the list. When [force] is false and servers are already loaded this is a no-op,
     * so re-entering the tab doesn't re-hit the network.
     */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        VpnGatePool.load(appCtx)
        if (!force && _servers.value.isNotEmpty()) return@withContext
        if (_loading.value) return@withContext

        // ---- the cache is the first choice, not the fallback -----------------------------
        //
        // This used to go to the network on every entry to the screen and fall back to disk only
        // when that failed, so opening the tab meant a wait and a fetch even though a perfectly
        // good list was already sitting there. The cache is read first now; the network is asked
        // only when the user asks (force) or when what is on disk is too old to be worth showing.
        if (!force) {
            val f = cacheFile
            val age = if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
            if (f.exists() && age < cacheMaxAgeMs) {
                try {
                    val parsed = VpnGateCsvParser.parse(f.readText())
                    if (parsed.isNotEmpty()) {
                        _fetchedAt.value = f.lastModified()
                        publish(parsed, Source.CACHE)
                        return@withContext
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "cache read failed, falling through to the network", e)
                }
            }

            // A cache that is past cacheMaxAgeMs but still real: serve it without a network
            // round trip if a fetch was already tried recently, so a blocked operator costs the
            // wait once rather than on every cold start.
            //
            // Deliberately NOT reached when there is no cache. The cooldown exists to avoid
            // repeating a request that just failed, but with nothing on disk the only thing left
            // to show is the bundled asset -- and skipping the fetch there meant the app served
            // a stale built-in list, reported its age as "unknown", and never tried again for six
            // hours. That is the exact case where the fetch matters most.
            if (f.exists() &&
                System.currentTimeMillis() - lastAttemptAt < attemptCooldownMs
            ) {
                try {
                    val parsed = VpnGateCsvParser.parse(f.readText())
                    if (parsed.isNotEmpty()) {
                        _fetchedAt.value = f.lastModified()
                        publish(parsed, Source.CACHE)
                        return@withContext
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stale cache read failed", e)
                }
            }
        }

        _loading.value = true
        _error.value = null
        lastAttemptAt = System.currentTimeMillis()
        try {
            var liveError: String? = null

            // 1) Live API, by every route in turn until one answers with a parseable list.
            //    "resolved" is not a URL: it does its own DNS. See fetchViaResolvedIp.
            val userRelays = runCatching {
                com.mlmvpn.scanner.data.CloudManager(appCtx).relayUrls()
            }.getOrDefault(emptyList())
            _hasRelay.value = userRelays.isNotEmpty()
            val routes = fetchRoutes(userRelays)
            for ((label, url) in routes) {
                try {
                    val csv = if (label == "resolved") fetchViaResolvedIp() else fetch(url)
                    val parsed = VpnGateCsvParser.parse(csv)
                    if (parsed.isNotEmpty()) {
                        // Cache the RAW csv before any consumer sees the parse, and do it
                        // atomically, so a future parser change can't poison the cache.
                        writeCacheAtomically(csv)
                        _fetchedAt.value = System.currentTimeMillis()
                        publish(parsed, Source.LIVE)
                        return@withContext
                    }
                    liveError = "$label: empty list"
                    Log.w(TAG, "live fetch ($label) returned no servers")
                } catch (e: Exception) {
                    liveError = "$label: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "live fetch ($label) failed: $liveError")
                }
            }

            // 2) Disk cache from the last good fetch.
            try {
                val f = cacheFile
                if (f.exists()) {
                    val parsed = VpnGateCsvParser.parse(f.readText())
                    if (parsed.isNotEmpty()) {
                        _fetchedAt.value = f.lastModified()
                        publish(parsed, Source.CACHE)
                        _error.value = liveError
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cache read failed", e)
            }

            // 3) Bundled asset. Stale by construction — the UI labels the source.
            try {
                val csv = appCtx.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                val parsed = VpnGateCsvParser.parse(csv)
                if (parsed.isNotEmpty()) {
                    publish(parsed, Source.BUNDLED)
                    _error.value = liveError
                    return@withContext
                }
            } catch (e: Exception) {
                Log.w(TAG, "bundled asset read failed", e)
            }

            _error.value = liveError ?: "no server list available"
            Log.w(TAG, "all ${routes.size} routes failed; last error: $liveError")
        } finally {
            _loading.value = false
        }
    }

    /** Decodes column 15 into the .ovpn profile text for [server]. */
    fun ovpnTextFor(server: VpnGateServer): String =
        String(Base64.decode(server.configBase64, Base64.DEFAULT), Charsets.UTF_8)

    /** Servers seen for the first time by the most recent successful refresh. */
    private val _newlyDiscovered = MutableStateFlow(0)
    val newlyDiscoveredFlow: StateFlow<Int> = _newlyDiscovered.asStateFlow()

    private suspend fun publish(servers: List<VpnGateServer>, source: Source) {
        _servers.value = servers
        _source.value = source
        // Every refresh folds into the archive. VPN Gate rotates its ~100-server window
        // constantly, so this is the only way the catalogue ever grows past one page.
        _newlyDiscovered.value = VpnGatePool.merge(appCtx, servers)
        Log.d(TAG, "loaded ${servers.size} servers from $source")
    }

    /** Decodes the profile of any server, pooled or live. */
    fun ovpnTextOf(server: VpnGateServer): String = ovpnTextFor(server)

    /**
     * The CSV, fetched by an address this app resolved rather than one the ISP handed back.
     *
     * The direct fetch does not fail because vpngate.net is down -- it answers HTTP 200 in about
     * three seconds from a machine whose DNS is not tampered with. It fails because the operator's
     * resolver returns 10.10.34.36, the block page. So: ask a DoH resolver over HTTPS (which is
     * not interceptable the same way), then connect straight to the address it gives back and put
     * the real hostname in the Host header, which is what the server routes on.
     *
     * This is the only route that involves no third party holding the response, which is why it
     * runs before any relay.
     */
    private fun fetchViaResolvedIp(): String {
        val addresses = resolveOverHttps("www.vpngate.net")
        if (addresses.isEmpty()) throw java.io.IOException("DoH returned no address")
        var last: Exception? = null
        for (ip in addresses) {
            try {
                val req = Request.Builder()
                    .url("http://$ip/api/iphone/")
                    // The server is name-based; without this it has no idea which site is wanted.
                    .header("Host", "www.vpngate.net")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) return body
                    throw java.io.IOException("empty body")
                }
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "resolved-ip fetch via $ip failed: ${e.message}")
            }
        }
        throw last ?: java.io.IOException("no address answered")
    }

    /**
     * A records for [host], asked over HTTPS so the answer cannot be rewritten in flight.
     *
     * Two resolvers, because one of them is itself blocked on some networks. The addresses in the
     * block range are dropped: a DoH resolver will not return them, but a transparent proxy that
     * intercepted the request might, and connecting to the block page would look like a fetch that
     * merely returned the wrong body.
     */
    private fun resolveOverHttps(host: String): List<String> {
        val endpoints = listOf(
            "https://cloudflare-dns.com/dns-query?name=$host&type=A",
            "https://dns.google/resolve?name=$host&type=A",
        )
        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = org.json.JSONObject(resp.body?.string().orEmpty())
                    val answers = json.optJSONArray("Answer") ?: return@use
                    val ips = buildList {
                        for (i in 0 until answers.length()) {
                            val a = answers.optJSONObject(i) ?: continue
                            if (a.optInt("type") != 1) continue
                            val ip = a.optString("data")
                            // 10.10.34.x is the Iranian block page; never a real answer.
                            if (ip.isNotBlank() && !ip.startsWith("10.10.34.")) add(ip)
                        }
                    }
                    if (ips.isNotEmpty()) {
                        Log.d(TAG, "resolved $host -> $ips")
                        return ips
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoH via $url failed: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body.string()
        }
    }

    private fun writeCacheAtomically(csv: String) {
        try {
            val target = cacheFile
            val tmp = File(target.parentFile, "${CACHE_FILE}.tmp")
            tmp.writeText(csv)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed", e)
        }
    }
}
