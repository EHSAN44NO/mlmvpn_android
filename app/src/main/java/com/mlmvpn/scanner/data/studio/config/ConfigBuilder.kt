package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.domain.ConfigSpec
import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import com.mlmvpn.scanner.data.studio.domain.SecurityType
import com.mlmvpn.scanner.data.studio.domain.TransportType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The one place a protocol x transport combination turns into something a client can use.
 *
 * Before this file the same knowledge lived in five places: three URI builders in `mlm_worker.js`
 * (`:833`, `:3475`, `:4308`), a JSON builder duplicated at `:715` and `:3552`, and
 * `XrayJsonGenerator`'s own `streamSettings`. That is not merely repetitive -- it means the config
 * this app **dials** and the config it **hands to someone else** are built by different code, so they
 * can disagree, and the way that failure presents is a link that works in our client and not in
 * theirs, or the reverse. Neither is diagnosable from the outside.
 *
 * So: two pure functions, no Android dependencies, no I/O, no mutable state -- which is also what
 * makes them testable against fixtures (`ConfigBuilderGoldenTest`) that the worker's own substitution
 * can be checked against.
 *
 * **The worker builds no URIs.** It stores a template rendered here and substitutes placeholders into
 * it. There is no second implementation to keep in step, because there is no second implementation.
 *
 * See `android/docs/CONFIG-STUDIO-PLAN.md` §5.2 and F3/F4.
 */
object ConfigBuilder {

    // ---------------------------------------------------------------------------------------------
    // URI
    // ---------------------------------------------------------------------------------------------

    /**
     * The shareable link.
     *
     * vmess is the odd one out and always has been: it carries a base64 JSON object rather than query
     * parameters, so it gets its own branch instead of being bent into the common shape.
     */
    fun buildUri(spec: ConfigSpec): String = when (spec.protocol.type) {
        ProtocolType.VMESS -> buildVmessUri(spec)
        ProtocolType.SHADOWSOCKS -> buildShadowsocksUri(spec)
        ProtocolType.VLESS, ProtocolType.TROJAN -> buildUserinfoUri(spec)
    }

    /** vless and trojan share a shape: credential in the userinfo, everything else in the query. */
    private fun buildUserinfoUri(spec: ConfigSpec): String {
        val q = LinkedHashMap<String, String>()
        if (spec.protocol.type == ProtocolType.VLESS) {
            q["encryption"] = "none"
            spec.protocol.extra["flow"]?.takeIf { it.isNotBlank() }?.let { q["flow"] = it }
        }
        q["type"] = spec.transport.type.wire
        q.putAll(transportQuery(spec))
        q.putAll(securityQuery(spec))

        val query = q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val frag = if (spec.remark.isBlank()) "" else "#" + enc(spec.remark)
        return "${spec.protocol.type.wire}://${enc(spec.protocol.credential)}@${hostPart(spec.host)}:${spec.port}?$query$frag"
    }

    private fun buildVmessUri(spec: ConfigSpec): String {
        val sec = spec.security
        val o = JSONObject().apply {
            put("v", "2")
            put("ps", spec.remark)
            put("add", spec.host)
            put("port", spec.port.toString())
            put("id", spec.protocol.credential)
            put("aid", spec.protocol.extra["alterId"] ?: "0")
            put("scy", spec.protocol.extra["security"] ?: "auto")
            put("net", spec.transport.type.wire)
            put("type", "none")
            put("host", spec.transport.settings["host"] ?: sec.sni.orEmpty())
            put("path", spec.transport.settings["path"] ?: "/")
            put("tls", if (sec.type == SecurityType.NONE) "" else sec.type.wire)
            put("sni", sec.sni.orEmpty())
            put("fp", sec.fingerprint.orEmpty())
            if (sec.alpn.isNotEmpty()) put("alpn", effectiveAlpn(spec).joinToString(","))
        }
        return "vmess://" + base64(o.toString().toByteArray(Charsets.UTF_8), urlSafe = false, pad = true)
    }

    private fun buildShadowsocksUri(spec: ConfigSpec): String {
        val method = spec.protocol.extra["method"] ?: "aes-256-gcm"
        val userinfo = base64(
            "$method:${spec.protocol.credential}".toByteArray(Charsets.UTF_8),
            urlSafe = true, pad = false,
        )
        val frag = if (spec.remark.isBlank()) "" else "#" + enc(spec.remark)
        return "ss://$userinfo@${hostPart(spec.host)}:${spec.port}$frag"
    }

    private fun transportQuery(spec: ConfigSpec): Map<String, String> {
        val s = spec.transport.settings
        return when (spec.transport.type) {
            TransportType.WS, TransportType.HTTPUPGRADE -> buildMap {
                put("path", s["path"] ?: "/")
                s["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }
            TransportType.XHTTP -> buildMap {
                put("path", s["path"] ?: "/")
                s["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                put("mode", xhttpMode(s))
                s["extra"]?.takeIf { it.isNotBlank() }?.let { put("extra", it) }
            }
            TransportType.GRPC -> buildMap {
                put("serviceName", s["serviceName"] ?: "")
                s["mode"]?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
            }
            TransportType.TCP -> emptyMap()
        }
    }

    private fun securityQuery(spec: ConfigSpec): Map<String, String> {
        val sec = spec.security
        if (sec.type == SecurityType.NONE) return mapOf("security" to "none")
        return buildMap {
            put("security", sec.type.wire)
            sec.sni?.takeIf { it.isNotBlank() }?.let { put("sni", it) }
            sec.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fp", it) }
            effectiveAlpn(spec).takeIf { it.isNotEmpty() }?.let { put("alpn", it.joinToString(",")) }
            if (sec.allowInsecure) put("allowInsecure", "1")
            if (sec.type == SecurityType.REALITY) {
                sec.extra["pbk"]?.let { put("pbk", it) }
                put("sid", sec.extra["sid"].orEmpty())
                put("spx", sec.extra["spx"] ?: "/")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Xray JSON
    // ---------------------------------------------------------------------------------------------

    /** A complete outbound, untagged. The caller adds `tag` and any connect-path-only tuning. */
    fun buildXrayOutbound(spec: ConfigSpec): JSONObject = JSONObject().apply {
        put("protocol", if (spec.protocol.type == ProtocolType.SHADOWSOCKS) "shadowsocks" else spec.protocol.type.wire)
        put("settings", outboundSettings(spec))
        put("streamSettings", buildStreamSettings(spec))
    }

    private fun outboundSettings(spec: ConfigSpec): JSONObject {
        val p = spec.protocol
        return when (p.type) {
            ProtocolType.VLESS -> JSONObject().put(
                "vnext",
                JSONArray().put(JSONObject().apply {
                    put("address", spec.host)
                    put("port", spec.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", p.credential)
                        put("encryption", "none")
                        // Not decoration: a server configured for xtls-rprx-vision rejects a user
                        // that arrives without it.
                        p.extra["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                    }))
                }),
            )
            ProtocolType.VMESS -> JSONObject().put(
                "vnext",
                JSONArray().put(JSONObject().apply {
                    put("address", spec.host)
                    put("port", spec.port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", p.credential)
                        put("alterId", p.extra["alterId"]?.toIntOrNull() ?: 0)
                        put("security", p.extra["security"]?.ifBlank { "auto" } ?: "auto")
                    }))
                }),
            )
            ProtocolType.TROJAN -> JSONObject().put(
                "servers",
                JSONArray().put(JSONObject().apply {
                    put("address", spec.host)
                    put("port", spec.port)
                    put("password", p.credential)
                }),
            )
            ProtocolType.SHADOWSOCKS -> JSONObject().put(
                "servers",
                JSONArray().put(JSONObject().apply {
                    put("address", spec.host)
                    put("port", spec.port)
                    put("method", p.extra["method"] ?: "aes-256-gcm")
                    put("password", p.credential)
                }),
            )
        }
    }

    /**
     * `streamSettings` -- the half `XrayJsonGenerator` delegates here.
     *
     * Connect-path-only concerns are deliberately **not** in this function and stay with the caller:
     * the unfiltering cipher swap (which is about how *this* device dials) and the `sockopt`
     * happy-eyeballs block (which depends on IPs this device pre-resolved). Neither has any meaning in
     * a config handed to someone else, and folding them in here is how the shared builder would start
     * emitting device-specific links.
     */
    fun buildStreamSettings(spec: ConfigSpec): JSONObject = JSONObject().apply {
        put("network", spec.transport.type.wire)
        applySecurity(this, spec)
        transportSettings(spec)?.let { (key, value) -> put(key, value) }
    }

    private fun transportSettings(spec: ConfigSpec): Pair<String, JSONObject>? {
        val s = spec.transport.settings
        return when (spec.transport.type) {
            TransportType.WS -> "wsSettings" to JSONObject().apply {
                put("path", s["path"]?.ifBlank { null } ?: "/")
                // Host goes in the independent "host" field only. The core logs a deprecation
                // warning for a "Host" entry inside "headers" and setting both means the same value
                // in two places, one of which is on its way out.
                s["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                put("headers", JSONObject().put("User-Agent", BROWSER_UA))
            }
            TransportType.HTTPUPGRADE -> "httpupgradeSettings" to JSONObject().apply {
                put("path", s["path"]?.ifBlank { null } ?: "/")
                s["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }
            TransportType.XHTTP -> "xhttpSettings" to JSONObject().apply {
                put("host", s["host"].orEmpty())
                put("path", s["path"]?.ifBlank { null } ?: "/")
                // Packet-up tuning travels in the URI's ?extra={...}; on xray 26.x it belongs under
                // xhttpSettings.extra, so it is passed through as-is.
                s["extra"]?.takeIf { it.isNotBlank() }?.let {
                    runCatching { put("extra", JSONObject(it)) }
                }
                // Forced after the extra merge so a "mode" carried inside extra cannot win.
                put("mode", xhttpMode(s))
            }
            TransportType.GRPC -> "grpcSettings" to JSONObject().apply {
                put("serviceName", s["serviceName"].orEmpty())
                put("multiMode", s["mode"] == "multi")
            }
            // A raw TCP inbound cannot be hosted on a Worker at all, so there is nothing to
            // configure here; the spec still allows it so a Node pointing at a non-Worker endpoint
            // can use it.
            TransportType.TCP -> null
        }
    }

    /**
     * The XHTTP mode, resolved once for both the link and the JSON.
     *
     * Cloudflare buffers request bodies, so `stream-up` and `stream-one` hang with a TLS handshake
     * timeout to the destination; `packet-up` is the only mode that works on a Worker. `auto` is not
     * a mode a Worker can serve either, so it resolves here rather than being passed on.
     *
     * It lives in a function because the first version of this file did not have one, and the two
     * call sites drifted immediately: `buildStreamSettings` forced packet-up while `transportQuery`
     * used `?: "packet-up"`, which does not fire on a non-null `"auto"`. The emitted **link** said
     * `mode=auto` while the emitted **JSON** said `packet-up` -- the same config described two ways,
     * from one object, in one file. That is precisely the failure this whole class exists to remove,
     * reproduced inside it, and `ConfigBuilderGoldenTest` caught it on the first run.
     */
    private fun xhttpMode(settings: Map<String, String>): String =
        settings["mode"]?.takeIf { it.isNotBlank() && it != "auto" } ?: "packet-up"

    /**
     * The transport security block.
     *
     * **REALITY is not TLS with different parameters** -- Xray reads a completely different object,
     * and this is worth stating because getting it wrong is invisible. An earlier generation of this
     * code emitted `security` verbatim and then always filled in `tlsSettings`, so a
     * `security=reality` link produced a config in which REALITY was announced and none of REALITY's
     * inputs were present: no public key, no short id, no fingerprint, no spider path. Xray then has
     * nothing to perform the handshake with, so the outbound never comes up -- which is why such a
     * link imported cleanly, measured no delay at all, and refused to connect, while the identical
     * link worked in every other client.
     *
     * `fingerprint` is likewise not hardcoded to chrome. It is one of the values REALITY actually
     * varies, the link carries it in `fp`, and pinning it discards what the server expects.
     */
    private fun applySecurity(stream: JSONObject, spec: ConfigSpec) {
        val sec = spec.security
        if (sec.type == SecurityType.NONE) return
        stream.put("security", sec.type.wire)

        val fingerprint = sec.fingerprint?.ifBlank { null } ?: "chrome"
        val serverName = sec.sni?.ifBlank { null } ?: spec.transport.settings["host"] ?: spec.host

        if (sec.type == SecurityType.REALITY) {
            stream.put("realitySettings", JSONObject().apply {
                put("serverName", serverName)
                put("fingerprint", fingerprint)
                put("publicKey", sec.extra["pbk"].orEmpty())
                // Sent as "" rather than omitted when there is none: Xray wants the key present, and
                // an empty short id is a legitimate REALITY configuration.
                put("shortId", sec.extra["sid"].orEmpty())
                put("spiderX", sec.extra["spx"]?.ifBlank { null } ?: "/")
                put("show", false)
            })
            return
        }

        stream.put("tlsSettings", JSONObject().apply {
            put("serverName", serverName)
            put("fingerprint", fingerprint)
            if (sec.allowInsecure) put("allowInsecure", true)
            val alpn = effectiveAlpn(spec)
            if (alpn.isNotEmpty()) put("alpn", JSONArray().also { a -> alpn.forEach { a.put(it) } })
        })
    }

    /**
     * ALPN with `h2` removed on WebSocket, which is a correctness rule and not a preference.
     *
     * Xray's WebSocket transport speaks HTTP/1.1 only. If h2 is offered, Cloudflare negotiates it and
     * the dial dies with `websocket: protocol "h2" was given but is not supported`, followed by a
     * malformed-response error whose bytes are an HTTP/2 SETTINGS frame. The config validates fine and
     * fails only at dial time, which is what makes it look like a broken server rather than a broken
     * ALPN -- plenty of public ws links ship `alpn=h2,http/1.1` and are dead on arrival.
     *
     * It is filtered **here**, in the shared builder, rather than only on the connect path: a link
     * emitted with h2 on ws is broken in whichever client opens it, so emitting one would be handing
     * out a config that cannot work.
     */
    private fun effectiveAlpn(spec: ConfigSpec): List<String> =
        spec.security.alpn
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { spec.transport.type == TransportType.WS && it == "h2" }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Base64, written out rather than taken from `android.util.Base64`.
     *
     * Two reasons, and the second is the one that matters. `java.util.Base64` needs API 26 and this
     * app ships `minSdk 24`. And `android.util.Base64` is a framework class that a plain JVM unit
     * test sees only as a stub returning null -- so depending on it would mean the golden fixtures
     * could not run without Robolectric, which is a large dependency to take on so that twenty
     * string comparisons can execute. Keeping this object free of Android types is what lets the
     * fixtures be an ordinary, fast unit test.
     */
    private fun base64(bytes: ByteArray, urlSafe: Boolean, pad: Boolean): String {
        val abc = if (urlSafe) URL_SAFE_ALPHABET else STD_ALPHABET
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(abc[n ushr 18 and 63]).append(abc[n ushr 12 and 63])
                .append(abc[n ushr 6 and 63]).append(abc[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                sb.append(abc[n ushr 18 and 63]).append(abc[n ushr 12 and 63])
                if (pad) sb.append("==")
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                sb.append(abc[n ushr 18 and 63]).append(abc[n ushr 12 and 63]).append(abc[n ushr 6 and 63])
                if (pad) sb.append('=')
            }
        }
        return sb.toString()
    }

    private const val STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** IPv6 literals need brackets in a URI authority; anything else is passed through. */
    private fun hostPart(host: String): String =
        if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
}
