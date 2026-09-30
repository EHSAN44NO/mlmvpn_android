package com.mlmvpn.scanner.engines.mae.probe

import android.content.Context
import com.mlmvpn.scanner.engines.mae.model.ProbeSpec
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * One small request through the RUNNING tunnel, exactly as an app's traffic goes: into the
 * tunnel's own loopback HTTP inbound (MaeConfigCompiler's canary inbound), through the rules and
 * the app's balancer, out of the outbound the app is using now.
 *
 * Why it exists (the user, 2026-09-30): Instagram sat idle for twenty minutes, then its videos
 * would not play until the tunnel was reconnected by hand. The route had died under the app and
 * nothing looked: the probe core measures routes with its own sessions, never the tunnel's.
 * This looks at the tunnel's.
 */
class LiveCanary(private val context: Context, private val port: Int) {

    data class Result(
        /** The service itself answered through the route (TLS with its own certificate + HTTP). */
        val answered: Boolean,
        /** ...and not with a refusal. */
        val usable: Boolean,
        /** It answered with the service's region refusal. */
        val refused: Boolean,
        val ms: Long,
    )

    fun check(spec: ProbeSpec): Result {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        val uri = runCatching { URI(spec.url) }.getOrNull() ?: return Result(false, false, false, 0)
        val host = uri.host ?: return Result(false, false, false, 0)
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MS)
            raw.soTimeout = HANDSHAKE_MS
            raw.getOutputStream().write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray())
            val head = readHead(raw.getInputStream())
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) {
                runCatching { raw.close() }
                return Result(false, false, false, ms())
            }
        } catch (e: Exception) {
            runCatching { raw.close() }
            return Result(false, false, false, ms())
        }
        val (tls, http) = NetProber(context).tlsGetThrough(raw, host, path, spec, HANDSHAKE_MS, READ_MS)
        val o = Observation("canary", foreign = false, direct = false, tcp = Step.OK, tls = tls, http = http)
        return Result(o.answered, o.usable, o.refusedCountry, ms())
    }

    /** The proxy's reply head, up to the blank line (1 KB at most). */
    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 1024) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    companion object {
        const val CONNECT_MS = 2_000
        /** Through a foreign exit the handshake crosses the exit too: generous, or it cries wolf. */
        const val HANDSHAKE_MS = 7_000
        const val READ_MS = 7_000
    }
}
