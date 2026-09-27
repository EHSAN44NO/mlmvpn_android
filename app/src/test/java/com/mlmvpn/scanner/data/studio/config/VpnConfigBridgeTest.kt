package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.utils.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the `VpnConfig` -> `ConfigSpec` -> `streamSettings` path.
 *
 * These are not tests of new behaviour. Every assertion here was read off `XrayJsonGenerator` as it
 * stood *before* it delegated to [ConfigBuilder], and they exist so the delegation can be shown to
 * preserve it rather than asserted to. The connect path has 22 call sites across the app and no way
 * to be exercised offline, so "it compiles" was not enough evidence to change it on.
 *
 * Two assertions deliberately encode a **fix** rather than the old behaviour, and are marked where
 * they appear: the old `generateAntiSanctionConfig` and `generateMultiConfig` had no xhttp branch at
 * all, and `generateMultiConfig` sent a different User-Agent.
 */
class VpnConfigBridgeTest {

    private fun ws() = VpnConfig(
        protocol = "vless",
        name = "Node 1",
        address = "edge.example.com",
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        network = "ws",
        wsHost = "edge.example.com",
        wsPath = "/tunnel",
        tls = "tls",
        sni = "edge.example.com",
        fingerprint = "chrome",
    )

    private val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Test
    fun `ws over tls matches the shape the generator built`() {
        val s = ConfigBuilder.buildStreamSettings(ws().toConfigSpec())
        assertEquals("ws", s.getString("network"))
        assertEquals("tls", s.getString("security"))

        val wsSettings = s.getJSONObject("wsSettings")
        assertEquals("/tunnel", wsSettings.getString("path"))
        assertEquals("edge.example.com", wsSettings.getString("host"))
        assertEquals(CHROME_UA, wsSettings.getJSONObject("headers").getString("User-Agent"))
        // The deprecated spelling must not come back: the core migrated Host to the independent
        // field and warns when it appears in headers.
        assertFalse(wsSettings.getJSONObject("headers").has("Host"))

        val tls = s.getJSONObject("tlsSettings")
        assertEquals("edge.example.com", tls.getString("serverName"))
        assertEquals("chrome", tls.getString("fingerprint"))
    }

    @Test
    fun `empty path becomes slash, as the generator did`() {
        val s = ConfigBuilder.buildStreamSettings(ws().copy(wsPath = "").toConfigSpec())
        assertEquals("/", s.getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun `serverName falls back to the ws host when sni is empty`() {
        val s = ConfigBuilder.buildStreamSettings(ws().copy(sni = "").toConfigSpec())
        assertEquals("edge.example.com", s.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun `fingerprint defaults to chrome when the link carries none`() {
        val s = ConfigBuilder.buildStreamSettings(ws().copy(fingerprint = "").toConfigSpec())
        assertEquals("chrome", s.getJSONObject("tlsSettings").getString("fingerprint"))
    }

    @Test
    fun `h2 is dropped from alpn on ws`() {
        val s = ConfigBuilder.buildStreamSettings(ws().copy(alpn = "h2,http/1.1").toConfigSpec())
        val alpn = s.getJSONObject("tlsSettings").getJSONArray("alpn")
        assertEquals(1, alpn.length())
        assertEquals("http/1.1", alpn.getString(0))
    }

    @Test
    fun `xhttp host falls back to the ws host and auto becomes packet-up`() {
        val c = ws().copy(network = "xhttp", xhttpPath = "/x", xhttpHost = "", xhttpMode = "auto")
        val x = ConfigBuilder.buildStreamSettings(c.toConfigSpec()).getJSONObject("xhttpSettings")
        assertEquals("edge.example.com", x.getString("host"))
        assertEquals("/x", x.getString("path"))
        // Cloudflare buffers request bodies; packet-up is the only mode that survives a Worker.
        assertEquals("packet-up", x.getString("mode"))
    }

    @Test
    fun `xhttp extra tuning is passed through as an object`() {
        val c = ws().copy(network = "xhttp", xhttpExtra = """{"scMaxEachPostBytes":1000000}""")
        val x = ConfigBuilder.buildStreamSettings(c.toConfigSpec()).getJSONObject("xhttpSettings")
        assertEquals(1000000, x.getJSONObject("extra").getInt("scMaxEachPostBytes"))
    }

    @Test
    fun `malformed xhttp extra is ignored rather than throwing`() {
        val c = ws().copy(network = "xhttp", xhttpExtra = "{not json")
        val x = ConfigBuilder.buildStreamSettings(c.toConfigSpec()).getJSONObject("xhttpSettings")
        assertFalse(x.has("extra"))
        assertEquals("packet-up", x.getString("mode"))
    }

    /**
     * A FIX, not old behaviour: `generateAntiSanctionConfig` and `generateMultiConfig` had no xhttp
     * branch, so they emitted `network: "xhttp"` with no settings object at all.
     */
    @Test
    fun `every path now produces xhttpSettings`() {
        val s = ConfigBuilder.buildStreamSettings(ws().copy(network = "xhttp").toConfigSpec())
        assertTrue(s.has("xhttpSettings"))
    }

    @Test
    fun `trojan takes its password from the uuid field`() {
        val c = ws().copy(protocol = "trojan", uuid = "s3cret")
        val out = ConfigBuilder.buildXrayOutbound(c.toConfigSpec())
        assertEquals("trojan", out.getString("protocol"))
        assertEquals(
            "s3cret",
            out.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("password"),
        )
    }

    @Test
    fun `vless flow survives the round trip`() {
        val c = ws().copy(flow = "xtls-rprx-vision")
        val user = ConfigBuilder.buildXrayOutbound(c.toConfigSpec())
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals("xtls-rprx-vision", user.getString("flow"))
    }

    @Test
    fun `reality produces realitySettings and no tlsSettings`() {
        val c = ws().copy(tls = "reality", publicKey = "PUBKEY", shortId = "", spiderX = "")
        val s = ConfigBuilder.buildStreamSettings(c.toConfigSpec())
        assertEquals("reality", s.getString("security"))
        assertFalse(s.has("tlsSettings"))
        val r = s.getJSONObject("realitySettings")
        assertEquals("PUBKEY", r.getString("publicKey"))
        // Present-but-empty is a legitimate REALITY config; Xray wants the key there.
        assertTrue(r.has("shortId"))
        assertEquals("/", r.getString("spiderX"))
    }

    @Test
    fun `security none emits no tls block at all`() {
        val s = ConfigBuilder.buildStreamSettings(ws().copy(tls = "none").toConfigSpec())
        assertFalse(s.has("security"))
        assertFalse(s.has("tlsSettings"))
    }
}
