package com.mlmvpn.scanner.engines.game.booster.doctor

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One question asked of one host: from this line, through this address, does the host serve us,
 * and if not, how does it refuse?
 *
 * The same four answers the anti-sanction screen tells apart (`AntiSanctionManager.classifyDomain`,
 * a port of the desktop's `sanction-manager.js`), with two things that screen does not need and
 * the booster does:
 *  - the address is chosen by the caller, so the same host can be asked through the system's
 *    answer, a clean DNS answer, or an anti-sanction DNS's proxy, and the three compared;
 *  - the certificate is checked against the host's name, so a filtering or "please register"
 *    page served on the host's address is never mistaken for the host.
 */
object SanctionProbe {

    enum class Outcome {
        /** The host itself answered with something other than a geo-block. */
        OPEN,
        /** The host answered, with its own certificate, 403 or 451: it refuses this line's country. */
        GEO_BLOCKED,
        /** TCP connected, then the TLS handshake was cut: filtering on the name (SNI). */
        TLS_RESET,
        /** Someone else's certificate on the host's address: an interception or block page. */
        CERT_MISMATCH,
        /** Refused at TCP. */
        TCP_RESET,
        /** Nothing at TCP. */
        TCP_TIMEOUT,
        ERROR,
    }

    data class Result(val outcome: Outcome, val httpStatus: Int? = null, val connectMs: Long? = null)

    /** How an HTTP status reads: only 403 and 451 are a country refusal; anything else is the host serving. */
    fun classifyStatus(status: Int): Outcome =
        if (status == 403 || status == 451) Outcome.GEO_BLOCKED else Outcome.OPEN

    /**
     * `HEAD /` over TLS to [ip]:443 with the real SNI [host]. Timeouts are per step, so a dead
     * address costs at most [connectTimeoutMs] and a stalled handshake at most [tlsTimeoutMs].
     */
    suspend fun probe(
        host: String,
        ip: String,
        network: Network?,
        connectTimeoutMs: Int = 2000,
        tlsTimeoutMs: Int = 2500,
    ): Result = withContext(Dispatchers.IO) {
        var raw: Socket? = null
        val t0 = System.nanoTime()
        try {
            raw = network?.socketFactory?.createSocket() ?: Socket()
            try {
                raw.connect(InetSocketAddress(ip, 443), connectTimeoutMs)
            } catch (e: SocketTimeoutException) {
                return@withContext Result(Outcome.TCP_TIMEOUT)
            } catch (e: ConnectException) {
                return@withContext Result(Outcome.TCP_RESET)
            }
            val connectMs = (System.nanoTime() - t0) / 1_000_000
            raw.soTimeout = tlsTimeoutMs
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, 443, true) as SSLSocket
            try {
                val p = ssl.sslParameters
                p.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = p
            } catch (_: Exception) {
            }
            try {
                ssl.startHandshake()
            } catch (e: SSLException) {
                // A chain that does not verify fails here too; either way it is not the host.
                return@withContext Result(
                    if (e.message?.contains("Trust anchor", ignoreCase = true) == true ||
                        e.message?.contains("certificate", ignoreCase = true) == true
                    ) Outcome.CERT_MISMATCH else Outcome.TLS_RESET,
                    connectMs = connectMs,
                )
            } catch (e: Exception) {
                return@withContext Result(Outcome.TLS_RESET, connectMs = connectMs)
            }
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                try { ssl.close() } catch (_: Exception) {}
                return@withContext Result(Outcome.CERT_MISMATCH, connectMs = connectMs)
            }
            // From here the host has proven itself with its own certificate: whatever happens to
            // the request, it is reachable -- only an explicit 403/451 says otherwise.
            val status = try {
                val req = "HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0 (Linux; Android 13)\r\n" +
                    "Accept: */*\r\nConnection: close\r\n\r\n"
                ssl.outputStream.write(req.toByteArray(Charsets.US_ASCII))
                ssl.outputStream.flush()
                val line = ssl.inputStream.bufferedReader(Charsets.ISO_8859_1).readLine() ?: ""
                Regex("^HTTP/[\\d.]+ (\\d{3})").find(line)?.groupValues?.get(1)?.toIntOrNull()
            } catch (e: Exception) {
                null
            } finally {
                try { ssl.close() } catch (_: Exception) {}
            }
            if (status == null) Result(Outcome.OPEN, null, connectMs)
            else Result(classifyStatus(status), status, connectMs)
        } catch (e: SocketTimeoutException) {
            Result(Outcome.TLS_RESET)
        } catch (e: Exception) {
            Result(Outcome.ERROR)
        } finally {
            try { raw?.close() } catch (_: Exception) {}
        }
    }
}
