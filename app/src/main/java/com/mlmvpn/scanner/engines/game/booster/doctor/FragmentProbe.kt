package com.mlmvpn.scanner.engines.game.booster.doctor

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Does the host open when the TLS ClientHello is sent in pieces?
 *
 * The same question [SanctionProbe] asks, with one difference: the first TLS packet -- the one
 * carrying the host's name in clear -- leaves as three TCP segments, cut through the middle of the
 * name. A filter that reads the name from one segment then sees half of it in each and lets the
 * connection pass; the server reassembles and never notices.
 *
 * `SSLSocket` cannot do this: Android's implementation writes to the socket's file descriptor
 * directly. So the handshake is driven here by hand with an [SSLEngine] and the bytes are written
 * by this code, which is also what lets the booster TEST the fix from the phone before a session
 * relies on it -- the session itself fragments with the Iran profiles' proven outbound.
 */
object FragmentProbe {

    /** Where the first packet is cut: after the record header, and through the middle of the name. */
    fun cutPoints(hello: ByteArray, host: String): List<Int> {
        val name = host.toByteArray(Charsets.US_ASCII)
        val at = indexOf(hello, name)
        val cuts = mutableListOf(minOf(5, hello.size))
        if (at >= 0) cuts += at + name.size / 2 else cuts += minOf(hello.size, 5 + (hello.size - 5) / 2)
        return cuts.filter { it in 1 until hello.size }.distinct().sorted()
    }

    suspend fun probe(
        host: String,
        ip: String,
        network: Network?,
        connectTimeoutMs: Int = 1500,
        ioTimeoutMs: Int = 2500,
    ): SanctionProbe.Result = withContext(Dispatchers.IO) {
        var sock: Socket? = null
        try {
            sock = network?.socketFactory?.createSocket() ?: Socket()
            sock.tcpNoDelay = true
            val t0 = System.nanoTime()
            try {
                sock.connect(InetSocketAddress(ip, 443), connectTimeoutMs)
            } catch (e: SocketTimeoutException) {
                return@withContext SanctionProbe.Result(SanctionProbe.Outcome.TCP_TIMEOUT)
            } catch (e: ConnectException) {
                return@withContext SanctionProbe.Result(SanctionProbe.Outcome.TCP_RESET)
            }
            val connectMs = (System.nanoTime() - t0) / 1_000_000
            sock.soTimeout = ioTimeoutMs
            val engine = SSLContext.getDefault().createSSLEngine(host, 443).apply {
                useClientMode = true
                sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            }
            val io = Io(engine, sock.getInputStream(), sock.getOutputStream())
            try {
                io.handshake(host, System.currentTimeMillis() + 2L * ioTimeoutMs)
            } catch (e: SSLHandshakeException) {
                val certProblem = generateSequence<Throwable>(e) { it.cause }
                    .any { it is java.security.cert.CertificateException || it.message?.contains("Trust anchor", true) == true }
                return@withContext SanctionProbe.Result(
                    if (certProblem) SanctionProbe.Outcome.CERT_MISMATCH else SanctionProbe.Outcome.TLS_RESET, connectMs = connectMs)
            } catch (e: Exception) {
                return@withContext SanctionProbe.Result(SanctionProbe.Outcome.TLS_RESET, connectMs = connectMs)
            }
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, engine.session)) {
                return@withContext SanctionProbe.Result(SanctionProbe.Outcome.CERT_MISMATCH, connectMs = connectMs)
            }
            // The host has proven itself; only an explicit 403/451 says it refuses this line.
            val status = try {
                io.request("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0 (Linux; Android 13)\r\n" +
                    "Accept: */*\r\nConnection: close\r\n\r\n", System.currentTimeMillis() + ioTimeoutMs)
            } catch (e: Exception) {
                null
            }
            if (status == null) SanctionProbe.Result(SanctionProbe.Outcome.OPEN, null, connectMs)
            else SanctionProbe.Result(SanctionProbe.classifyStatus(status), status, connectMs)
        } catch (e: Exception) {
            SanctionProbe.Result(SanctionProbe.Outcome.ERROR)
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    /** An [SSLEngine] over a socket's streams, with the first packet written in pieces. */
    private class Io(private val engine: SSLEngine, private val input: InputStream, private val output: OutputStream) {
        private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
        private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
        private val netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
        private var firstWrite = true
        private var host = ""

        fun handshake(host: String, deadline: Long) {
            this.host = host
            engine.beginHandshake()
            var hs = engine.handshakeStatus
            while (hs != SSLEngineResult.HandshakeStatus.FINISHED && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                if (System.currentTimeMillis() > deadline) throw SocketTimeoutException("handshake")
                hs = when (hs) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> { wrap(ByteBuffer.allocate(0)); engine.handshakeStatus }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        while (true) { engine.delegatedTask?.run() ?: break }
                        engine.handshakeStatus
                    }
                    // NEED_UNWRAP, and NEED_UNWRAP_AGAIN on newer runtimes.
                    else -> { unwrapOnce(); engine.handshakeStatus }
                }
            }
        }

        /** Send [text], read until the status line is complete; its code, or null. */
        fun request(text: String, deadline: Long): Int? {
            wrap(ByteBuffer.wrap(text.toByteArray(Charsets.US_ASCII)))
            val sb = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                unwrapOnce()
                appIn.flip()
                while (appIn.hasRemaining()) sb.append((appIn.get().toInt() and 0xFF).toChar())
                appIn.clear()
                val end = sb.indexOf("\r\n")
                if (end >= 0) {
                    return Regex("^HTTP/[\\d.]+ (\\d{3})").find(sb.substring(0, end))?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return null
        }

        private fun wrap(src: ByteBuffer) {
            do {
                netOut.clear()
                val r = engine.wrap(src, netOut)
                if (r.status == SSLEngineResult.Status.CLOSED) throw SSLException("closed")
                netOut.flip()
                val bytes = ByteArray(netOut.remaining()).also { netOut.get(it) }
                if (bytes.isNotEmpty()) {
                    if (firstWrite) {
                        firstWrite = false
                        writeInPieces(bytes, cutPoints(bytes, host))
                    } else {
                        output.write(bytes)
                        output.flush()
                    }
                }
            } while (src.hasRemaining())
        }

        private fun writeInPieces(bytes: ByteArray, cuts: List<Int>) {
            var from = 0
            for (c in cuts + bytes.size) {
                if (c <= from) continue
                output.write(bytes, from, c - from)
                output.flush()
                from = c
                // A breath between segments so they do not coalesce on the way out.
                if (from < bytes.size) Thread.sleep(2)
            }
        }

        private fun unwrapOnce() {
            netIn.flip()
            val r = engine.unwrap(netIn, appIn)
            netIn.compact()
            when (r.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> readMore()
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val bigger = ByteBuffer.allocate(appIn.capacity() * 2)
                    appIn.flip(); bigger.put(appIn); appIn = bigger
                }
                SSLEngineResult.Status.CLOSED -> throw EOFException("closed")
                else -> if (r.bytesProduced() == 0 && r.bytesConsumed() == 0) readMore()
            }
        }

        private fun readMore() {
            if (!netIn.hasRemaining()) {
                val bigger = ByteBuffer.allocate(netIn.capacity() * 2)
                netIn.flip(); bigger.put(netIn); netIn = bigger
            }
            val n = input.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
            if (n < 0) throw EOFException("peer closed")
            netIn.position(netIn.position() + n)
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > hay.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
