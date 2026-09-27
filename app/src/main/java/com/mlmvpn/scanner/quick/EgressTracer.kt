package com.mlmvpn.scanner.quick

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * Where traffic actually leaves.
 *
 * This is the only statement about location in the whole panel that is evidence rather than a
 * label. `loc` is the country Cloudflare assigns the client it sees; `colo` is the edge
 * datacenter that answered; `warp` says whether the request arrived over Cloudflare's own
 * network.
 *
 * One caveat the UI must respect: when the path is WARP, `loc` reports the USER's country by
 * design, not the exit's -- a WARP session from Tehran reads `loc=IR colo=FRA` even though the
 * traffic really does leave in Frankfurt. So a WARP result is reported as "verified transit"
 * rather than "verified country", and [EgressResult.countryTrusted] is false, which is what
 * stops [QuickVerifiedStore] filing every node under Iran.
 *
 * Port of `traceEgress` in the desktop app's `quick-connect.js`.
 */
data class EgressResult(
    val ok: Boolean,
    val ip: String? = null,
    val loc: String? = null,
    val colo: String? = null,
    val warp: Boolean = false,
    val country: GeoCountry? = null,
    val countryTrusted: Boolean = false,
    /** Whether the request demonstrably left through the tunnel rather than around it. */
    val tunnelled: Boolean = false,
    val error: String? = null,
)

object EgressTracer {

    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    /**
     * The public IP this phone has with no tunnel, remembered from the last time we looked.
     *
     * This is what makes a reading trustworthy. Without it there is no way to tell a tunnel
     * that exits in Iran from a request that never entered the tunnel at all -- both report
     * `loc=IR`, and the second one is a leak, not a measurement. A node was being filed under
     * Iran on that basis and only corrected on the next connect, when the tunnel happened to be
     * ready in time.
     */
    @Volatile
    private var directIp: String? = null

    /** Read the phone's own public IP, deliberately NOT through the proxy. */
    suspend fun traceDirect(timeoutMs: Long = 6_000): EgressResult = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val res = request(client)
        if (res.ok && !res.ip.isNullOrBlank()) directIp = res.ip
        res
    }

    /** Forget the baseline — the phone changed network, so the old one describes nothing. */
    fun forgetBaseline() { directIp = null }

    /**
     * Read Cloudflare's own view of the connection through the app's local SOCKS port.
     *
     * The request goes through the proxy on purpose rather than over the TUN: it must describe
     * the tunnel we just built, and a plain request could be answered over any interface the
     * system happens to prefer at that moment.
     */
    suspend fun trace(socksPort: Int, timeoutMs: Long = 8_000): EgressResult = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val res = request(client)
        if (!res.ok) return@withContext res

        // The proof that this describes the tunnel: the address the far end sees is not the
        // address this phone has without one. When they match, the request did not go through
        // the tunnel -- the core had not finished binding, or an old instance was still holding
        // the port -- and the reading says nothing about the server.
        val baseline = directIp
        val leaked = baseline != null && !res.ip.isNullOrBlank() && res.ip == baseline
        res.copy(
            tunnelled = !leaked,
            // WARP preserves the user's country on purpose, so `loc` is not the exit there.
            // A leaked reading is not the exit either. Either way, say so rather than letting
            // the UI print a wrong flag or the store record one.
            countryTrusted = res.countryTrusted && !leaked,
            error = if (leaked) S(R.string.no_traffic_passed_through_the_tunnel) else res.error,
        )
    }

    /**
     * Trace with retries, for use right after connecting.
     *
     * A fixed wait cannot be right: the core may bind in 300ms or take five seconds under load,
     * and the previous version's single shot at 2.5s was often measuring a tunnel that was not
     * carrying anything yet. This retries until the reading is actually through the tunnel.
     */
    suspend fun traceWhenReady(
        socksPort: Int,
        attempts: Int = 5,
        gapMs: Long = 1_800,
    ): EgressResult {
        var last = EgressResult(ok = false, error = S(R.string.not_checked))
        repeat(attempts) { index ->
            if (index > 0) kotlinx.coroutines.delay(gapMs)
            last = trace(socksPort)
            if (last.ok && last.tunnelled) return last
        }
        return last
    }

    private fun request(client: OkHttpClient): EgressResult {
        return try {
            val request = Request.Builder()
                .url(TRACE_URL)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "*/*")
                .get()
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return EgressResult(ok = false, error = S(R.string.response, res.code))
                val body = res.body?.string().orEmpty().take(4096)
                val fields = HashMap<String, String>()
                for (line in body.split('\n')) {
                    val eq = line.indexOf('=')
                    if (eq > 0) fields[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                }
                val warp = fields["warp"] == "on" || fields["warp"] == "plus"
                EgressResult(
                    ok = true,
                    ip = fields["ip"],
                    loc = fields["loc"],
                    colo = fields["colo"],
                    warp = warp,
                    country = GeoLabel.countryFromCode(fields["loc"]),
                    countryTrusted = !warp,
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            EgressResult(ok = false, error = S(R.string.no_response_timed_out))
        } catch (e: Exception) {
            EgressResult(ok = false, error = e.message ?: S(R.string.unknown_error_2))
        }
    }
}
