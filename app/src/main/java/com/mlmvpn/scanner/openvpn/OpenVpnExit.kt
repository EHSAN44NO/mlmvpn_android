package com.mlmvpn.scanner.openvpn

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One small request that proves data crosses the tunnel and says where it comes out.
 *
 * Plain HTTP on purpose: this box's TLS-over-a-socket path is not something to rely on in a
 * probe, and the answer is only an address and a country code. Inside the tunnel nothing on the
 * user's line can see or alter it.
 */
object OpenVpnExit {
    private const val HOST = "ip-api.com"

    /** (ip, country code) or null. */
    fun probe(timeoutMs: Int): Pair<String, String?>? {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, 80), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write("GET /json/?fields=status,countryCode,query HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val body = text.substringAfter("\r\n\r\n", "")
            val start = body.indexOf('{')
            if (start < 0) return null
            val o = JSONObject(body.substring(start, body.lastIndexOf('}') + 1))
            if (o.optString("status") != "success") return null
            val ip = o.optString("query").takeIf { it.isNotBlank() } ?: return null
            return ip to o.optString("countryCode").takeIf { it.length == 2 }
        }
    }
}
