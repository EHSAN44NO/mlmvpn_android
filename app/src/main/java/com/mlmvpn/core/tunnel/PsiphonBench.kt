package com.mlmvpn.core.tunnel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * What a country actually delivers, measured rather than assumed.
 *
 * A country picker with no numbers behind it asks the user to guess. On a line where domain
 * fronting is the only transport that works, the exit country is the single biggest lever on
 * speed -- meek carries data in HTTP round trips, so its throughput is bounded by latency, and
 * Frankfurt and Los Angeles are not interchangeable from Tehran. But which of the six countries
 * with a fronted server actually answers, and which of those is quickest, is a property of the
 * user's line on the day. It cannot be shipped as a constant.
 *
 * So it is measured: connect to each country in turn, and for the ones that come up, time a real
 * request and a real download THROUGH the tunnel.
 *
 * ## Why the measurement goes through the local SOCKS port
 *
 * The app excludes its own package from its own tun, so anything it sends over an ordinary socket
 * leaves on the carrier link no matter what is connected -- it would measure the Wi-Fi and report
 * the same number for all six countries. Psiphon's local SOCKS listener is the way in: it is on
 * loopback, it is not affected by the exclusion, and everything it carries goes through the tunnel
 * that is up. [com.mlmvpn.scanner.ui.tunnel.TunnelExitLocator] resolves the exit address the same
 * way and for the same reason.
 *
 * State lives here as Compose state rather than in a ViewModel because the producer is a
 * foreground service and the consumer is a settings screen; they share a process and nothing else.
 */
object PsiphonBench {

    /** One country's result. Nulls mean "did not get that far". */
    data class Row(
        val region: String,
        val connectMs: Long? = null,
        val rttMs: Long? = null,
        val kbps: Int? = null,
        val failed: Boolean = false,
    )

    var running by mutableStateOf(false)
        internal set

    /** The country being measured right now, for the progress line. */
    var current by mutableStateOf("")
        internal set

    /** How far along, as "3 / 6". */
    var done by mutableStateOf(0)
        internal set

    var total by mutableStateOf(0)
        internal set

    val rows = mutableStateListOf<Row>()

    /**
     * Set by the screen, read by the loop between countries.
     *
     * A run is minutes long and takes the tunnel up and down as it goes, so "I have seen enough"
     * has to be an option. Volatile rather than Compose state because the writer is the UI thread
     * and the reader is the benchmark's own thread.
     */
    @Volatile
    private var cancelled = false

    /** Ask the run to stop after the country it is on. */
    fun cancel() {
        cancelled = true
        current = ""
    }

    internal fun isCancelled(): Boolean = cancelled

    /** Cleared by the service when a run starts. */
    internal fun begin(regions: List<String>) {
        rows.clear()
        done = 0
        total = regions.size
        current = ""
        cancelled = false
        running = true
    }

    /** Replace a country's row in place, for the second pass. */
    internal fun update(row: Row) {
        val i = rows.indexOfFirst { it.region == row.region }
        if (i >= 0) rows[i] = row else rows.add(row)
    }

    internal fun finish() {
        running = false
        current = ""
        cancelled = false
    }

    /**
     * Best first, by LATENCY.
     *
     * Throughput was the first ranking and it was the wrong one. meek moves data in HTTP round
     * trips, so on this transport latency is not merely correlated with speed -- it is most of
     * what speed IS, and it can be measured in one request instead of a twelve-second download.
     * A country that answers in 530ms and one that answers in 2500ms are not close, and waiting
     * twelve seconds each to discover that is time the user spends learning something the first
     * request already said. Throughput is still recorded when it was taken, and breaks ties.
     */
    fun ranked(): List<Row> = rows.sortedWith(
        compareBy<Row> { it.rttMs ?: Long.MAX_VALUE }.thenByDescending { it.kbps ?: -1 }
    )

    // --- the measurement ------------------------------------------------------------------

    /**
     * A small object, fetched twice.
     *
     * The first fetch is the latency sample and the second is thrown away with it: a cold tunnel
     * has to open an SSH channel and a meek session before the first byte, and timing that would
     * measure the setup rather than the path. `/cdn-cgi/trace` is a few hundred bytes and exists
     * on every Cloudflare edge, so the request itself contributes nothing to the number.
     */
    private const val RTT_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    /** Two megabytes is enough to leave TCP slow-start behind without making the test long. */
    private const val THROUGHPUT_URL = "https://speed.cloudflare.com/__down?bytes=2000000"

    /**
     * Kept short: this now runs once, on the country that already won on latency, purely to put a
     * real number next to it. It is not part of the comparison.
     */
    private const val THROUGHPUT_CAP_MS = 8_000L

    private fun client(socksPort: Int): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        // No pooling between measurements: a reused connection would hand the next country the
        // previous one's warm path, and the whole point is to compare cold ones.
        .retryOnConnectionFailure(false)
        .build()

    /** Round trip through the live tunnel, in milliseconds, or null if it did not answer. */
    fun measureRtt(socksPort: Int): Long? = runCatching {
        val c = client(socksPort)
        // Warm-up, discarded. See [RTT_URL].
        runCatching { c.newCall(Request.Builder().url(RTT_URL).build()).execute().use { it.body?.bytes() } }
        val t0 = System.nanoTime()
        c.newCall(Request.Builder().url(RTT_URL).build()).execute().use { resp ->
            resp.body?.bytes()
            if (!resp.isSuccessful) return null
        }
        (System.nanoTime() - t0) / 1_000_000
    }.getOrNull()

    /** Download rate through the live tunnel in KB/s, or null if nothing arrived. */
    fun measureThroughput(socksPort: Int): Int? = runCatching {
        val c = client(socksPort)
        val t0 = System.currentTimeMillis()
        var read = 0L
        c.newCall(Request.Builder().url(THROUGHPUT_URL).build()).execute().use { resp ->
            val stream = resp.body?.byteStream() ?: return null
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                read += n
                if (System.currentTimeMillis() - t0 > THROUGHPUT_CAP_MS) break
            }
        }
        val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
        if (read == 0L) null else ((read * 1000L) / ms / 1024L).toInt()
    }.getOrNull()
}
