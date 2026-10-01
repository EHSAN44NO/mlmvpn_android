package com.mlmvpn.scanner.engines.flux

import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.engines.flux.core.model.FluxNode
import com.mlmvpn.scanner.engines.flux.core.source.FluxSources
import com.mlmvpn.scanner.engines.flux.core.source.NodeSource
import com.mlmvpn.scanner.engines.flux.core.source.SourceHealth
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * FLUX's node lists on disk: cache first, refreshed in the background, never in the way of a
 * connect. A list that cannot be fetched keeps its last good copy; a fresh install with no copy at
 * all simply has fewer nodes until the first refresh lands.
 */
class FluxSourceRepo(private val app: Context) {

    private val dir = File(File(app.filesDir, "flux"), "src").apply { mkdirs() }
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var parsed: Pair<Long, List<FluxNode>>? = null

    fun sources(): List<NodeSource> = FluxSources.BUILT_IN + userSubscriptions().mapIndexed { i, u -> FluxSources.userSource(i, u) }

    fun userSubscriptions(): List<String> = runCatching {
        JSONArray(prefs.getString(KEY_USER, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList())

    fun setUserSubscriptions(urls: List<String>) {
        prefs.edit().putString(KEY_USER, JSONArray(urls.filter { FluxSources.isHttpsUrl(it) }.distinct().take(5)).toString()).apply()
        parsed = null
    }

    fun health(id: String): SourceHealth? = runCatching {
        prefs.getString("h_$id", null)?.let { s ->
            val o = JSONObject(s)
            SourceHealth(o.optLong("ok"), o.optLong("fail"), o.optInt("cf"), o.optInt("use"), o.optInt("rej"),
                o.optString("etag").ifEmpty { null }, o.optString("lm").ifEmpty { null })
        }
    }.getOrNull()

    private fun saveHealth(id: String, h: SourceHealth) {
        prefs.edit().putString("h_$id", JSONObject().put("ok", h.lastOkAt).put("fail", h.lastFailAt).put("cf", h.consecutiveFailures)
            .put("use", h.usable).put("rej", h.rejected).put("etag", h.etag ?: "").put("lm", h.lastModified ?: "").toString()).apply()
    }

    /** Every cached node, parsed once per change of the cache. */
    fun nodes(): List<FluxNode> {
        val stamp = sources().sumOf { cacheFile(it).lastModified() } + sources().size
        parsed?.takeIf { it.first == stamp }?.let { return it.second }
        val batches = sources().mapNotNull { src ->
            val f = cacheFile(src)
            if (!f.exists()) null else runCatching { FluxSources.parse(src, f.readText()).nodes }.getOrNull()
        }
        return FluxSources.merge(batches).also { parsed = stamp to it }
    }

    fun hasAnyCache(): Boolean = sources().any { cacheFile(it).exists() }

    /**
     * Refreshes the sources that are due. Direct first (the app's own traffic is outside the VPN);
     * when that fails and a tunnel is up, once more through the tunnel's local HTTP proxy.
     */
    fun refreshDue(onWifi: Boolean, tunnelHttpPort: Int?, force: Boolean = false): Int {
        val now = System.currentTimeMillis()
        var changed = 0
        for (src in sources()) {
            if (src.wifiOnly && !onWifi) continue
            val h = health(src.id) ?: SourceHealth()
            if (!force && !FluxSources.due(src, health(src.id), now)) continue
            val result = fetch(src, h, null) ?: tunnelHttpPort?.let { fetch(src, h, Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it))) }
            when (result) {
                null -> saveHealth(src.id, FluxSources.recordFail(h, now))
                Fetched.NotModified -> saveHealth(src.id, FluxSources.recordNotModified(h, now))
                is Fetched.Body -> {
                    val batch = FluxSources.parse(src, result.text)
                    if (batch.nodes.isEmpty()) {
                        // A list that came back empty or unreadable does not replace a good copy.
                        saveHealth(src.id, FluxSources.recordFail(h, now))
                    } else {
                        cacheFile(src).writeText(result.text)
                        saveHealth(src.id, FluxSources.recordOk(h, now, batch.nodes.size, batch.rejected.values.sum(), result.etag, result.lastModified))
                        changed++
                    }
                }
            }
        }
        if (changed > 0) parsed = null
        return changed
    }

    private sealed class Fetched {
        object NotModified : Fetched()
        class Body(val text: String, val etag: String?, val lastModified: String?) : Fetched()
    }

    private fun fetch(src: NodeSource, h: SourceHealth, proxy: Proxy?): Fetched? = try {
        val url = URL(src.url)
        val c = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MLMVPN-FLUX")
        if (cacheFile(src).exists()) {
            h.etag?.let { c.setRequestProperty("If-None-Match", it) }
            h.lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
        }
        try {
            when (c.responseCode) {
                304 -> Fetched.NotModified
                200 -> {
                    val bytes = c.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > src.maxBytes) break
                        }
                        out.toByteArray()
                    }
                    // Over the cap: the head of a list is still its best part, so it is kept.
                    Fetched.Body(String(bytes, Charsets.UTF_8).let { if (bytes.size > src.maxBytes) it.substringBeforeLast('\n') else it },
                        c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
                }
                else -> null
            }
        } finally { c.disconnect() }
    } catch (e: Exception) {
        Log.i(TAG, "source ${src.id} not fetched${if (proxy != null) " (via tunnel)" else ""}: ${e.javaClass.simpleName}")
        null
    }

    private fun cacheFile(src: NodeSource) = File(dir, src.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".txt")

    companion object {
        private const val TAG = "FluxSources"
        private const val PREFS = "flux_sources"
        private const val KEY_USER = "user_subs"
    }
}
