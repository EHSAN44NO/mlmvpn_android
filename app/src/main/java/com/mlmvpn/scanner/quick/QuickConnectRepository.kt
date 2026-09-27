package com.mlmvpn.scanner.quick

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mlmvpn.scanner.engines.freeconfig.FreeConfigEngine
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * «اتصال سریع» -- a ready-to-use server pool with a country picker and one-tap connect.
 *
 * What this module owns:
 *   - fetching and caching public share-link feeds, merged and de-duplicated
 *   - giving every node a country (see [GeoLabel]) so the UI can group by flag
 *
 * What it does not own: the connection itself. Starting Xray and the tunnel already have one
 * correct implementation in MyVpnService, reached the same way the nodes tab reaches it.
 * Re-implementing it here would mean two code paths that can disagree about whether the phone
 * is protected, which is the one bug class this app cannot afford.
 *
 * Port of the desktop app's `quick-connect.js`.
 */

/** One runnable, country-tagged entry. Mutable because a measured country overwrites a claimed one. */
data class QuickNode(
    val id: String,
    var uri: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val tag: String,
    var name: String,
    var country: String?,
    var flag: String?,
    var countryName: String?,
    val source: String,
    /** What the feed claimed, kept for reference once a measurement overrides it. */
    var claimedCountry: String? = null,
    var verified: Boolean = false,
    /** Filled in by a scan; -1 means untested. */
    var tcp: Int = -1,
    var delay: Int = -1,
) {
    /** Overwrite this node's country with a measured one, keeping the feed's claim for reference. */
    fun applyVerified(code: String) {
        val measured = GeoLabel.countryFromCode(code) ?: return
        // Only the FIRST override records a claim; re-applying the same reading on every load
        // must not overwrite what the feed originally said with what we already corrected it to.
        if (!verified) claimedCountry = country
        country = measured.code
        flag = measured.flag
        countryName = measured.name
        // The prefix is part of the label, so leaving it alone would file a server under Italy
        // while still calling it FR-838. The tag is the stable half and does not move.
        name = QuickConnectRepository.composeName(measured.code, tag)
        verified = true
    }
}

data class QuickCountryRow(val code: String, val flag: String, val name: String, val count: Int)

data class QuickSourceStatus(
    val id: String,
    val title: String,
    val ok: Boolean,
    val usable: Int,
    val unusable: Int = 0,
    val duplicate: Int = 0,
    val error: String? = null,
)

data class QuickCatalog(
    val total: Int,
    val countries: List<QuickCountryRow>,
    val unknown: Int,
    val verified: Int,
    val sources: List<QuickSourceStatus>,
    val updatedAt: Long,
    val stale: Boolean,
    val error: String?,
)

object QuickConnectRepository {

    private const val TAG = "QuickConnect"

    private data class Source(
        val id: String,
        val title: String,
        val url: String? = null,
        val mirror: String? = null,
        /** When set, entries come from [FreeConfigEngine] instead of a URL of our own. */
        val pool: Boolean = false,
        /**
         * When set, entries come from the MLMVPN shared pool.
         *
         * It cannot be a plain URL like the others: the request has to be signed with this
         * install's enrolment, and what comes back is scoped to the caller's own network.
         */
        val shared: Boolean = false,
    )

    // Several feeds, on purpose: any single one being down or filtered still leaves a usable
    // list, and they overlap heavily so the de-duplication below is doing real work rather
    // than just concatenating.
    //
    // The four `0xRadikal` pools come from an aggregator that merges ~21 public sources every
    // 15 minutes and publishes them pre-sorted by how well they did in its own testing. Its
    // labels are worth surfacing verbatim: someone connecting from the "verified" pool starts
    // from configs that passed a real proxied request minutes ago, so a sweep finds working
    // nodes far sooner than it would from the raw set. They are listed best-first because
    // de-duplication keeps the FIRST occurrence, so a server present in both `verified` and
    // `all` is filed under the better label.
    //
    // Measured 2026-08-30: whitedns 410, verified 1494, fast 1079, secure 997, all 10024.
    // `all` is included despite being untested because it is where the breadth is, and the
    // two-stage sweep is what separates the living from the dead anyway.
    private const val RADIKAL = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main"
    private const val RADIKAL_MIRROR = "https://cdn.jsdelivr.net/gh/0xRadikal/Free-v2ray-Configs@main"

    private val SOURCES = listOf(
        // First on purpose. De-duplication keeps the FIRST occurrence, so a server that is both
        // in a public feed and in the pool is filed under the pool -- which is the label that
        // actually says something about whether it works.
        Source(
            id = MlmPoolClient.SOURCE,
            title = S(R.string.mlmvpn_private_list_2),
            shared = true,
        ),
        Source(
            id = "global",
            title = S(R.string.global_pool),
            url = "https://raw.githubusercontent.com/iampedii/whitedns-sub/refs/heads/main/base64.txt",
            mirror = "https://cdn.jsdelivr.net/gh/iampedii/whitedns-sub@main/base64.txt",
        ),
        Source(id = "verified", title = S(R.string.verified), url = "$RADIKAL/verified/configs.txt", mirror = "$RADIKAL_MIRROR/verified/configs.txt"),
        Source(id = "fast", title = S(R.string.fast), url = "$RADIKAL/fast/configs.txt", mirror = "$RADIKAL_MIRROR/fast/configs.txt"),
        Source(id = "secure", title = S(R.string.secure), url = "$RADIKAL/secure/configs.txt", mirror = "$RADIKAL_MIRROR/secure/configs.txt"),
        Source(id = "all", title = S(R.string.all_4), url = "$RADIKAL/all/configs.txt", mirror = "$RADIKAL_MIRROR/all/configs.txt"),
        Source(id = "extra", title = S(R.string.extra_sources), pool = true),
    )

    /** How many feeds a refresh has to get through, for the progress readout. */
    val sourceCount: Int get() = SOURCES.size

    /** How often the upstream feeds themselves refresh — shown to the user in the list. */
    val SOURCE_REFRESH_LABEL: String get() = S(R.string.the_sources_refresh_every_15_minutes)

    private const val CACHE_FILE = "quick-servers.json"

    // Bumped whenever the shape of a cached node changes. Without it, an upgrade keeps serving
    // yesterday's file for up to half an hour and the user sees rows built by the old code.
    private const val CACHE_VERSION = 1

    // Matches the refresh cadence of the upstream feeds. Re-downloading megabytes because the
    // user reopened a panel helps nobody, and a list this size does not go stale in minutes.
    private const val TTL_MS = 30L * 60 * 1000

    private const val BRAND = "@mlmvpn"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private class Snapshot(
        val at: Long,
        val nodes: List<QuickNode>,
        val sources: List<QuickSourceStatus>,
        val stale: Boolean = false,
        val error: String? = null,
    )

    private var memory: Snapshot? = null

    /** So two panels opening at once fetch once. */
    private val loadLock = Mutex()

    /**
     * The repository's own scope. A download must not belong to whichever screen happened to
     * ask for it -- see [load].
     */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** The refresh currently in flight, so concurrent callers share one download. */
    private var inFlight: kotlinx.coroutines.Deferred<Snapshot>? = null

    // -- feed parsing ------------------------------------------------------------------

    private suspend fun fetchText(url: String, mirror: String?): String = withContext(Dispatchers.IO) {
        val attempts = listOfNotNull(url, mirror)
        var lastErr: String? = null
        for (target in attempts) {
            try {
                client.newCall(Request.Builder().url(target).get().build()).execute().use { res ->
                    if (!res.isSuccessful) {
                        lastErr = "HTTP ${res.code}"
                        return@use
                    }
                    return@withContext res.body?.string().orEmpty()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallowed. Treating a cancellation as "this source failed" is what made
                // every source report the Compose message as its own error.
                throw e
            } catch (e: Exception) {
                lastErr = e.message
            }
        }
        throw IllegalStateException(lastErr ?: S(R.string.not_fetched))
    }

    /** Feeds ship either raw links or one big base64 blob; accept both without being told. */
    private fun decodeFeed(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed.take(32))) {
            return trimmed.split(Regex("\r?\n"))
        }
        try {
            val decoded = String(Base64.decode(trimmed.replace(Regex("\\s+"), ""), Base64.DEFAULT))
            if (decoded.contains("://")) return decoded.split(Regex("\r?\n"))
        } catch (e: Exception) {
            // not base64 after all
        }
        return trimmed.split(Regex("\r?\n"))
    }

    /** The remark a feed puts after `#`, with any foreign channel handle replaced by ours. */
    fun remarkOf(uri: String): String {
        val hash = uri.indexOf('#')
        if (hash == -1) return ""
        val raw = try {
            URLDecoder.decode(uri.substring(hash + 1), "UTF-8").trim()
        } catch (e: Exception) {
            uri.substring(hash + 1).trim()
        }
        return raw.replace(Regex("@[A-Za-z0-9_]{2,}"), BRAND)
    }

    private fun withRemark(uri: String, remark: String): String {
        val hash = uri.indexOf('#')
        val base = if (hash == -1) uri else uri.substring(0, hash)
        val encoded = URLEncoder.encode(remark, "UTF-8").replace("+", "%20")
        return "$base#$encoded"
    }

    /**
     * A short, honest name for one node.
     *
     * Feed remarks are built for a different audience: «🇳🇱 | @Channel | NL1|31.4MB/s|GPT-NL|
     * GM-NL|CL-NL|SP-NL» is eight fields of provider bookkeeping. None of it survives into the
     * list -- the country is shown as a flag column, and what is left is a stable per-node tag
     * so two servers in the same country are still tellable apart.
     */
    private fun endpointTag(host: String, port: Int, used: MutableSet<String>): String {
        // FNV-1a over the endpoint, rendered base36. Derived from the address rather than the
        // position, so a server keeps the same tag between refreshes even when the feed reorders
        // itself -- which is what lets a user say "I was on K3P9" and mean something.
        //
        // The width matters more than it looks. A previous version used `hash % 900`, giving 900
        // tags per country against 473 Canadian servers: measured on the live feed, 1424 servers
        // shared a name with at least one other and a single tag was worn by twenty different
        // machines. 36^4 is 1.68 million, and the loop below closes the remainder.
        val key = "$host:$port"
        var h = 0x811c9dc5.toInt()
        for (ch in key) {
            h = h xor ch.code
            h *= 0x01000193
        }
        for (salt in 0 until 64) {
            var v = h
            if (salt != 0) {
                v = v xor salt
                v *= 0x01000193
            }
            val tag = ((v.toLong() and 0xFFFFFFFFL) % 1679616L)
                .toString(36).uppercase(Locale.US).padStart(4, '0')
            if (!used.contains(tag)) {
                used.add(tag)
                return tag
            }
        }
        return ((h.toLong() and 0xFFFFFFFFL) % 1679616L)
            .toString(36).uppercase(Locale.US).padStart(4, '0')
    }

    /** The label a row shows: country prefix plus the endpoint's own tag. */
    fun composeName(code: String?, tag: String): String = "${code ?: "XX"}-$tag"

    /**
     * Turn a list of share links into runnable, country-tagged entries.
     *
     * Parsing IS the filter. [VpnConfig.parseUri] rejects protocols the core cannot speak and
     * malformed parameters -- every one of which is fatal to the WHOLE Xray config rather than
     * to the single node, so none of them may ever reach the tester.
     */
    /**
     * One URI, parsed the same way a feed's would be.
     *
     * The pool worker hands back configs rather than a feed, but they have to arrive as the same
     * [QuickNode] the list, the tester and the connect button already know -- including the
     * protocol filtering, which is not cosmetic: a config the core cannot speak poisons the whole
     * generated Xray config, not just its own entry.
     */
    fun parseNode(uri: String, sourceId: String): QuickNode? {
        val (nodes, _, _) = toEntries(
            uris = listOf(uri),
            sourceId = sourceId,
            seen = HashSet(),
            usedTags = HashSet(),
            blocked = emptySet(),
        )
        return nodes.firstOrNull()
    }

    private fun toEntries(
        uris: List<String>,
        sourceId: String,
        seen: MutableSet<String>,
        usedTags: MutableSet<String>,
        blocked: Set<String>,
    ): Triple<List<QuickNode>, Int, Int> {
        val out = ArrayList<QuickNode>()
        var unusable = 0
        var duplicate = 0

        for (raw in uris) {
            val uri = raw.trim()
            if (uri.isEmpty() || uri.startsWith("#")) continue

            val config = try {
                VpnConfig.parseUri(uri)
            } catch (e: Exception) {
                null
            }
            if (config == null) { unusable++; continue }

            val host = config.address
            val port = config.port
            if (host.isBlank() || port <= 0) { unusable++; continue }

            val key = FreeConfigEngine.serverKey(uri)
            // A server the user deleted is dropped here, before it can reach the list, the
            // tester or the cache -- so a refresh cannot resurrect it.
            if (key in blocked) { duplicate++; continue }
            if (!seen.add(key)) { duplicate++; continue }

            val remark = remarkOf(uri)
            // The host is included in the search text on purpose: plenty of feeds carry no
            // remark at all but use names like `de-fra-01.example.net`.
            val country = GeoLabel.countryFromText(listOf(remark, host))
            val tag = endpointTag(host, port, usedTags)
            val name = composeName(country?.code, tag)

            out.add(
                QuickNode(
                    id = key,
                    uri = withRemark(uri, if (country != null) "${country.flag} ${country.name} · $name" else name),
                    protocol = config.protocol,
                    host = host,
                    port = port,
                    tag = tag,
                    name = name,
                    country = country?.code,
                    flag = country?.flag,
                    countryName = country?.name,
                    source = sourceId,
                )
            )
        }
        return Triple(out, unusable, duplicate)
    }

    // -- catalog -----------------------------------------------------------------------

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun readDiskCache(context: Context): Snapshot? {
        return try {
            val f = cacheFile(context)
            if (!f.exists()) return null
            val root = JSONObject(f.readText())
            if (root.optInt("v", 0) != CACHE_VERSION) return null
            val arr = root.optJSONArray("nodes") ?: return null
            if (arr.length() == 0) return null
            val nodes = ArrayList<QuickNode>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                nodes.add(
                    QuickNode(
                        id = o.getString("id"),
                        uri = o.getString("uri"),
                        protocol = o.optString("protocol"),
                        host = o.optString("host"),
                        port = o.optInt("port"),
                        tag = o.optString("tag"),
                        name = o.optString("name"),
                        country = o.optString("country").ifBlank { null },
                        flag = o.optString("flag").ifBlank { null },
                        countryName = o.optString("countryName").ifBlank { null },
                        source = o.optString("source"),
                    )
                )
            }
            // The cache can predate a deletion, so the blocklist is applied on the way out too,
            // not only while parsing a fresh feed.
            val blocked = QuickBlocklist.ids(context)
            val kept = if (blocked.isEmpty()) nodes else nodes.filter { it.id !in blocked }
            if (kept.isEmpty()) return null
            QuickVerifiedStore.applyTo(context, kept)
            Snapshot(at = root.optLong("at"), nodes = kept, sources = emptyList())
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDiskCache(context: Context, snap: Snapshot) {
        try {
            val arr = JSONArray()
            for (n in snap.nodes) {
                arr.put(
                    JSONObject()
                        .put("id", n.id).put("uri", n.uri).put("protocol", n.protocol)
                        .put("host", n.host).put("port", n.port).put("tag", n.tag)
                        .put("name", n.name).put("country", n.country ?: "")
                        .put("flag", n.flag ?: "").put("countryName", n.countryName ?: "")
                        .put("source", n.source)
                )
            }
            val root = JSONObject().put("v", CACHE_VERSION).put("at", snap.at).put("nodes", arr)
            cacheFile(context).writeText(root.toString())
        } catch (e: Exception) {
            // A cache that cannot be written is not a reason to fail the fetch.
        }
    }

    private suspend fun fetchAll(
        context: Context,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): Snapshot {
        val seen = HashSet<String>()
        val usedTags = HashSet<String>()
        val nodes = ArrayList<QuickNode>()
        val sources = ArrayList<QuickSourceStatus>()
        val blocked = QuickBlocklist.ids(context)

        for ((index, src) in SOURCES.withIndex()) {
            onProgress(index, SOURCES.size, src.title)
            try {
                val uris = if (src.shared) {
                    // Servers other installs on this same network have proven within the hour.
                    // Returns nothing when the pool has not been reached or has no entry for
                    // this ISP yet, which is an ordinary empty source, not a failure.
                    MlmPoolClient.fetch(context).map { it.uri }
                } else if (src.pool) {
                    // Already parsed and de-duped by FreeConfigEngine; re-key it against the
                    // merged set so the same server appearing in both feeds is listed once.
                    FreeConfigEngine.fetchCandidates()
                } else {
                    decodeFeed(fetchText(src.url!!, src.mirror))
                }
                val (added, unusable, duplicate) = toEntries(uris, src.id, seen, usedTags, blocked)
                nodes.addAll(added)
                sources.add(
                    QuickSourceStatus(src.id, src.title, ok = true, usable = added.size, unusable = unusable, duplicate = duplicate)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One dead feed must not empty the list -- that is why there is more than one.
                Log.w(TAG, "source ${src.id} failed: ${e.message}")
                sources.add(QuickSourceStatus(src.id, src.title, ok = false, usable = 0, error = e.message))
            }
        }

        onProgress(SOURCES.size, SOURCES.size, "")
        if (nodes.isEmpty()) throw IllegalStateException(S(R.string.no_servers_were_fetched_check_your_internet))
        QuickVerifiedStore.applyTo(context, nodes)
        return Snapshot(at = System.currentTimeMillis(), nodes = nodes, sources = sources)
    }

    /**
     * The server list, from memory, then disk, then the network.
     *
     * A stale list is always preferable to no list: the user opened this panel to get online,
     * and yesterday's servers can do that while a failed download cannot.
     */
    private suspend fun load(context: Context, force: Boolean = false): Snapshot {
        val cached = memory
        if (!force && cached != null && System.currentTimeMillis() - cached.at < TTL_MS) return cached

        // The download runs on the repository's own scope and callers merely await it.
        //
        // It used to run directly in whichever coroutine asked, which on the browse screen was
        // a composition-scoped one -- so leaving the screen cancelled the fetch mid-flight, and
        // because the per-source handler caught every exception including CancellationException,
        // the result was every source reporting "the coroutine scope left the composition" as
        // its own download error. Both halves of that are fixed: cancellation is rethrown, and
        // the fetch no longer belongs to a caller who can disappear.
        val job = loadLock.withLock {
            val again = memory
            if (!force && again != null && System.currentTimeMillis() - again.at < TTL_MS) return again
            // A refresh already in flight is shared rather than duplicated.
            inFlight?.takeIf { it.isActive && !force } ?: scope.async {
                try {
                    val fresh = fetchAll(context)
                    memory = fresh
                    writeDiskCache(context, fresh)
                    fresh
                } catch (err: kotlinx.coroutines.CancellationException) {
                    throw err
                } catch (err: Exception) {
                    val fallback = memory ?: readDiskCache(context)
                    if (fallback != null) {
                        memory = fallback
                        Snapshot(fallback.at, fallback.nodes, fallback.sources, stale = true, error = err.message)
                    } else {
                        throw err
                    }
                }
            }.also { inFlight = it }
        }
        return job.await()
    }

    /** Countries present in the list, most servers first. */
    private fun summarise(data: Snapshot): QuickCatalog {
        val byCode = LinkedHashMap<String, QuickCountryRow>()
        var unknown = 0
        for (n in data.nodes) {
            val code = n.country
            if (code == null) { unknown++; continue }
            val row = byCode[code]
            byCode[code] = if (row == null) {
                QuickCountryRow(code, n.flag ?: GeoLabel.flagForCode(code), n.countryName ?: code, 1)
            } else {
                row.copy(count = row.count + 1)
            }
        }
        val collator = java.text.Collator.getInstance(GeoLabel.FA)
        val countries = byCode.values.sortedWith(
            compareByDescending<QuickCountryRow> { it.count }.thenComparator { a, b -> collator.compare(a.name, b.name) }
        )
        return QuickCatalog(
            total = data.nodes.size,
            countries = countries,
            unknown = unknown,
            verified = data.nodes.count { it.verified },
            sources = data.sources,
            updatedAt = data.at,
            stale = data.stale,
            error = data.error,
        )
    }

    suspend fun catalog(context: Context, force: Boolean = false): QuickCatalog = summarise(load(context, force))

    /**
     * The list we already hold, or null -- memory first, then the disk cache. Never the network.
     *
     * Opening the browse screen used to call straight through to [catalog], so the first thing
     * it did was download six feeds and parse ~12,000 lines while the user watched an empty
     * screen. Whatever was cached could have been on screen instantly. Refreshing is now
     * something the user asks for, so the wait only ever happens when it was requested.
     */
    fun cached(context: Context): QuickCatalog? {
        val snap = memory ?: readDiskCache(context)?.also { memory = it } ?: return null
        return summarise(snap)
    }

    /** How old the cached list is, in milliseconds, or null when there is none. */
    fun cacheAgeMs(context: Context): Long? {
        val snap = memory ?: readDiskCache(context)?.also { memory = it } ?: return null
        return System.currentTimeMillis() - snap.at
    }

    /**
     * Download every feed again, reporting which one is in flight.
     *
     * Unlike [catalog] this never falls back to the cache: the user pressed refresh, so a
     * failure is something they need to be told about rather than quietly papered over.
     */
    suspend fun refresh(
        context: Context,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): QuickCatalog = loadLock.withLock {
        val fresh = fetchAll(context, onProgress)
        memory = fresh
        writeDiskCache(context, fresh)
        summarise(fresh)
    }

    /** Every node, or only those in one country. `"unknown"` selects the unlabelled ones. */
    suspend fun nodesFor(context: Context, country: String?, force: Boolean = false): List<QuickNode> {
        val data = load(context, force)
        if (country == null || country == "all") return data.nodes
        if (country == "unknown") return data.nodes.filter { it.country == null }
        val code = GeoLabel.normalizeCode(country) ?: return emptyList()
        return data.nodes.filter { it.country == code }
    }

    /**
     * The pool grouped by country, for the [countryCount] countries with the most servers.
     *
     * Biggest-first because a balanced sweep asking for ten servers from a country that has
     * twelve is going to come back short, and the point of "5 countries" is five countries
     * that can actually supply their share.
     */
    suspend fun nodesByTopCountries(context: Context, countryCount: Int): Map<String, List<QuickNode>> {
        val data = load(context, false)
        val grouped = data.nodes.filter { it.country != null }.groupBy { it.country!! }
        return grouped.entries
            .sortedByDescending { it.value.size }
            .take(countryCount.coerceAtLeast(1))
            .associate { it.key to it.value }
    }

    /** One node by id, from whatever is already loaded. */
    fun nodeById(id: String): QuickNode? = memory?.nodes?.firstOrNull { it.id == id }

    /**
     * Move a node into its measured country, in the list the panel is looking at right now,
     * instead of waiting for the next fetch. Returns true when the country actually changed.
     */
    fun applyMeasured(nodeId: String, code: String): Boolean {
        val node = memory?.nodes?.firstOrNull { it.id == nodeId } ?: return false
        val claimed = node.claimedCountry ?: node.country
        node.applyVerified(code)
        return claimed != GeoLabel.normalizeCode(code)
    }

    /**
     * Drop the in-memory list so the next read rebuilds it.
     *
     * Called after the user deletes servers: the blocklist is consulted while parsing, so a
     * list already in memory would keep showing rows that are now blocked.
     */
    fun invalidate() {
        memory = null
    }

    /** Drop every measured country, and rebuild the list from the feed's own labels next read. */
    fun forgetVerified(context: Context) {
        QuickVerifiedStore.forget(context)
        memory = null
    }
}
