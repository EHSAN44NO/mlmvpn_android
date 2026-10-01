package com.mlmvpn.scanner.engines.flux

import com.mlmvpn.scanner.engines.flux.FluxFixtures.cand
import com.mlmvpn.scanner.engines.flux.FluxFixtures.cfWs
import com.mlmvpn.scanner.engines.flux.FluxFixtures.hy2
import com.mlmvpn.scanner.engines.flux.FluxFixtures.reality
import com.mlmvpn.scanner.engines.flux.FluxFixtures.trojan
import com.mlmvpn.scanner.engines.flux.core.compile.FluxConfigCompiler
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxConfigTest {

    @Test fun `websocket never offers h2 and keeps origin names on an edge`() {
        val c = cand(cfWs().substringBefore('#') + "&alpn=h2,http/1.1", Family.V4, edge = "104.16.5.5")
        val o = FluxOutbounds.outbound(c, "t")
        assertEquals("104.16.5.5", o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        val ss = o.getJSONObject("streamSettings")
        assertEquals("edge-a.pages.dev", ss.getJSONObject("wsSettings").getString("host"))
        assertEquals("edge-a.pages.dev", ss.getJSONObject("tlsSettings").getString("serverName"))
        assertFalse(ss.getJSONObject("tlsSettings").getJSONArray("alpn").toString().contains("h2"))
        assertEquals("ForceIPv4", ss.getJSONObject("sockopt").getString("domainStrategy"))
    }

    @Test fun `reality uses its own settings object`() {
        val ss = FluxOutbounds.outbound(cand(reality()), "t").getJSONObject("streamSettings")
        assertEquals("reality", ss.getString("security"))
        assertTrue(ss.has("realitySettings"))
        assertFalse(ss.has("tlsSettings"))
        assertEquals("ios", ss.getJSONObject("realitySettings").getString("fingerprint"))
    }

    @Test fun `hysteria2 outbound with salamander`() {
        val o = FluxOutbounds.outbound(cand(hy2()), "t")
        assertEquals("hysteria", o.getString("protocol"))
        val ss = o.getJSONObject("streamSettings")
        assertEquals("hysteria", ss.getString("network"))
        assertEquals(2, ss.getJSONObject("hysteriaSettings").getInt("version"))
        assertEquals("salamander", ss.getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0).getString("type"))
    }

    @Test fun `fragment only when asked, mux never with vision`() {
        val plain = cand(trojan())
        assertFalse(FluxOutbounds.outbound(plain, "t").getJSONObject("streamSettings").has("finalmask"))
        val frag = plain.copy(fragment = FragmentProfile.BALANCED)
        assertTrue(FluxOutbounds.outbound(frag, "t").getJSONObject("streamSettings").getJSONObject("finalmask").has("tcp"))
        val vision = cand(reality()).copy(mux = true)
        assertFalse(FluxOutbounds.outbound(vision, "t").has("mux"))
        assertTrue(FluxOutbounds.outbound(plain.copy(mux = true), "t").has("mux"))
    }

    @Test fun `ipv4 tunnel has no ipv6 anywhere`() {
        val cfg = JSONObject(FluxConfigCompiler.tunnel(listOf(cand(reality())), IpMode.V4, 10808))
        assertEquals(FluxConfigCompiler.REMARKS_V4, cfg.getString("remarks"))
        assertEquals("UseIPv4", cfg.getJSONObject("dns").getString("queryStrategy"))
        assertEquals(1, cfg.getJSONArray("fakedns").length())
        assertFalse(cfg.getJSONArray("fakedns").toString().contains("fc00"))
    }

    @Test fun `both-family tunnel offers ipv6`() {
        val cfg = JSONObject(FluxConfigCompiler.tunnel(listOf(cand(reality())), IpMode.BOTH, 10808))
        assertEquals(FluxConfigCompiler.REMARKS, cfg.getString("remarks"))
        assertTrue(cfg.getJSONArray("fakedns").toString().contains("fc00::/18"))
    }

    @Test fun `no dns leaves the tunnel in the clear`() {
        val cfg = JSONObject(FluxConfigCompiler.tunnel(listOf(cand(cfWs())), IpMode.V4, 10808))
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        val portRules = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.optString("port") == "53" }
        assertTrue(portRules.isNotEmpty())
        assertTrue(portRules.all { it.getString("outboundTag") == "dns-out" })
        // The resolver behind FakeDNS is reached through the balancer, never direct.
        val dnsRule = (0 until rules.length()).map { rules.getJSONObject(it) }.single { it.optJSONArray("inboundTag")?.toString()?.contains(FluxConfigCompiler.DNS_TAG) == true }
        assertEquals(FluxConfigCompiler.BALANCER, dnsRule.getString("balancerTag"))
        val servers = cfg.getJSONObject("dns").getJSONArray("servers").toString()
        assertTrue(servers.contains("https://"))
        assertFalse(servers.contains("\"address\":\"1.1.1.1\""))
        // The fake pool is not LAN: it must not be routed direct.
        val direct = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.optString("outboundTag") == "direct" }.toString()
        assertFalse(direct.contains("198.18"))
        assertFalse(direct.contains("fc00"))
    }

    @Test fun `primary and standbys behind one balancer with an observatory`() {
        val routes = listOf(cand(reality(ip = "1.0.0.1")), cand(reality(ip = "1.0.0.2")), cand(reality(ip = "1.0.0.3")), cand(reality(ip = "1.0.0.4")))
        val cfg = JSONObject(FluxConfigCompiler.tunnel(routes, IpMode.BOTH, 10808))
        val bal = cfg.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0)
        assertEquals(3, bal.getJSONArray("selector").length())
        assertEquals("flux-0", bal.getString("fallbackTag"))
        assertEquals(3, cfg.getJSONObject("burstObservatory").getJSONArray("subjectSelector").length())
        val tags = (0 until cfg.getJSONArray("outbounds").length()).map { cfg.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertTrue(tags.containsAll(listOf("flux-0", "flux-1", "flux-2")))
        assertFalse(tags.contains("flux-3"))
    }

    @Test fun `quic refused only when the primary cannot carry udp`() {
        val sink = JSONObject().put("tag", "quic-refuse").put("protocol", "freedom")
        val ws = JSONObject(FluxConfigCompiler.tunnel(listOf(cand(cfWs())), IpMode.V4, 10808, quicSink = sink))
        assertTrue(ws.getJSONObject("routing").getJSONArray("rules").toString().contains("quic-refuse"))
        val rl = JSONObject(FluxConfigCompiler.tunnel(listOf(cand(reality())), IpMode.V4, 10808, quicSink = sink))
        assertFalse(rl.getJSONObject("routing").getJSONArray("rules").toString().contains("quic"))
    }

    @Test fun `probe core maps each candidate to its own port explicitly`() {
        val a = cand(reality(ip = "1.0.0.1")); val b = cand(hy2(ip = "1.0.0.2")); val c = cand(trojan(ip = "1.0.0.3"))
        val ports = linkedMapOf(a to 20001, b to 20002, c to 20003)
        val cfg = JSONObject(FluxConfigCompiler.probe(ports))
        val inbounds = cfg.getJSONArray("inbounds"); val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        val outs = cfg.getJSONArray("outbounds")
        ports.forEach { (cand, port) ->
            val inb = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }.single { it.getInt("port") == port }
            val rule = (0 until rules.length()).map { rules.getJSONObject(it) }.single { it.getJSONArray("inboundTag").getString(0) == inb.getString("tag") }
            val out = (0 until outs.length()).map { outs.getJSONObject(it) }.single { it.getString("tag") == rule.getString("outboundTag") }
            val addr = out.getJSONObject("settings").let { s ->
                s.optJSONArray("vnext")?.getJSONObject(0)?.getString("address") ?: s.optJSONArray("servers")?.getJSONObject(0)?.getString("address") ?: s.getString("address")
            }
            assertEquals(cand.dialAddress, addr)
        }
    }

    @Test fun `credentials never in a candidate's printable form`() {
        val c = cand(trojan(pass = "super-secret-pass"))
        assertFalse(c.toString().contains("super-secret"))
        assertFalse(c.id.contains("super-secret"))
        assertNull(Regex("super-secret").find(c.node.redacted()))
    }
}
