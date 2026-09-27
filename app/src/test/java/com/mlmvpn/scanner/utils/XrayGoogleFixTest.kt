package com.mlmvpn.scanner.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gemini / Google apps switch, as [XrayJsonGenerator] writes it.
 *
 * On, a Cloudflare-worker config must (1) send QUIC to the refusal outbound (see QuicRefuser) and
 * (2) reach Google's domains through a tunnel copy with `targetStrategy: UseIPv4`, resolved through
 * the tunnel -- the half that makes Gemini open (see `googleByIpv4`). Off, every config must be
 * exactly what it was before the switch existed.
 */
class XrayGoogleFixTest {

    @After
    fun restoreDefault() {
        XrayJsonGenerator.googleFix = true
    }

    private fun worker(network: String = "ws") = VpnConfig(
        protocol = "vless",
        name = "EDG-Auto",
        address = "edge.example.workers.dev",
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        network = network,
        wsHost = "edge.example.workers.dev",
        wsPath = "/tunnel",
        tls = "tls",
        sni = "edge.example.workers.dev",
        fingerprint = "chrome",
    )

    private fun JSONObject.outboundTags(): List<String> {
        val outs = getJSONArray("outbounds")
        return (0 until outs.length()).map { outs.getJSONObject(it).optString("tag") }
    }

    private fun JSONObject.outbound(tag: String): JSONObject? {
        val outs = getJSONArray("outbounds")
        return (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.optString("tag") == tag }
    }

    private fun JSONObject.rules(): List<JSONObject> {
        val rules = getJSONObject("routing").getJSONArray("rules")
        return (0 until rules.length()).map { rules.getJSONObject(it) }
    }

    private fun JSONObject.dnsServers(): List<Any> {
        val servers = getJSONObject("dns").getJSONArray("servers")
        return (0 until servers.length()).map { servers.get(it) }
    }

    private fun JSONObject.isUdp443(): Boolean =
        optString("network") == "udp" && optString("port") == "443"

    private fun JSONObject.domains(): List<String> =
        optJSONArray("domain")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()

    private fun List<JSONObject>.indexOfTag(tag: String) = indexOfFirst { it.optString("outboundTag") == tag }

    // --- share links ---------------------------------------------------------------------------

    @Test
    fun `switch on - QUIC goes to the refusal`() {
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker(), localPort = 10808))

        val refusal = json.outbound(XrayJsonGenerator.QUIC_REFUSAL_TAG)
        assertNotNull(refusal)
        assertEquals("freedom", refusal!!.getString("protocol"))
        val settings = refusal.getJSONObject("settings")
        assertEquals("127.0.0.1:${QuicRefuser.port()}", settings.getString("redirect"))
        val level = settings.getInt("userLevel").toString()
        assertTrue(json.getJSONObject("policy").getJSONObject("levels").has(level))
        assertFalse("level 0 is every other connection's and stays default",
            json.getJSONObject("policy").getJSONObject("levels").has("0"))

        val quicRules = json.rules().filter { it.optString("network") == "udp" &&
            (it.optString("port") == "443" || it.has("protocol")) }
        assertEquals(2, quicRules.size)
        quicRules.forEach { assertEquals(XrayJsonGenerator.QUIC_REFUSAL_TAG, it.getString("outboundTag")) }
        assertEquals("the proxy is still the first outbound", "proxy", json.outboundTags().first())
    }

    @Test
    fun `switch on - Google reaches the worker by IPv4 address, resolved through the tunnel`() {
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker(), localPort = 10808))

        // The copy: the same server and stream as the tunnel, told to hand the worker IPv4 only.
        val copy = json.outbound(XrayJsonGenerator.GOOGLE_V4_TAG)
        assertNotNull(copy)
        assertEquals("UseIPv4", copy!!.getString("targetStrategy"))
        val proxy = json.outbound("proxy")!!
        assertEquals(proxy.getJSONObject("settings").toString(), copy.getJSONObject("settings").toString())
        assertEquals(proxy.getJSONObject("streamSettings").toString(), copy.getJSONObject("streamSettings").toString())
        assertFalse("the tunnel itself is untouched", proxy.has("targetStrategy"))

        // The rule: Google's names to the copy, after QUIC and the block page.
        val rules = json.rules()
        val google = rules.indexOfTag(XrayJsonGenerator.GOOGLE_V4_TAG)
        assertTrue(google >= 0)
        assertTrue(rules[google].domains().containsAll(listOf("domain:google.com", "domain:googleapis.com", "domain:gstatic.com")))
        assertFalse("YouTube stays on the path that already works",
            rules[google].domains().any { it.contains("youtube") || it.contains("googlevideo") })
        assertTrue(google > rules.indexOfTag(XrayJsonGenerator.QUIC_REFUSAL_TAG))
        assertTrue(google > rules.indexOfTag("blocked"))

        // The resolver: DoH through the tunnel, Google's names only, IPv4 only.
        val resolver = json.dnsServers().filterIsInstance<JSONObject>()
            .single { it.optString("tag") == "google-dns" }
        assertEquals("https://8.8.8.8/dns-query", resolver.getString("address"))
        assertEquals("UseIPv4", resolver.getString("queryStrategy"))
        assertTrue(resolver.getBoolean("skipFallback"))
        val viaTunnel = rules.single { it.optJSONArray("inboundTag")?.getString(0) == "google-dns" }
        assertEquals("proxy", viaTunnel.getString("outboundTag"))
        assertTrue("its queries are routed before the rule that sends DNS direct",
            rules.indexOf(viaTunnel) < rules.indexOfFirst { it.optString("outboundTag") == "direct" })
    }

    @Test
    fun `xhttp workers get the Google half too`() {
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker("xhttp"), localPort = 10808))
        assertNotNull(json.outbound(XrayJsonGenerator.GOOGLE_V4_TAG))
    }

    @Test
    fun `a real server keeps Google as it was - it has its own exit`() {
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker("tcp"), localPort = 10808))
        assertNull(json.outbound(XrayJsonGenerator.GOOGLE_V4_TAG))
        assertTrue(json.dnsServers().filterIsInstance<JSONObject>().none { it.optString("tag") == "google-dns" })
    }

    @Test
    fun `switch off - a share-link config is exactly the old one`() {
        XrayJsonGenerator.googleFix = false
        val json = JSONObject(XrayJsonGenerator.generateConfig(worker(), localPort = 10808))

        assertFalse(json.outboundTags().contains(XrayJsonGenerator.QUIC_REFUSAL_TAG))
        assertFalse(json.outboundTags().contains(XrayJsonGenerator.GOOGLE_V4_TAG))
        assertFalse(json.has("policy"))
        assertTrue(json.dnsServers().filterIsInstance<JSONObject>().none { it.optString("tag") == "google-dns" })
        val quicRules = json.rules().filter { it.optString("network") == "udp" &&
            (it.optString("port") == "443" || it.has("protocol")) }
        assertEquals(2, quicRules.size)
        quicRules.forEach { assertEquals("blocked", it.getString("outboundTag")) }
    }

    @Test
    fun `hybrid configs keep their QUIC - it leaves through WARP`() {
        val json = JSONObject(
            XrayJsonGenerator.generateConfig(worker(), localPort = 10808, warpHybrid = JSONObject())
        )
        assertFalse(json.outboundTags().contains(XrayJsonGenerator.QUIC_REFUSAL_TAG))
        assertTrue(json.rules().none { it.optString("network") == "udp" && it.optString("port") == "443" })
    }

    @Test
    fun `the delay test measures the tunnel alone`() {
        val json = JSONObject(XrayJsonGenerator.generateSpeedtestConfig(worker()))
        assertEquals(listOf("proxy"), json.outboundTags())
    }

    // --- whole JSON configs --------------------------------------------------------------------

    /** The shape of a BPB Xray-subscription config: worker over ws, QUIC to a blackhole, DNS. */
    private fun bpbJson(vararg extraOutbounds: JSONObject): String = JSONObject().apply {
        put("remarks", "BPB")
        put("inbounds", JSONArray().put(JSONObject().put("tag", "mixed-in").put("port", 10808).put("protocol", "mixed")))
        put("outbounds", JSONArray().apply {
            put(JSONObject().put("tag", "proxy").put("protocol", "vless")
                .put("streamSettings", JSONObject().put("network", "ws")))
            extraOutbounds.forEach { put(it) }
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        })
        put("dns", JSONObject().put("servers", JSONArray().put("https://8.8.8.8/dns-query")))
        put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", JSONArray()
            .put(JSONObject().put("domain", JSONArray().put("geosite:category-ir")).put("outboundTag", "direct"))
            .put(JSONObject().put("network", "udp").put("port", "443").put("outboundTag", "block"))
            .put(JSONObject().put("network", "tcp,udp").put("outboundTag", "proxy"))))
        put("policy", JSONObject().put("levels", JSONObject().put("0", JSONObject().put("connIdle", 300))))
    }.toString()

    @Test
    fun `a worker JSON config gets both halves, fitted around its own rules`() {
        val out = XrayJsonGenerator.applyGoogleFix(bpbJson())
        assertNotNull(out)
        val json = JSONObject(out!!)
        val rules = json.rules()

        // Their three rules, in order, with ours around them.
        assertEquals(6, rules.size)
        assertEquals("google-dns", rules[0].getJSONArray("inboundTag").getString(0))
        assertTrue(rules[1].isUdp443())
        assertEquals(XrayJsonGenerator.QUIC_REFUSAL_TAG, rules[1].getString("outboundTag"))
        assertEquals("their own direct rule still comes first", "direct", rules[2].getString("outboundTag"))
        assertEquals("block", rules[3].getString("outboundTag"))
        assertEquals("Google, just before their first rule into the tunnel",
            XrayJsonGenerator.GOOGLE_V4_TAG, rules[4].getString("outboundTag"))
        assertEquals("proxy", rules[5].getString("outboundTag"))

        assertEquals("UseIPv4", json.outbound(XrayJsonGenerator.GOOGLE_V4_TAG)!!.getString("targetStrategy"))
        assertNotNull(json.outbound(XrayJsonGenerator.QUIC_REFUSAL_TAG))
        assertTrue(json.dnsServers().filterIsInstance<JSONObject>().any { it.optString("tag") == "google-dns" })
        val levels = json.getJSONObject("policy").getJSONObject("levels")
        assertEquals("their level 0 untouched", 300, levels.getJSONObject("0").getInt("connIdle"))

        assertNull("never twice", XrayJsonGenerator.applyGoogleFix(out))
    }

    @Test
    fun `a JSON config without DNS gets no resolver of ours`() {
        val noDns = JSONObject(bpbJson()).apply { remove("dns") }.toString()
        val json = JSONObject(XrayJsonGenerator.applyGoogleFix(noDns)!!)
        assertFalse(json.has("dns"))
        assertTrue(json.rules().none { it.has("inboundTag") })
        assertNotNull(json.outbound(XrayJsonGenerator.GOOGLE_V4_TAG))
    }

    @Test
    fun `several worker outbounds - QUIC only, there is no single tunnel to copy`() {
        val second = JSONObject().put("tag", "proxy-2").put("protocol", "vless")
            .put("streamSettings", JSONObject().put("network", "ws"))
        val json = JSONObject(XrayJsonGenerator.applyGoogleFix(bpbJson(second))!!)
        assertNotNull(json.outbound(XrayJsonGenerator.QUIC_REFUSAL_TAG))
        assertNull(json.outbound(XrayJsonGenerator.GOOGLE_V4_TAG))
        assertEquals(4, json.rules().size)
    }

    @Test
    fun `JSON configs that can carry UDP are left alone`() {
        // BPB's chain proxy: the worker in front of a real server over TCP, which carries UDP.
        val chain = JSONObject().put("tag", "chain").put("protocol", "vless")
            .put("streamSettings", JSONObject().put("network", "tcp"))
        assertNull(XrayJsonGenerator.applyGoogleFix(bpbJson(chain)))
        // WARP.
        assertNull(XrayJsonGenerator.applyGoogleFix(bpbJson(JSONObject().put("tag", "warp").put("protocol", "wireguard"))))
        // No tunnel at all: a fragment-only config sends its traffic wherever its author chose.
        val direct = JSONObject().put("inbounds", JSONArray()).put("outbounds", JSONArray()
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))).toString()
        assertNull(XrayJsonGenerator.applyGoogleFix(direct))
        // Not JSON.
        assertNull(XrayJsonGenerator.applyGoogleFix("vless://nope"))
    }

    @Test
    fun `switch off - JSON configs are untouched`() {
        XrayJsonGenerator.googleFix = false
        assertNull(XrayJsonGenerator.applyGoogleFix(bpbJson()))
    }

    // --- anti-sanction -------------------------------------------------------------------------

    @Test
    fun `anti-sanction - Google's sanctioned services go by IPv4, the rest of the split is unchanged`() {
        val json = JSONObject(
            XrayJsonGenerator.generateAntiSanctionConfig(
                worker(), localPort = 10808,
                sanctionedDomains = listOf("domain:gemini.google.com", "domain:openai.com", "domain:labs.google"),
            )
        )
        val rules = json.rules()

        val google = rules.single { it.optString("outboundTag") == XrayJsonGenerator.GOOGLE_V4_TAG }
        assertEquals(listOf("domain:gemini.google.com", "domain:labs.google"), google.domains())
        assertEquals("UseIPv4", json.outbound(XrayJsonGenerator.GOOGLE_V4_TAG)!!.getString("targetStrategy"))
        // Ahead of the rules that would send them to the worker by name.
        val poolToWorker = rules.indexOfFirst { it.optString("outboundTag") == "proxy" && it.optJSONArray("ip") != null }
        assertTrue(rules.indexOf(google) < poolToWorker)
        // QUIC headed for the worker is refused; nothing refused on the way out direct.
        val refusals = rules.filter { it.optString("outboundTag") == XrayJsonGenerator.QUIC_REFUSAL_TAG }
        assertEquals(2, refusals.size)
        assertTrue(refusals.all { it.isUdp443() && (it.has("ip") || it.has("domain")) })
        assertTrue(rules.indexOfLast { it.optString("outboundTag") == XrayJsonGenerator.QUIC_REFUSAL_TAG } < poolToWorker)
        // And the JSON path will not add a second copy of anything.
        assertNull(XrayJsonGenerator.applyGoogleFix(json.toString()))
    }

    @Test
    fun `anti-sanction by app - all QUIC refused, all of Google by IPv4`() {
        val json = JSONObject(
            XrayJsonGenerator.generateAntiSanctionConfig(
                worker(), localPort = 10808, sanctionedDomains = emptyList(), proxyEverything = true,
            )
        )
        val refusals = json.rules().filter { it.optString("outboundTag") == XrayJsonGenerator.QUIC_REFUSAL_TAG }
        assertEquals(1, refusals.size)
        assertTrue(refusals.single().isUdp443())
        assertFalse(refusals.single().has("ip"))
        val google = json.rules().single { it.optString("outboundTag") == XrayJsonGenerator.GOOGLE_V4_TAG }
        assertTrue(google.domains().contains("domain:google.com"))
    }

    @Test
    fun `anti-sanction with the switch off is the old split`() {
        XrayJsonGenerator.googleFix = false
        val json = JSONObject(
            XrayJsonGenerator.generateAntiSanctionConfig(
                worker(), localPort = 10808, sanctionedDomains = listOf("domain:gemini.google.com"),
            )
        )
        assertEquals(listOf("proxy", "direct", "dns-out"), json.outboundTags())
    }
}
