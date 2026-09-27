package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.doctor.SanctionProbe.Outcome
import com.mlmvpn.scanner.engines.game.booster.paths.SanctionDns
import com.mlmvpn.scanner.engines.game.booster.paths.SanctionDns.HostTrial
import com.mlmvpn.scanner.engines.game.booster.paths.SanctionDns.ProviderTrial
import com.mlmvpn.scanner.utils.XrayJsonGenerator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SanctionDnsTest {

    private fun p(id: String) = SanctionDns.byId(id)!!

    private fun trial(id: String, vararg hosts: Pair<String, Outcome?>, connectMs: Long = 30, answer: String = "185.51.200.9") =
        ProviderTrial(p(id), p(id).ips.first(), hosts.map { (h, o) -> HostTrial(h, answer, o, 5, connectMs) })

    @Test
    fun `the provider that opens the most refused hosts wins`() {
        val best = SanctionDns.rank(listOf(
            trial("shecan", "accounts.ea.com" to Outcome.OPEN, "gateway.ea.com" to Outcome.GEO_BLOCKED),
            trial("403", "accounts.ea.com" to Outcome.OPEN, "gateway.ea.com" to Outcome.OPEN, connectMs = 90),
            trial("radar", "accounts.ea.com" to null, "gateway.ea.com" to null),
        ), currentId = null)
        assertEquals("403", best!!.provider.id)
    }

    @Test
    fun `among equals the one in use stays, otherwise the nearest proxy`() {
        val a = trial("shecan", "accounts.ea.com" to Outcome.OPEN, connectMs = 80)
        val b = trial("electro", "accounts.ea.com" to Outcome.OPEN, connectMs = 20)
        assertEquals("electro", SanctionDns.rank(listOf(a, b), currentId = null)!!.provider.id)
        assertEquals("shecan", SanctionDns.rank(listOf(a, b), currentId = "shecan")!!.provider.id)
    }

    @Test
    fun `nothing is picked when no provider opens anything`() {
        assertNull(SanctionDns.rank(listOf(
            trial("shecan", "accounts.ea.com" to Outcome.GEO_BLOCKED),
            ProviderTrial(p("radar"), null, listOf(HostTrial("accounts.ea.com", null, null))),
        ), currentId = null))
    }

    @Test
    fun `a 403 through a provider's proxy means the refusal is not about the country`() {
        val sys = mapOf("x.example.com" to listOf("1.2.3.4"), "y.example.com" to listOf("5.6.7.8"))
        val notGeo = SanctionDns.notGeo(listOf(
            // Proxied (a different address) and still 403: not a sanction.
            ProviderTrial(p("shecan"), "178.22.122.100", listOf(HostTrial("x.example.com", "185.51.200.9", Outcome.GEO_BLOCKED))),
            // Passed through (the same address): says nothing about the country.
            ProviderTrial(p("403"), "10.202.10.202", listOf(HostTrial("y.example.com", "5.6.7.8", Outcome.GEO_BLOCKED))),
        ), sys)
        assertEquals(setOf("x.example.com"), notGeo)
        // One provider fixing it proves it was a sanction after all.
        val fixedElsewhere = SanctionDns.notGeo(listOf(
            ProviderTrial(p("shecan"), "178.22.122.100", listOf(HostTrial("x.example.com", "185.51.200.9", Outcome.GEO_BLOCKED))),
            ProviderTrial(p("electro"), "78.157.42.100", listOf(HostTrial("x.example.com", "78.157.42.5", Outcome.OPEN))),
        ), sys)
        assertTrue(fixedElsewhere.isEmpty())
    }

    @Test
    fun `the providers are the right services at the right addresses`() {
        assertEquals(listOf("10.202.10.202", "10.202.10.102"), p("403").ips)
        assertEquals(listOf("185.55.226.26", "185.55.225.25"), p("begzar").ips)
        assertTrue("178.22.122.100" in p("shecan").ips)
    }

    @Test
    fun `the DNS-only session asks the provider only for its names and the line for the rest`() {
        val cfg = JSONObject(XrayJsonGenerator.generateGameDnsOnlyConfig(
            mtu = 1400,
            staticHosts = mapOf("poisoned.example.com" to listOf("9.9.9.9")),
            providerServers = listOf("178.22.122.100" to listOf("accounts.ea.com", ".ea.com")),
            ispResolvers = listOf("10.20.30.40"),
        ))
        val dns = cfg.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        val provider = servers.getJSONObject(0)
        assertEquals("178.22.122.100", provider.getString("address"))
        assertTrue(provider.getBoolean("skipFallback"))
        val domains = provider.getJSONArray("domains")
        assertEquals("full:accounts.ea.com", domains.getString(0))
        assertEquals("domain:ea.com", domains.getString(1))
        // The line's own resolver is the default for everything else, before any DoH.
        val isp = servers.getJSONObject(1)
        assertEquals("10.20.30.40", isp.getString("address"))
        assertFalse(isp.has("domains"))
        assertEquals("https://8.8.8.8/dns-query", servers.getString(2))
        assertEquals("9.9.9.9", dns.getJSONObject("hosts").getString("poisoned.example.com"))
        assertEquals("UseIPv4", dns.getString("queryStrategy"))
    }

    @Test
    fun `without the line's resolvers the old order stays`() {
        val servers = JSONObject(XrayJsonGenerator.generateGameDnsOnlyConfig(mtu = 1400))
            .getJSONObject("dns").getJSONArray("servers")
        assertEquals(2, servers.length())
        assertEquals("https://8.8.8.8/dns-query", servers.getString(0))
    }

    @Test
    fun `hosts filtered on their name get their own routes to the fragmenting outbound`() {
        val cfg = JSONObject(XrayJsonGenerator.generateGameDnsOnlyConfig(
            mtu = 1400, staticHosts = mapOf("login.example.com" to listOf("203.0.113.7")),
            fragmentIps = listOf("203.0.113.7"),
        ))
        val outbounds = cfg.getJSONArray("outbounds")
        val frag = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .first { it.getString("tag") == XrayJsonGenerator.GAME_FRAGMENT_TAG }
        val masks = frag.getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("tcp")
        assertEquals("tlshello", masks.getJSONObject(0).getJSONObject("settings").getString("packets"))
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        val ipRule = (0 until rules.length()).map { rules.getJSONObject(it) }.first { it.has("ip") }
        assertEquals("203.0.113.7", ipRule.getJSONArray("ip").getString(0))
        assertEquals(XrayJsonGenerator.GAME_FRAGMENT_TAG, ipRule.getString("outboundTag"))
        // Without fragment hosts there is no such outbound at all.
        val plain = JSONObject(XrayJsonGenerator.generateGameDnsOnlyConfig(mtu = 1400)).getJSONArray("outbounds")
        assertTrue((0 until plain.length()).none { plain.getJSONObject(it).getString("tag") == XrayJsonGenerator.GAME_FRAGMENT_TAG })
    }

    @Test
    fun `the first packet is cut through the middle of the host name`() {
        val host = "accounts.example.com"
        val hello = ByteArray(40) { 0x16 } + host.toByteArray() + ByteArray(60)
        val cuts = com.mlmvpn.scanner.engines.game.booster.doctor.FragmentProbe.cutPoints(hello, host)
        assertEquals(listOf(5, 40 + host.length / 2), cuts)
        // No name found: still cut after the record header and once more.
        assertEquals(2, com.mlmvpn.scanner.engines.game.booster.doctor.FragmentProbe.cutPoints(ByteArray(100), host).size)
    }
}
