package com.mlmvpn.scanner.engines.flux

import com.mlmvpn.scanner.engines.flux.FluxFixtures.cand
import com.mlmvpn.scanner.engines.flux.FluxFixtures.cfWs
import com.mlmvpn.scanner.engines.flux.FluxFixtures.hy2
import com.mlmvpn.scanner.engines.flux.FluxFixtures.reality
import com.mlmvpn.scanner.engines.flux.core.country.EgressVerifier
import com.mlmvpn.scanner.engines.flux.core.memory.FluxMemory
import com.mlmvpn.scanner.engines.flux.core.memory.FluxState
import com.mlmvpn.scanner.engines.flux.core.memory.FluxStateCodec
import com.mlmvpn.scanner.engines.flux.core.memory.NetProfile
import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.model.Tri
import com.mlmvpn.scanner.engines.flux.core.net.Cloudflare
import com.mlmvpn.scanner.engines.flux.core.plan.FluxLearner
import com.mlmvpn.scanner.engines.flux.core.race.FluxProbe
import com.mlmvpn.scanner.engines.flux.core.race.FluxRacer
import com.mlmvpn.scanner.engines.flux.core.score.FluxScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxLearningTest {

    private val now = 2_000_000_000L

    // ---------------------------------------------------------------- country

    @Test fun `egress sources parsed`() {
        assertEquals("DE", EgressVerifier.parse("cf-trace", "fl=1\nip=5.6.7.8\nloc=DE\nwarp=off")!!.countryCode)
        val api = EgressVerifier.parse("ip-api", """{"status":"success","countryCode":"de","query":"5.6.7.8","as":"AS24940 Hetzner","org":"Hetzner"}""")!!
        assertEquals("DE", api.countryCode); assertEquals("AS24940", api.asn)
        assertNull(EgressVerifier.parse("ip-api", """{"status":"fail"}"""))
        assertEquals("NL", EgressVerifier.parse("ipwho", """{"success":true,"ip":"1.2.3.4","country_code":"NL","connection":{"asn":1136,"org":"KPN"}}""")!!.countryCode)
    }

    @Test fun `label says US but the exit is in Germany - rejected for US, accepted for DE`() {
        val node = FluxFixtures.node(reality()) // label "#US"
        assertEquals("US", node.label)
        val id = EgressVerifier.combine(listOf(
            EgressVerifier.Observation("cf-trace", "5.6.7.8", "DE"),
            EgressVerifier.Observation("ip-api", "5.6.7.8", "DE"),
        ), now)
        assertEquals(1.0, id.confidence, 0.0)
        assertFalse(EgressVerifier.accepts(id, "US", now))
        assertTrue(EgressVerifier.accepts(id, "DE", now))
        assertTrue(EgressVerifier.accepts(id, null, now))
    }

    @Test fun `disagreeing sources give no country`() {
        val id = EgressVerifier.combine(listOf(
            EgressVerifier.Observation("cf-trace", "5.6.7.8", "DE"),
            EgressVerifier.Observation("ip-api", "5.6.7.8", "NL"),
        ), now)
        assertNull(id.countryCode)
        assertFalse(EgressVerifier.accepts(id, "DE", now))
    }

    @Test fun `country proof expires`() {
        val id = FluxEgressIdentity(countryCode = "DE", verifiedAt = now, confidence = 1.0)
        assertTrue(EgressVerifier.accepts(id, "DE", now + 3600_000L))
        assertFalse(EgressVerifier.accepts(id, "DE", now + FluxEgressIdentity.TTL_MS + 1))
    }

    // ---------------------------------------------------------------- memory

    @Test fun `network A remembers node X, network B node Y`() {
        val x = cand(reality(ip = "1.0.0.1")); val y = cand(reality(ip = "1.0.0.2"))
        var s = FluxState()
        s = FluxMemory.setBest(s, "A", null, IpMode.BOTH, listOf(x.id))
        s = FluxMemory.setBest(s, "B", null, IpMode.BOTH, listOf(y.id))
        assertEquals(listOf(x.id), FluxMemory.best(s, "A", null, IpMode.BOTH))
        assertEquals(listOf(y.id), FluxMemory.best(s, "B", null, IpMode.BOTH))
        // A country choice is its own policy: switching countries forgets nothing.
        s = FluxMemory.setBest(s, "A", "DE", IpMode.BOTH, listOf(y.id))
        assertEquals(listOf(x.id), FluxMemory.best(s, "A", null, IpMode.BOTH))
    }

    @Test fun `breaker opens after three failures, quarantine after many, success resets`() {
        val id = "n@o/40"
        var s = FluxState()
        repeat(2) { s = FluxMemory.recordFailure(s, "A", id, now, FailReason.TCP_TIMEOUT) }
        assertFalse(FluxMemory.metrics(s, "A", id)!!.coolingDown(now))
        s = FluxMemory.recordFailure(s, "A", id, now, FailReason.TCP_TIMEOUT)
        assertTrue(FluxMemory.metrics(s, "A", id)!!.coolingDown(now + 30_000))
        assertFalse(FluxMemory.metrics(s, "A", id)!!.coolingDown(now + 61_000))
        repeat(6) { s = FluxMemory.recordFailure(s, "A", id, now, FailReason.TCP_TIMEOUT) }
        assertTrue(FluxMemory.metrics(s, "A", id)!!.coolingDown(now + 20 * 3600_000L))
        s = FluxMemory.recordSuccess(s, "A", id, now, 50)
        assertFalse(FluxMemory.metrics(s, "A", id)!!.coolingDown(now))
    }

    @Test fun `state survives the codec`() {
        var s = FluxState()
        s = FluxMemory.recordSuccess(s, "A", "n@o/40", now, 42, 80, "1.2.3.4")
        s = FluxMemory.recordFailure(s, "A", "m@o/40", now, FailReason.RESET_AFTER_SNI)
        s = FluxMemory.setVerdict(s, "A", NetVerdict(cfV4 = Tri.NO, cfV6 = Tri.YES, v6 = Tri.YES, udp = Tri.NO, at = now))
        s = FluxMemory.rememberEdges(s, "A", Family.V6, listOf("2606:4700::1"))
        s = FluxMemory.setFragment(s, "A", FragmentProfile.BALANCED)
        s = FluxMemory.recordEgress(s, "n@o", FluxEgressIdentity(ipv4 = "5.6.7.8", countryCode = "DE", verifiedAt = now, confidence = 1.0))
        s = s.copy(prefs = s.prefs.copy(country = "DE", ipMode = IpMode.V6))
        s = FluxMemory.setBest(s, "A", "DE", IpMode.V6, listOf("n@o/40"))
        val back = FluxStateCodec.decode(FluxStateCodec.encode(s))
        assertEquals(s, back)
    }

    @Test fun `old networks are pruned`() {
        var s = FluxMemory.markSeen(FluxState(), "old", now - 40L * 86_400_000L)
        s = FluxMemory.recordSuccess(s, "old", "n@o/40", now, 10)
        s = FluxMemory.markSeen(s, "new", now)
        val p = FluxMemory.prune(s, now)
        assertFalse(p.nets.containsKey("old"))
        assertTrue(p.metrics.isEmpty())
        assertTrue(p.nets.containsKey("new"))
    }

    // ---------------------------------------------------------------- learning

    private fun outcome(
        ranked: List<com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate> = emptyList(),
        reach: Map<String, FluxProbe.Reach> = emptyMap(),
        real: Map<String, FluxProbe.Real> = emptyMap(),
    ) = FluxRacer.Outcome(ranked.map { it to FluxProbe.Real(true, 50) }, reach, real, emptyList())

    @Test fun `cloudflare learned cut when several edges all fail at tcp`() {
        val cs = (1..3).map { cand(cfWs(), Family.V4, edge = "104.16.0.$it") }
        val out = outcome(reach = cs.associate { it.id to FluxProbe.Reach(false, reason = FailReason.TCP_TIMEOUT) })
        val s = FluxLearner.afterRace(FluxState(), "A", now, null, IpMode.V4, cs, out)
        assertEquals(Tri.NO, s.nets["A"]!!.verdict.cfV4)
        assertTrue(cs.all { Cloudflare.isCloudflare(it.dialAddress) })
    }

    @Test fun `one dead edge does not condemn cloudflare`() {
        val dead = cand(cfWs(), Family.V4, edge = "104.16.0.1")
        val alive = cand(cfWs(), Family.V4, edge = "104.16.0.2")
        val out = outcome(reach = mapOf(dead.id to FluxProbe.Reach(false, reason = FailReason.TCP_TIMEOUT), alive.id to FluxProbe.Reach(true, 30)))
        val s = FluxLearner.afterRace(FluxState(), "A", now, null, IpMode.V4, listOf(dead, alive), out)
        assertEquals(Tri.YES, s.nets["A"]!!.verdict.cfV4)
        assertEquals(listOf("104.16.0.2"), s.nets["A"]!!.edges[Family.V4])
    }

    @Test fun `udp learned blocked when every hysteria2 node is silent`() {
        val hs = (1..3).map { cand(hy2(ip = "9.9.9.$it")) }
        val out = outcome(real = hs.associate { it.id to FluxProbe.Real(false, reason = FailReason.HTTP_TIMEOUT) })
        assertEquals(Tri.NO, FluxLearner.afterRace(FluxState(), "A", now, null, IpMode.V4, hs, out).nets["A"]!!.verdict.udp)
    }

    @Test fun `fragment helps - kept for the network`() {
        val plain = cand(reality(ip = "1.0.0.1"))
        val frag = plain.copy(fragment = FragmentProfile.BALANCED)
        val out = outcome(ranked = listOf(frag), reach = mapOf(plain.id to FluxProbe.Reach(false, reason = FailReason.RESET_AFTER_SNI)))
        val s = FluxLearner.afterRace(FluxState(), "A", now, null, IpMode.V4, listOf(plain, frag), out)
        assertEquals(FragmentProfile.BALANCED, s.nets["A"]!!.fragment)
    }

    @Test fun `fragment hurts or is unneeded - dropped as soon as plain works`() {
        val plain = cand(reality(ip = "1.0.0.1"))
        val frag = plain.copy(fragment = FragmentProfile.BALANCED)
        val start = FluxState(nets = mapOf("A" to NetProfile(fragment = FragmentProfile.BALANCED)))
        val s = FluxLearner.afterRace(start, "A", now, null, IpMode.V4, listOf(plain, frag), outcome(ranked = listOf(plain, frag)))
        assertNull(s.nets["A"]!!.fragment)
    }

    @Test fun `standbys are on other servers than the primary`() {
        val a1 = cand(reality(ip = "1.0.0.1")); val a2 = a1.copy(fragment = FragmentProfile.BALANCED)
        val b = cand(reality(ip = "1.0.0.2")); val c = cand(reality(ip = "1.0.0.3"))
        assertEquals(listOf(a1.id, b.id, c.id), FluxLearner.pickRoutes(listOf(a1, a2, b, c)))
    }

    @Test fun `race result becomes the warm route for that policy`() {
        val a = cand(reality(ip = "1.0.0.1")); val b = cand(reality(ip = "1.0.0.2"))
        val out = FluxRacer.Outcome(
            listOf(a to FluxProbe.Real(true, 40, egress = FluxEgressIdentity(countryCode = "DE", verifiedAt = now, confidence = 0.6)), b to FluxProbe.Real(true, 70)),
            mapOf(a.id to FluxProbe.Reach(true, 10), b.id to FluxProbe.Reach(true, 12)),
            mapOf(a.id to FluxProbe.Real(true, 40, egress = FluxEgressIdentity(countryCode = "DE", verifiedAt = now, confidence = 0.6)), b.id to FluxProbe.Real(true, 70)),
            emptyList(),
        )
        val s = FluxLearner.afterRace(FluxState(), "A", now, "DE", IpMode.BOTH, listOf(a, b), out)
        assertEquals(listOf(a.id, b.id), FluxMemory.best(s, "A", "DE", IpMode.BOTH))
        assertEquals("DE", s.egress[a.egressKey]!!.countryCode)
        assertEquals("1.0.0.1", FluxMemory.metrics(s, "A", a.id)!!.dial)
    }

    // ---------------------------------------------------------------- scoring / ip family

    @Test fun `both families pass and v6 is faster - v6 scores higher`() {
        val v4 = cand(reality(ip = "1.0.0.1"), Family.V4)
        val v6 = cand(reality(ip = "2a01::1"), Family.V6)
        assertTrue(FluxScorer.score(v6, null, 38, now) > FluxScorer.score(v4, null, 72, now))
    }

    @Test fun `hysteresis - a 2ms gain is not a switch, 30 percent is`() {
        assertFalse(FluxScorer.shouldSwitch(0.70, 0.71))
        assertTrue(FluxScorer.shouldSwitch(0.60, 0.80))
    }

    @Test fun `flapping route scores below a steady slower one`() {
        val c = cand(reality())
        var s = FluxState()
        repeat(4) { s = FluxMemory.recordSuccess(s, "A", "steady", now, 70) }
        var f = FluxState()
        repeat(3) { f = FluxMemory.recordSuccess(f, "A", "flap", now, 40); f = FluxMemory.recordFailure(f, "A", "flap", now, FailReason.HTTP_TIMEOUT) }
        f = FluxMemory.recordFailure(f, "A", "flap", now, FailReason.HTTP_TIMEOUT)
        assertTrue(FluxScorer.score(c, FluxMemory.metrics(s, "A", "steady"), null, now) > FluxScorer.score(c, FluxMemory.metrics(f, "A", "flap"), null, now))
    }

    @Test fun `cloudflare ranges`() {
        assertTrue(Cloudflare.isCloudflare("104.16.1.1"))
        assertTrue(Cloudflare.isCloudflare("188.114.97.6"))
        assertTrue(Cloudflare.isCloudflare("2606:4700::6810:1"))
        assertFalse(Cloudflare.isCloudflare("8.8.8.8"))
        assertFalse(Cloudflare.isCloudflare("example.com"))
        val v4 = Cloudflare.sampleEdges(Family.V4, 20, kotlin.random.Random(1))
        assertEquals(20, v4.size)
        assertTrue(v4.all { Cloudflare.isCloudflare(it) && !it.endsWith(".0") && !it.endsWith(".255") })
        val v6 = Cloudflare.sampleEdges(Family.V6, 5, kotlin.random.Random(2))
        assertTrue(v6.all { Cloudflare.isCloudflare(it) && Family.ofLiteral(it) == Family.V6 })
    }
}
