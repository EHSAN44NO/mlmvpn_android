package com.mlmvpn.core.geph

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/** An error the engine answered with, carrying its own words (broker errors arrive flattened). */
class GephRpcException(message: String) : IOException(message)

/** `conn_info`: the engine's own view of the tunnel. */
data class GephConnInfo(val state: String, val sessions: List<GephSession>) {
    val connected: Boolean get() = state == "Connected" && sessions.isNotEmpty()

    companion object {
        fun parse(json: Any?): GephConnInfo {
            val o = json as? JSONObject ?: return GephConnInfo("Disconnected", emptyList())
            val sessions = o.optJSONArray("sessions")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(GephSession::parse) }
            }.orEmpty()
            return GephConnInfo(o.optString("state", "Disconnected"), sessions)
        }
    }
}

/** One of the (up to six) sessions the engine keeps open to its exit. */
data class GephSession(
    val protocol: String,
    val country: String,
    val city: String,
    val exitAddress: String,
    val load: Double,
    /** Null for a direct session; meeklike ones report 0.0.0.0:0. */
    val bridge: String?,
) {
    companion object {
        fun parse(o: JSONObject): GephSession {
            val exit = o.optJSONObject("exit") ?: JSONObject()
            return GephSession(
                protocol = o.optString("protocol"),
                country = exit.optString("country").uppercase(),
                city = exit.optString("city"),
                exitAddress = exit.optString("b2e_listen").substringBeforeLast(':'),
                load = exit.optDouble("load", 0.0),
                bridge = o.optString("bridge").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

/**
 * The engine's control protocol, over the unix socket its config names.
 *
 * Newline-delimited JSON-RPC 2.0 (libraries/nanorpc-sillad): one request per connection, one
 * answer line back. `{"error": ...}` becomes a [GephRpcException] with the engine's message; a
 * broker error forwarded through `broker_rpc` arrives as its Display string ("account code has
 * been replaced"), which is what the UI shows.
 *
 * The method set is the engine's ControlProtocol (client_control.rs) -- there is no `user_info`;
 * account questions go through [brokerRpc] like the official apps do.
 */
class GephControl(private val socketPath: String) {

    @Throws(IOException::class)
    fun call(method: String, params: JSONArray = JSONArray(), timeoutMs: Int = 10_000): Any? {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = timeoutMs
            val request = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params)
                .put("id", 1)
            socket.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            val line = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)).readLine()
                ?: throw IOException("the engine closed the control connection")
            val answer = JSONObject(line)
            answer.optJSONObject("error")?.let { throw GephRpcException(it.optString("message", "error")) }
            return answer.opt("result")?.takeIf { it != JSONObject.NULL }
        }
    }

    /** Whether anything is listening yet. Cheap: one connect, no request. */
    fun reachable(): Boolean = runCatching {
        LocalSocket().use { it.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)) }
    }.isSuccess

    fun connInfo(timeoutMs: Int = 4_000): GephConnInfo = GephConnInfo.parse(call("conn_info", timeoutMs = timeoutMs))

    /** Only `ping` (seconds), `total_rx_bytes` and `total_tx_bytes` exist; anything else reads 0. */
    fun statNum(name: String, timeoutMs: Int = 3_000): Double =
        (call("stat_num", JSONArray().put(name), timeoutMs) as? Number)?.toDouble() ?: 0.0

    /** `traffic` is the only series: bytes per second, receive and send together, ≤600 bins. */
    fun statHistory(name: String = "traffic"): List<Double> {
        val arr = unwrapOk(call("stat_history", JSONArray().put(name), 4_000)) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.optDouble(it, 0.0) }
    }

    fun recentLogs(): List<String> {
        val arr = call("recent_logs", timeoutMs = 8_000) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    fun netStatus(timeoutMs: Int = 15_000): JSONObject? =
        unwrapOk(call("net_status", timeoutMs = timeoutMs)) as? JSONObject

    fun latestNews(lang: String): JSONArray? =
        unwrapOk(call("latest_news", JSONArray().put(lang), 15_000)) as? JSONArray

    fun startRegistration(): Int =
        (unwrapOk(call("start_registration", timeoutMs = 30_000)) as? Number)?.toInt()
            ?: throw IOException("no registration index")

    /** (progress 0..1, the new account code once the puzzle is solved). */
    fun pollRegistration(index: Int): Pair<Double, String?> {
        val o = unwrapOk(call("poll_registration", JSONArray().put(index), 10_000)) as? JSONObject
            ?: return 0.0 to null
        return o.optDouble("progress", 0.0) to o.optString("secret").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Any broker method, through the engine -- fronted, or tunnelled once connected. */
    fun brokerRpc(method: String, params: JSONArray, timeoutMs: Int = 20_000): Any? =
        unwrapOk(call("broker_rpc", JSONArray().put(method).put(params), timeoutMs))

    /** Asks the engine to exit (it does, ~100 ms later). */
    fun stop() {
        runCatching { call("stop", timeoutMs = 2_000) }
    }

    companion object {
        /** Some builds wrap a Result as `{"Ok": value}`; accept both shapes. */
        fun unwrapOk(value: Any?): Any? {
            if (value is JSONObject && value.length() == 1 && value.has("Ok")) {
                return value.opt("Ok")?.takeIf { it != JSONObject.NULL }
            }
            if (value is JSONObject && value.length() == 1 && value.has("Err")) {
                throw GephRpcException(value.opt("Err").toString())
            }
            return value
        }
    }
}
