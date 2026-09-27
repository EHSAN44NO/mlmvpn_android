package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.domain.ConfigSpec
import com.mlmvpn.scanner.data.studio.domain.ProtocolProfile
import com.mlmvpn.scanner.data.studio.domain.ProtocolType
import com.mlmvpn.scanner.data.studio.domain.SecurityProfile
import com.mlmvpn.scanner.data.studio.domain.SecurityType
import com.mlmvpn.scanner.data.studio.domain.TransportProfile
import com.mlmvpn.scanner.data.studio.domain.TransportType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed inputs, fixed outputs, for the one builder that both the connect path and the emit path use.
 *
 * The point is not coverage for its own sake. `ConfigBuilder` exists to collapse five copies of this
 * logic into one, and the risk that replaces "the five copies disagree" is "the one copy silently
 * changes". These fixtures are what makes such a change loud: every assertion here is a link or an
 * outbound that a real client has to accept, so an edit that alters one is either a deliberate fix
 * with its fixture updated in the same commit, or a regression caught before it ships.
 *
 * The same fixture values are the ones a Node test will check the worker's substitution against, so
 * the two implementations cannot drift apart (plan §5.2).
 */
class ConfigBuilderGoldenTest {

    private fun vlessWs(
        security: SecurityProfile = SecurityProfile(SecurityType.TLS, sni = "edge.example.com", fingerprint = "chrome"),
        transport: TransportProfile = TransportProfile(TransportType.WS, mapOf("path" to "/tunnel", "host" to "edge.example.com")),
    ) = ConfigSpec(
        protocol = ProtocolProfile(ProtocolType.VLESS, "11111111-2222-3333-4444-555555555555"),
        transport = transport,
        security = security,
        host = "edge.example.com",
        port = 443,
        remark = "Node 1",
    )

    // ---------------------------------------------------------------------- URI

    @Test
    fun `vless over ws with tls`() {
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@edge.example.com:443" +
                "?encryption=none&type=ws&path=%2Ftunnel&host=edge.example.com" +
                "&security=tls&sni=edge.example.com&fp=chrome#Node%201",
            ConfigBuilder.buildUri(vlessWs()),
        )
    }

    @Test
    fun `trojan over ws with tls`() {
        val spec = vlessWs().copy(
            protocol = ProtocolProfile(ProtocolType.TROJAN, "s3cret pass"),
        )
        val uri = ConfigBuilder.buildUri(spec)
        assertTrue(uri.startsWith("trojan://s3cret%20pass@edge.example.com:443?"))
        // encryption=none is a vless concept and must not leak into trojan.
        assertFalse(uri.contains("encryption="))
    }

    @Test
    fun `vless over xhttp forces packet-up`() {
        val spec = vlessWs(
            transport = TransportProfile(TransportType.XHTTP, mapOf("path" to "/x", "mode" to "auto")),
        )
        // "auto" is not a mode the Worker can serve; Cloudflare buffers request bodies so
        // stream-up and stream-one hang. packet-up must win.
        assertTrue(ConfigBuilder.buildUri(spec).contains("mode=packet-up"))
    }

    @Test
    fun `grpc carries serviceName and no path`() {
        val spec = vlessWs(
            transport = TransportProfile(TransportType.GRPC, mapOf("serviceName" to "grpcsvc", "mode" to "multi")),
        )
        val uri = ConfigBuilder.buildUri(spec)
        assertTrue(uri.contains("type=grpc"))
        assertTrue(uri.contains("serviceName=grpcsvc"))
        assertFalse(uri.contains("path="))
    }

    @Test
    fun `reality emits pbk sid and spx`() {
        val spec = vlessWs(
            security = SecurityProfile(
                SecurityType.REALITY, sni = "www.example.org", fingerprint = "chrome",
                extra = mapOf("pbk" to "PUBKEY", "sid" to "ab12", "spx" to "/"),
            ),
        )
        val uri = ConfigBuilder.buildUri(spec)
        assertTrue(uri.contains("security=reality"))
        assertTrue(uri.contains("pbk=PUBKEY"))
        assertTrue(uri.contains("sid=ab12"))
    }

    @Test
    fun `security none is explicit`() {
        val spec = vlessWs(security = SecurityProfile(SecurityType.NONE))
        val uri = ConfigBuilder.buildUri(spec)
        assertTrue(uri.contains("security=none"))
        assertFalse(uri.contains("sni="))
    }

    @Test
    fun `ipv6 host is bracketed in the authority`() {
        val spec = vlessWs().copy(host = "2606:4700:4700::1111")
        assertTrue(ConfigBuilder.buildUri(spec).contains("@[2606:4700:4700::1111]:443"))
    }

    @Test
    fun `vmess is a base64 object not a query string`() {
        val spec = vlessWs().copy(
            protocol = ProtocolProfile(ProtocolType.VMESS, "abc-uuid", mapOf("alterId" to "0")),
        )
        val uri = ConfigBuilder.buildUri(spec)
        assertTrue(uri.startsWith("vmess://"))
        val json = JSONObject(String(decodeB64(uri.removePrefix("vmess://")), Charsets.UTF_8))
        assertEquals("abc-uuid", json.getString("id"))
        assertEquals("ws", json.getString("net"))
        assertEquals("tls", json.getString("tls"))
    }

    @Test
    fun `shadowsocks encodes method and password in the userinfo`() {
        val spec = vlessWs().copy(
            protocol = ProtocolProfile(ProtocolType.SHADOWSOCKS, "pw", mapOf("method" to "aes-256-gcm")),
        )
        val uri = ConfigBuilder.buildUri(spec)
        assertTrue(uri.startsWith("ss://"))
        val userinfo = uri.removePrefix("ss://").substringBefore('@')
        assertEquals("aes-256-gcm:pw", String(decodeB64(userinfo), Charsets.UTF_8))
    }

    // ---------------------------------------------------------------------- Xray JSON

    @Test
    fun `ws stream settings match what the connect path builds`() {
        val s = ConfigBuilder.buildStreamSettings(vlessWs())
        assertEquals("ws", s.getString("network"))
        assertEquals("tls", s.getString("security"))
        assertEquals("/tunnel", s.getJSONObject("wsSettings").getString("path"))
        assertEquals("edge.example.com", s.getJSONObject("wsSettings").getString("host"))
        // The Host header must NOT also be set inside headers: the core deprecates it and setting
        // both puts the same value in two places, one of which is going away.
        assertFalse(s.getJSONObject("wsSettings").getJSONObject("headers").has("Host"))
        assertEquals("edge.example.com", s.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun `h2 is stripped from alpn on websocket`() {
        val spec = vlessWs(
            security = SecurityProfile(
                SecurityType.TLS, sni = "edge.example.com",
                alpn = listOf("h2", "http/1.1"),
            ),
        )
        val alpn = ConfigBuilder.buildStreamSettings(spec).getJSONObject("tlsSettings").getJSONArray("alpn")
        assertEquals(1, alpn.length())
        assertEquals("http/1.1", alpn.getString(0))
        // and the link must not advertise it either -- an emitted config with h2 on ws is dead on
        // arrival in whatever client opens it.
        assertFalse(ConfigBuilder.buildUri(spec).contains("h2"))
    }

    @Test
    fun `h2 survives on a transport that can actually use it`() {
        val spec = vlessWs(
            security = SecurityProfile(SecurityType.TLS, sni = "e.example.com", alpn = listOf("h2", "http/1.1")),
            transport = TransportProfile(TransportType.XHTTP, mapOf("path" to "/x")),
        )
        val alpn = ConfigBuilder.buildStreamSettings(spec).getJSONObject("tlsSettings").getJSONArray("alpn")
        assertEquals(2, alpn.length())
    }

    @Test
    fun `vless flow reaches the outbound user`() {
        val spec = vlessWs().copy(
            protocol = ProtocolProfile(ProtocolType.VLESS, "uuid-1", mapOf("flow" to "xtls-rprx-vision")),
        )
        val user = ConfigBuilder.buildXrayOutbound(spec)
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals("xtls-rprx-vision", user.getString("flow"))
    }

    @Test
    fun `reality outbound sends an empty short id rather than omitting it`() {
        val spec = vlessWs(
            security = SecurityProfile(SecurityType.REALITY, sni = "www.example.org", extra = mapOf("pbk" to "K")),
        )
        val reality = ConfigBuilder.buildStreamSettings(spec).getJSONObject("realitySettings")
        assertTrue(reality.has("shortId"))
        assertEquals("", reality.getString("shortId"))
        assertEquals("/", reality.getString("spiderX"))
    }

    @Test
    fun `tcp has no transport settings block`() {
        val spec = vlessWs(transport = TransportProfile(TransportType.TCP))
        val s = ConfigBuilder.buildStreamSettings(spec)
        assertEquals("tcp", s.getString("network"))
        assertFalse(s.has("tcpSettings"))
    }

    @Test
    fun `shadowsocks outbound uses servers not vnext`() {
        val spec = vlessWs().copy(
            protocol = ProtocolProfile(ProtocolType.SHADOWSOCKS, "pw", mapOf("method" to "chacha20-ietf-poly1305")),
        )
        val out = ConfigBuilder.buildXrayOutbound(spec)
        assertEquals("shadowsocks", out.getString("protocol"))
        val server = out.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("chacha20-ietf-poly1305", server.getString("method"))
    }

    /** Accepts both alphabets and tolerates missing padding, so one helper covers every fixture. */
    private fun decodeB64(s: String): ByteArray {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val norm = s.replace('-', '+').replace('_', '/').filter { it != '=' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in norm) {
            val v = abc.indexOf(c)
            require(v >= 0) { "bad base64 char: $c" }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
