package com.mlmvpn.scanner.engines.flux

import com.mlmvpn.scanner.engines.flux.FluxFixtures.node
import com.mlmvpn.scanner.engines.flux.FluxFixtures.reality
import com.mlmvpn.scanner.engines.flux.core.country.CountryHint
import com.mlmvpn.scanner.engines.flux.core.country.EgressVerifier
import com.mlmvpn.scanner.engines.flux.core.memory.FluxState
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.net.Cloudflare
import com.mlmvpn.scanner.engines.flux.core.net.Poison
import com.mlmvpn.scanner.engines.flux.core.plan.FluxPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from the second device test (IPv6 mode, the country list). */
class FluxDeviceTest2 {

    @Test fun `ipv6 edges come from the measured Cloudflare prefixes`() {
        val edges = Cloudflare.sampleEdges(Family.V6, 20, kotlin.random.Random(7))
        assertTrue(edges.size >= 16)
        edges.forEach { e ->
            assertTrue(e, Cloudflare.isCloudflare(e))
            assertEquals(Family.V6, Family.ofLiteral(e))
        }
        // Every edge sits in one of the /48s (first 48 bits match).
        edges.forEach { e ->
            val a = java.net.InetAddress.getByName(e).address.copyOf(6)
            assertTrue(e, Cloudflare.V6_EDGE_PREFIXES.any { java.net.InetAddress.getByName("$it::").address.copyOf(6).contentEquals(a) })
        }
    }

    @Test fun `the block page and private answers are poisoned, real addresses are not`() {
        assertTrue(Poison.isPoisoned("2001:4188:2:600:10:10:34:36"))
        assertTrue(Poison.isPoisoned("10.10.34.35"))
        assertTrue(Poison.isPoisoned("192.168.1.1"))
        assertTrue(Poison.isPoisoned("fd00::1"))
        assertFalse(Poison.isPoisoned("2001:b030:1424:ff00::1"))
        assertFalse(Poison.isPoisoned("91.99.225.11"))
        assertFalse(Poison.isPoisoned("2001:4188:2:601::1"))
    }

    @Test fun `country hint from flags and codes, not from noise`() {
        assertEquals("CA", CountryHint.of("CA 🇨🇦 | @Raydikalx | F71"))
        assertEquals("CA", CountryHint.of("🇨🇦[openproxylist.com] vless-CA"))
        assertEquals("DE", CountryHint.of("vless-DE-1"))
        assertEquals("US", CountryHint.of("#US"))
        assertNull(CountryHint.of("⚡ b2n.ir/v2ray-configs | 354"))
        assertNull(CountryHint.of("WS IP 443"))
    }

    @Test fun `a chosen country races name-hinted nodes first, still never a proven wrong exit`() {
        val us1 = node(reality(ip = "1.0.0.1").replace("#US", "#US-1"))
        val de = node(reality(ip = "1.0.0.2").replace("#US", "#DE"))
        val us2 = node(reality(ip = "1.0.0.3").replace("#US", "#🇺🇸"))
        val liar = node(reality(ip = "1.0.0.4").replace("#US", "#US-liar"))
        val s = FluxState(egress = mapOf(FluxCandidate.egressKey(liar.id, null) to FluxEgressIdentity(countryCode = "NL", verifiedAt = 1_000, confidence = 1.0)))
        val inp = FluxPlanner.Input(listOf(de, us1, liar, us2), s, "n", 2_000, "US", IpMode.V4, NetVerdict())
        val ids = FluxPlanner.candidates(inp).map { it.node.id }
        assertEquals(setOf(us1.id, us2.id), ids.take(2).toSet())
        assertFalse(liar.id in ids)
        assertTrue(de.id in ids) // unproven and unclaimed still gets a chance, after the hinted ones
    }

    @Test fun `a fourth source breaks a tie`() {
        val two = listOf(EgressVerifier.Observation("ip-api", "1.2.3.4", "DE"), EgressVerifier.Observation("ipwho", "1.2.3.4", "US"))
        assertNull(EgressVerifier.combine(two, 1).countryCode)
        val tie = EgressVerifier.parse("ifconfig", """{"ip":"1.2.3.4","country_iso":"DE","asn":"AS24940","asn_org":"Hetzner"}""")!!
        val id = EgressVerifier.combine(two + tie, 1)
        assertEquals("DE", id.countryCode)
        assertEquals(1.0, id.confidence, 0.0)
    }
}
