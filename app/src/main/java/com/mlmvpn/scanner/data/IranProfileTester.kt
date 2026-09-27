package com.mlmvpn.scanner.data

import android.content.Context
import android.util.Base64
import com.mlmvpn.scanner.MyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * «کدام کانفیگ ایران روی این خط کار می‌کند؟»
 *
 * The serverless configs have no server. They reach the internet with two tricks that fail
 * INDEPENDENTLY: a DoH resolver, to get an honest answer for a poisoned name, and a fragmented
 * TLS ClientHello, to get past SNI inspection.
 *
 * This measures the FIRST of those, and that is a deliberate line, not a shortcut. The resolver
 * is the half that can be asked without a core: it is a plain HTTPS request to a public endpoint,
 * it costs about a second, and it is decisive — **if a config's resolver cannot be reached, not
 * one name resolves, so every site fails and the config cannot work at all.** Measured on one
 * Iranian mobile line (2026-09-12):
 *
 *     Google 8.8.8.8         ✅ 493 ms
 *     AdGuard 94.140.14.14   ✅ 1036 ms
 *     Cloudflare 1.1.1.1     ❌ reset after 11 s
 *     cloudflare-dns.com     ❌ never answered   ← the upstream default
 *
 * That last row is why the built-in configs opened nothing on that line while being perfectly
 * healthy otherwise, and it is exactly what this test catches.
 *
 * The SECOND half — whether the fragmentation defeats this operator's DPI — cannot be answered
 * without actually running the core through the tunnel, so it is not claimed here. A row that
 * passes is a row that CAN work, not one that is proven to. The UI says so rather than implying
 * a verdict this cannot give.
 *
 * One probe per distinct endpoint, not one per profile: nineteen profiles share three resolvers,
 * so a line that cannot reach one loses its whole group in a single second.
 */
object IranProfileTester {

    /** A DNS query for one A record — enough to prove a resolver answers. */
    private val DNS_QUERY: ByteArray =
        Base64.decode("AAABAAABAAAAAAAAA3d3dwd5b3V0dWJlA2NvbQAAAQAB", Base64.DEFAULT)

    /** What one profile's resolver did. `ms` is only meaningful when [ok]. */
    data class Verdict(val ok: Boolean, val ms: Long, val why: String)

    /**
     * The DoH endpoint a profile's config asks names of, or null when it has none we can read.
     *
     * Read out of the config rather than carried beside it: the config is the thing that will
     * actually run, and a label that drifted from it would measure the wrong endpoint.
     */
    fun resolverOf(configJson: String): String? = try {
        val dns = org.json.JSONObject(configJson).optJSONObject("dns")
        val servers = dns?.optJSONArray("servers")
        var found: String? = null
        if (servers != null) {
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                if (s.optString("tag") == "no-filter-dns") {
                    found = s.optString("address").takeIf { it.startsWith("http") }
                    break
                }
            }
        }
        found
    } catch (_: Exception) {
        null
    }

    /**
     * Ask one DoH endpoint whether it answers from this network.
     *
     * `protect()`ed like every other probe in the app: if a tunnel happens to be up, this must
     * still measure the USER'S LINE, or the answer is about the tunnel instead of about the
     * network the tunnel would have to be built on.
     */
    suspend fun probeResolver(doh: String, timeoutMs: Int = 7000): Verdict =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(doh).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/dns-message")
                    setRequestProperty("Accept", "application/dns-message")
                }
                DataOutputStream(conn.outputStream).use { it.write(DNS_QUERY) }
                val code = conn.responseCode
                val bytes = if (code == 200) conn.inputStream.use { it.readBytes().size } else 0
                val ms = System.currentTimeMillis() - started
                if (code == 200 && bytes > 0) Verdict(true, ms, "")
                else Verdict(false, ms, "پاسخ $code")
            } catch (e: Exception) {
                Verdict(false, System.currentTimeMillis() - started, shortReason(e))
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    private fun shortReason(e: Exception): String = when {
        e is java.net.SocketTimeoutException -> "بی‌پاسخ"
        e.message.isNullOrBlank() -> e.javaClass.simpleName
        else -> e.message!!.take(60)
    }

    /**
     * Measure every profile in [configs] (id -> config JSON) and return id -> verdict.
     *
     * Profiles sharing a resolver share its single measurement, so the cost is the number of
     * DISTINCT endpoints, not the number of rows.
     */
    suspend fun testAll(configs: Map<String, String>): Map<String, Verdict> = coroutineScope {
        val byProfile: Map<String, String?> = configs.mapValues { resolverOf(it.value) }
        val endpoints: List<String> = byProfile.values.filterNotNull().distinct()

        val measured: Map<String, Verdict> = endpoints
            .map { doh -> async { doh to probeResolver(doh) } }
            .awaitAll()
            .toMap()

        byProfile.mapValues { (_, doh) ->
            when {
                doh == null -> Verdict(true, 0, "")   // nothing to check: never call it broken
                else -> measured[doh] ?: Verdict(false, 0, "سنجیده نشد")
            }
        }
    }

    /** The profiles this app ships, as id -> config JSON, read from the generated asset. */
    fun builtInConfigs(context: Context): Map<String, String> = try {
        val raw = com.mlmvpn.scanner.store.StoreFiles.open(context, "iran_profiles.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(raw)
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cfg = o.optString("config", "")
                if (cfg.isNotEmpty()) put("default_mlmvpn_${i + 1}", cfg)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Unused today, kept honest: the probe must never be attributed to a live tunnel. */
    @Suppress("unused")
    fun tunnelIsUp(): Boolean = MyVpnService.instance != null
}
