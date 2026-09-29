package com.mlmvpn.scanner.engines.mae.control

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * SPIKE: live route switching through Xray's gRPC API, reached over a Unix socket in filesDir.
 *
 * Deliberately just one call, [overrideBalancerTarget]. If it fails on a real phone (socket not
 * created, HTTP/2 over LocalSocket refused, method missing), MAE records that once and falls back
 * to recompile + reconnect. Nothing else is built on this until it is proven.
 */
class XrayApiClient(private val socketPath: String) {

    sealed class Result {
        object Ok : Result()
        data class Failed(val stage: String, val detail: String) : Result()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
            .socketFactory(LocalSocketFactory(socketPath))
            // The host name is a placeholder; the socket factory ignores the address.
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) })
            // One connection per call: reusing a pooled connection over the LocalSocket adapter
            // failed on the phone (UnsupportedOperationException on the second call, 2026-09-29).
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun overrideBalancerTarget(balancerTag: String, target: String): Result = withContext(Dispatchers.IO) {
        if (!java.io.File(socketPath).exists()) return@withContext Result.Failed("socket", "api socket was not created")
        val body = GrpcWire.frame(GrpcWire.overrideBalancerTarget(balancerTag, target))
        val req = Request.Builder()
            .url("http://xray-api${GrpcWire.OVERRIDE_BALANCER_PATH}")
            .header("te", "trailers")
            .post(body.toRequestBody("application/grpc".toMediaType()))
            .build()
        runCatching { client.connectionPool.evictAll() }
        try {
            client.newCall(req).execute().use { r ->
                if (r.code != 200) return@use Result.Failed("http", "HTTP ${r.code}")
                r.body?.bytes()
                val status = r.header("grpc-status") ?: runCatching { r.trailers()["grpc-status"] }.getOrNull()
                if (status == "0") Result.Ok
                else Result.Failed("grpc", "grpc-status=$status ${r.header("grpc-message") ?: runCatching { r.trailers()["grpc-message"] }.getOrNull().orEmpty()}")
            }
        } catch (e: Exception) {
            android.util.Log.w("XrayApiClient", "override $balancerTag failed", e)
            Result.Failed("transport", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }
}

/** Hands OkHttp a [Socket] that is really an AF_UNIX filesystem socket. */
private class LocalSocketFactory(private val path: String) : SocketFactory() {
    override fun createSocket(): Socket = LocalSocketAdapter(path)
    override fun createSocket(host: String?, port: Int): Socket = LocalSocketAdapter(path).also { it.connect(null) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = createSocket(host, port)
    override fun createSocket(host: InetAddress?, port: Int) = createSocket(null as String?, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = createSocket(null as String?, port)
}

private class LocalSocketAdapter(private val path: String) : Socket() {
    private val local = LocalSocket()
    @Volatile private var connected = false
    @Volatile private var closed = false

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        local.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        connected = true
    }
    override fun getInputStream(): InputStream = local.inputStream
    override fun getOutputStream(): OutputStream = local.outputStream
    override fun isConnected() = connected
    override fun isClosed() = closed
    override fun isInputShutdown() = local.isInputShutdown
    override fun isOutputShutdown() = local.isOutputShutdown
    override fun shutdownInput() = local.shutdownInput()
    override fun shutdownOutput() = local.shutdownOutput()
    override fun setSoTimeout(timeout: Int) { runCatching { local.soTimeout = timeout } }
    override fun getSoTimeout(): Int = runCatching { local.soTimeout }.getOrDefault(0)
    override fun setTcpNoDelay(on: Boolean) {}
    override fun getInetAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    override fun getLocalAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    override fun getPort() = 1
    override fun getLocalPort() = 1
    override fun close() {
        closed = true
        runCatching { local.close() }
    }
}
