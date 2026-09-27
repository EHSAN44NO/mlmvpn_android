package com.mlmvpn.scanner.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Gemini exit, as [XrayJsonGenerator] writes it.
 *
 * With one set up (and the Gemini switch on), Gemini's domains must go to a VLESS outbound aimed at
 * the exit worker -- reached through the tunnel's own clean address and TLS shape, so it gets
 * through wherever the tunnel does -- and that rule must come before the Google rule, which covers
 * the same names. Without one, nothing may change.
 */
class XrayGeminiExitTest {

    private val exit = XrayJsonGenerator.GeminiExitRoute(
        host = "cloud-sync-1a2b3c.acme.workers.dev",
        path = "p4thS3cretXyz",
        id = "0f0e0d0c-0b0a-0908-0706-050403020100",
    )

    @Before
    fun setUp() {
        XrayJsonGenerator.googleFix = true
        XrayJsonGenerator.geminiExit = exit
    }

    @After
    fun tearDown() {
        XrayJsonGenerator.googleFix = true
        XrayJsonGenerator.geminiExit = null
    }

    private fun worker(port: Int = 8443, tls: String = "tls") = VpnConfig(
        protocol = "vless",
        name = "BPB",
        address = "172.67.154.11",
        port = port,
        uuid = "11111111-2222-3333-4444-555555555555",
        network = "ws",
        wsHost = "edge.example.workers.dev",
        wsPath = "/vl/abc?ed=2560",
        tls = tls,
        sni = "edge.example.workers.dev",
        fingerprint = "chrome",
    )

    private fun JSONObject.outbound(tag: String): JSONObject? {
        val outs = getJSONArray("outbounds")
        return (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.optString("tag") == tag }
    }

    private fun JSONObject.rules(): List<JSONObject> {
        val rules = getJSONObject("routing").getJSONArray("rules")
        return (0 until rules.length()).map { rules.getJSONObject(it) }
    }

    private fun JSONObject.domains(): List<String> =
        optJSONArray("domain")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()

    private fun List<JSONObject>.indexOfTag(tag: String) = indexOfFirst { it.optString("outboundTag") == tag }

    private fun JSONObject.server(): JSONObject =
        getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)

    @Test
    fun `share link - Gemini goes to the exit, through the tunnel's own clean address`() {
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker(), localPort = 10808))
        val out = json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG)
        assertNotNull(out)
        assertEquals("vless", out!!.getString("protocol"))

        val server = out.server()
        assertEquals("the tunnel's clean IP, which works on this line", "172.67.154.11", server.getString("address"))
        assertEquals(8443, server.getInt("port"))
        assertEquals(exit.id, server.getJSONArray("users").getJSONObject(0).getString("id"))

        val stream = out.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("case is only camouflage", exit.host, tls.getString("serverName").lowercase())
        assertEquals("chrome", tls.getString("fingerprint"))
        assertEquals("/" + exit.path, stream.getJSONObject("wsSettings").getString("path"))
        assertEquals(exit.host, stream.getJSONObject("wsSettings").getString("host").lowercase())
        assertFalse("names pass through: the exit resolves them from America", out.has("targetStrategy"))

        val rules = json.rules()
        val gemini = rules.indexOfTag(XrayJsonGenerator.GEMINI_EXIT_TAG)
        val google = rules.indexOfTag(XrayJsonGenerator.GOOGLE_V4_TAG)
        assertTrue("the Gemini rule must win over the Google one", gemini in 0 until google)
        val names = rules[gemini].domains()
        assertTrue(names.contains("domain:gemini.google.com"))
        assertTrue(names.contains("domain:robinfrontend-pa.googleapis.com"))
        assertFalse("YouTube and the rest of Google stay put", names.contains("domain:google.com"))
    }

    @Test
    fun `a worker on plain HTTP reaches the exit on 443`() {
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker(port = 80, tls = "none"), localPort = 10808))
        val out = json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG)!!
        assertEquals(443, out.server().getInt("port"))
        assertEquals("tls", out.getJSONObject("streamSettings").getString("security"))
    }

    @Test
    fun `a pinned server name carries its socket options over`() {
        val byName = worker().copy(address = "edge.example.workers.dev")
        val json = JSONObject(
            XrayJsonGenerator.generateConfig(
                byName, localPort = 10808, pinnedHostIps = mapOf("edge.example.workers.dev" to listOf("104.16.1.1")),
            )
        )
        val out = json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG)!!
        assertEquals("edge.example.workers.dev", out.server().getString("address"))
        assertEquals("ForceIP", out.getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy"))
    }

    @Test
    fun `no exit set up - no outbound and no rule`() {
        XrayJsonGenerator.geminiExit = null
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker(), localPort = 10808))
        assertNull(json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG))
        assertEquals(-1, json.rules().indexOfTag(XrayJsonGenerator.GEMINI_EXIT_TAG))
    }

    @Test
    fun `switch off - the exit is not used either`() {
        XrayJsonGenerator.googleFix = false
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker(), localPort = 10808))
        assertNull(json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG))
    }

    @Test
    fun `a real server keeps Gemini as it was - it has its own exit`() {
        val server = worker().copy(network = "tcp", tls = "reality")
        val json = JSONObject(XrayJsonGenerator.generateConfig(server, localPort = 10808))
        assertNull(json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG))
    }

    /** BPB's Xray-subscription shape, with a real server entry for the exit to borrow. */
    private fun bpbJson(): String = JSONObject().apply {
        put("inbounds", JSONArray().put(JSONObject().put("tag", "mixed-in").put("port", 10808).put("protocol", "mixed")))
        put("outbounds", JSONArray().apply {
            put(JSONObject().put("tag", "proxy").put("protocol", "vless")
                .put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", "104.21.4.61").put("port", 443)
                        .put("users", JSONArray().put(JSONObject().put("id", "x").put("encryption", "none")))
                )))
                .put("streamSettings", JSONObject().put("network", "ws").put("security", "tls")
                    .put("tlsSettings", JSONObject().put("serverName", "edge.example.workers.dev").put("fingerprint", "firefox"))))
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        })
        put("dns", JSONObject().put("servers", JSONArray().put("https://8.8.8.8/dns-query")))
        put("routing", JSONObject().put("rules", JSONArray()
            .put(JSONObject().put("network", "udp").put("port", "443").put("outboundTag", "block"))
            .put(JSONObject().put("network", "tcp,udp").put("outboundTag", "proxy"))))
    }.toString()

    @Test
    fun `whole JSON config - the exit rule goes just before the Google rule`() {
        val json = JSONObject(XrayJsonGenerator.applyGoogleFix(bpbJson())!!)
        val out = json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG)!!
        assertEquals("104.21.4.61", out.server().getString("address"))
        assertEquals("their fingerprint, their shape", "firefox",
            out.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("fingerprint"))
        val rules = json.rules()
        assertEquals(rules.indexOfTag(XrayJsonGenerator.GOOGLE_V4_TAG) - 1, rules.indexOfTag(XrayJsonGenerator.GEMINI_EXIT_TAG))
        assertNull("never twice", XrayJsonGenerator.applyGoogleFix(json.toString()))
    }

    @Test
    fun `anti-sanction - the exit goes ahead of the Google rule`() {
        val json = JSONObject(
            XrayJsonGenerator.generateAntiSanctionConfig(
                worker(), localPort = 10808, sanctionedDomains = listOf("domain:gemini.google.com"),
            )
        )
        assertNotNull(json.outbound(XrayJsonGenerator.GEMINI_EXIT_TAG))
        val rules = json.rules()
        assertTrue(rules.indexOfTag(XrayJsonGenerator.GEMINI_EXIT_TAG) in 0 until rules.indexOfTag(XrayJsonGenerator.GOOGLE_V4_TAG))
    }

    @Test
    fun `the stored form round-trips, and junk reads as none`() {
        assertEquals(exit, XrayJsonGenerator.GeminiExitRoute.decode(exit.encode()))
        assertNull(XrayJsonGenerator.GeminiExitRoute.decode(null))
        assertNull(XrayJsonGenerator.GeminiExitRoute.decode("host|path"))
        assertNull(XrayJsonGenerator.GeminiExitRoute.decode("host||id"))
    }
}
