package com.mlmvpn.scanner.engines.mae

import com.mlmvpn.scanner.engines.mae.FakeNet.Behave
import com.mlmvpn.scanner.engines.mae.classify.Classifier
import com.mlmvpn.scanner.engines.mae.model.Axis
import com.mlmvpn.scanner.engines.mae.model.FailMode
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ForeignEgressStatus
import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.model.ServiceHints
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.policy.Decision
import com.mlmvpn.scanner.engines.mae.policy.Discovery
import com.mlmvpn.scanner.engines.mae.policy.DiscoveryResult
import com.mlmvpn.scanner.engines.mae.policy.PolicyEngine
import com.mlmvpn.scanner.engines.mae.probe.ProbeRoute
import com.mlmvpn.scanner.engines.mae.route.Capabilities
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.engines.mae.store.SelectedService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spec's scenarios, end to end through discovery -> classifier -> scorer, on a fake network. */
class MaeScenarioTest {
    private val worker = WorkerRoute("mae.example.workers.dev", "00000000-0000-0000-0000-000000000000")
    private val providers = listOf(DirectRoute, ServerlessRoute, FragmentRoute, worker)
    private val healthy: (String) -> Health = { Health.HEALTHY }
    private val net = "netA"

    private fun discover(net: FakeNet, svc: com.mlmvpn.scanner.engines.mae.model.ServiceDef, routes: List<ProbeRoute> = FakeNet.routes()) =
        runBlocking { Discovery(net, now = { 1_000L }).run(svc, routes) }

    private fun decideAfter(result: DiscoveryResult, svc: com.mlmvpn.scanner.engines.mae.model.ServiceDef, provs: List<RouteProvider> = providers, state: MaeState = MaeState(), netKey: String = net) =
        PolicyEngine.mergeDiscovery(state.copy(selected = state.selected + SelectedService(svc.id)), svc, netKey, result, { provs }, healthy, 1_000L)

    @Test fun `Gemini - one 403 is not a verdict, and the foreign route that the service accepts wins`() {
        // Direct alone: a bare 403 must stay UNKNOWN.
        val onlyDirect = Classifier.classify(listOf(com.mlmvpn.scanner.engines.mae.probe.Observation("direct", false, true,
            tcp = com.mlmvpn.scanner.engines.mae.probe.Step.OK, tls = com.mlmvpn.scanner.engines.mae.probe.Step.OK,
            http = com.mlmvpn.scanner.engines.mae.probe.HttpEvidence(403, false, false))))
        assertEquals(Tri.UNKNOWN, onlyDirect[Axis.GEO_RESTRICTION].state)

        val gemini = FakeNet.service("gemini", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val fake = FakeNet()
            .route("direct", Behave.BARE_403)
            .route("serverless", Behave.GEO_403_SIGNATURE)
            .route("fragment", Behave.GEO_403_SIGNATURE)
            .route("worker", Behave.OK, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "US")
            // The Worker's IPv6 exit is refused by the service (the known Gemini case).
            .route("worker", Behave.GEO_403_SIGNATURE, family = FamilyPolicy.V6_ONLY).echo("worker", FamilyPolicy.V6_ONLY, "US")
        val r = discover(fake, gemini)
        assertEquals(Tri.YES, r.diagnosis[Axis.GEO_RESTRICTION].state)
        assertEquals(Tri.NO, r.diagnosis[Axis.CENSORSHIP].state)
        assertEquals(ForeignEgressStatus.Proven("worker", FamilyPolicy.V4_ONLY), r.egress)
        val (_, d) = decideAfter(r, gemini)
        assertEquals(Decision.Use::class, d::class)
        d as Decision.Use
        assertEquals("worker", d.routeId)
        assertEquals(FamilyPolicy.V4_ONLY, d.family)
    }

    @Test fun `censored and geo-restricted at once - both axes YES, route must satisfy both`() {
        val svc = FakeNet.service("tiktok", ServiceHints(likelyCensored = true, likelyGeoRestricted = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.GEO_403_SIGNATURE)
            .route("fragment", Behave.GEO_403_SIGNATURE)
            .route("worker", Behave.OK, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "DE")
        val r = discover(fake, svc)
        assertEquals(Tri.YES, r.diagnosis[Axis.CENSORSHIP].state)
        assertEquals(Tri.YES, r.diagnosis[Axis.GEO_RESTRICTION].state)
        assertEquals(com.mlmvpn.scanner.engines.mae.model.Diagnosis.Primary.CENSORED_AND_GEO, r.diagnosis.primary)
        val (_, d) = decideAfter(r, svc)
        assertEquals("worker", (d as Decision.Use).routeId)
    }

    @Test fun `YouTube - Serverless beats a slower foreign route, and the foreign exit is not even spent`() {
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true, heavy = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.OK, rttMs = 60, bps = 5_600_000.0)
            .route("fragment", Behave.OK, rttMs = 95, bps = 2_000_000.0)
            .route("worker", Behave.OK, rttMs = 260, bps = 500_000.0, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "US")
        val r = discover(fake, yt)
        assertEquals(Tri.NO, r.diagnosis[Axis.GEO_RESTRICTION].state)
        assertTrue("no Worker quota spent on a service that works from Iran", fake.echoAsked.isEmpty())
        val (_, d) = decideAfter(r, yt)
        assertEquals("serverless", (d as Decision.Use).routeId)
    }

    @Test fun `Telegram - a block is not a geo restriction, and the working bypass is chosen`() {
        val tg = FakeNet.service("telegram", ServiceHints(likelyCensored = true))
        val fake = FakeNet()
            .route("direct", Behave.TCP_TIMEOUT)
            .route("serverless", Behave.TLS_RESET)
            .route("fragment", Behave.OK, rttMs = 120)
        val r = discover(fake, tg)
        assertEquals(Tri.YES, r.diagnosis[Axis.CENSORSHIP].state)
        assertEquals(Tri.NO, r.diagnosis[Axis.GEO_RESTRICTION].state)
        val (_, d) = decideAfter(r, tg)
        assertEquals("fragment", (d as Decision.Use).routeId)
    }

    @Test fun `Instagram - the significantly faster working route is learned`() {
        val ig = FakeNet.service("instagram", ServiceHints(likelyCensored = true, heavy = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.OK, rttMs = 420, bps = 300_000.0)
            .route("fragment", Behave.OK, rttMs = 80, bps = 3_000_000.0)
        val (_, d) = decideAfter(discover(fake, ig), ig)
        assertEquals("fragment", (d as Decision.Use).routeId)
    }

    @Test fun `staged budget - only two finalists get a throughput sample`() {
        val svc = FakeNet.service("github")
        val fake = FakeNet()
            .route("direct", Behave.OK, rttMs = 50)
            .route("serverless", Behave.OK, rttMs = 70)
            .route("fragment", Behave.OK, rttMs = 90)
        val r = discover(fake, svc)
        assertEquals(2, fake.throughputAsked.size)
        assertEquals(setOf("direct", "serverless"), r.measured)
    }

    @Test fun `Worker is rejected for a Cloudflare-fronted service and a fail-closed service is blocked, not leaked`() {
        val gpt = FakeNet.service("chatgpt", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val fake = FakeNet()
            .route("direct", Behave.GEO_403_SIGNATURE)
            .route("serverless", Behave.GEO_403_SIGNATURE)
            .route("fragment", Behave.GEO_403_SIGNATURE)
            .echo("worker", FamilyPolicy.V4_ONLY, "US").echo("worker", FamilyPolicy.V6_ONLY, "US").echo("worker", FamilyPolicy.BOTH, "US")
        // No worker script: connect() to Cloudflare's own IPs never gets through.
        val r = discover(fake, gpt)
        assertTrue("IP abroad is not enough", r.proofs.none { it.serviceAccepted })
        assertTrue(r.egress is ForeignEgressStatus.NoneFound)
        val (_, d) = decideAfter(r, gpt)
        assertTrue(d is Decision.Block)
    }

    @Test fun `regionality - two networks learn independent routes`() {
        val tg = FakeNet.service("telegram", ServiceHints(likelyCensored = true))
        val a = discover(FakeNet().route("direct", Behave.TLS_RESET).route("serverless", Behave.OK, rttMs = 90).route("fragment", Behave.OK, rttMs = 300), tg)
        val b = discover(FakeNet().route("direct", Behave.TLS_RESET).route("serverless", Behave.TLS_RESET).route("fragment", Behave.OK, rttMs = 150), tg)
        val (s1, _) = decideAfter(a, tg, netKey = "irancell")
        val (s2, _) = decideAfter(b, tg, state = s1, netKey = "mci")
        assertEquals("serverless", s2.policies[MaeState.sk("telegram", "irancell")]!!.routeId)
        assertEquals("fragment", s2.policies[MaeState.sk("telegram", "mci")]!!.routeId)
    }

    @Test fun `hints are only priors - a likely-geo service that works locally stays local`() {
        val svc = FakeNet.service("spotify", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val fake = FakeNet().route("direct", Behave.OK, rttMs = 80).route("serverless", Behave.OK, rttMs = 90).route("fragment", Behave.OK, rttMs = 120)
        val (_, d) = decideAfter(discover(fake, svc), svc)
        assertTrue(d is Decision.Use)
        assertFalse((d as Decision.Use).routeId == "worker")
    }

    /** A second foreign provider, to show a repair can move to an exit other than the failed one. */
    private object OtherExit : RouteProvider {
        override val id = "exit2"
        override val kind = RouteKind.FOREIGN
        override val caps = Capabilities(udp = true)
        override fun outbounds() = listOf(JSONObject().put("tag", "mae-x2").put("protocol", "freedom"))
        override fun tag(family: FamilyPolicy) = "mae-x2"
    }

    @Test fun `user feedback - didn't open creates an incident, re-evaluates, and moves the route`() {
        val provs = providers + OtherExit
        val gemini = FakeNet.service("gemini", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val routes = FakeNet.routes() + ProbeRoute("exit2", RouteKind.FOREIGN, FamilyPolicy.BOTH, 9)
        val before = FakeNet()
            .route("direct", Behave.GEO_403_SIGNATURE).route("serverless", Behave.GEO_403_SIGNATURE).route("fragment", Behave.GEO_403_SIGNATURE)
            .route("worker", Behave.OK, rttMs = 100, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "US")
            // A worse exit: slow and thin, so the Worker wins while it works.
            .route("exit2", Behave.OK, rttMs = 1200, bps = 60_000.0).echo("exit2", FamilyPolicy.BOTH, "NL")
        val (s1, d1) = PolicyEngine.mergeDiscovery(MaeState(selected = listOf(SelectedService("gemini"))), gemini, net,
            runBlocking { Discovery(before, now = { 1L }).run(gemini, routes) }, { provs }, healthy, 1L)
        assertEquals(d1.toString(), "worker", (d1 as Decision.Use).routeId)

        // The user says it did not open.
        val s2 = PolicyEngine.recordFeedback(s1, "gemini", net, opened = false, now = 2L)
        assertTrue(s2.policies[MaeState.sk("gemini", net)]!!.confidence < s1.policies[MaeState.sk("gemini", net)]!!.confidence)

        // Re-discovery: the Worker path now fails; the other exit still works.
        val after = FakeNet()
            .route("direct", Behave.GEO_403_SIGNATURE).route("serverless", Behave.GEO_403_SIGNATURE).route("fragment", Behave.GEO_403_SIGNATURE)
            .route("worker", Behave.TCP_TIMEOUT, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "US")
            .route("exit2", Behave.OK, rttMs = 1200, bps = 60_000.0).echo("exit2", FamilyPolicy.BOTH, "NL")
        val prior = provs.associate { it.id to (s2.metrics[MaeState.rk(it.id, "gemini", net)] ?: com.mlmvpn.scanner.engines.mae.model.RouteMetrics()) }
        val (s3, d3) = PolicyEngine.mergeDiscovery(s2, gemini, net,
            runBlocking { Discovery(after, now = { 3L }).run(gemini, routes, prior) }, { provs }, healthy, 3L, incident = true)
        assertEquals(d3.toString(), "exit2", (d3 as Decision.Use).routeId)
        assertEquals(1, s3.incidents.size)
    }

    @Test fun `MCI case - IPv4 and IPv6 of one network are different countries, and direct IPv4 is chosen`() {
        // Measured 2026-09-29: api.openai.com served MCI's IPv4 (401) and refused its IPv6 (403 unsupported_country).
        val gpt = FakeNet.service("chatgpt", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val routes = listOf(
            ProbeRoute("direct", RouteKind.DIRECT, FamilyPolicy.V4_ONLY),
            ProbeRoute("direct", RouteKind.DIRECT, FamilyPolicy.V6_ONLY),
            ProbeRoute("serverless", RouteKind.BYPASS, socksPort = 1),
            ProbeRoute("fragment", RouteKind.BYPASS, socksPort = 2),
        )
        val fake = FakeNet()
            .route("direct", Behave.OK, rttMs = 300, family = FamilyPolicy.V4_ONLY)
            .route("direct", Behave.GEO_403_SIGNATURE, family = FamilyPolicy.V6_ONLY)
        val r = runBlocking { Discovery(fake, now = { 1L }).run(gpt, routes) }
        assertTrue(r.proofs.any { it.routeId == "direct:V4_ONLY" && it.serviceAccepted })
        assertTrue(r.proofs.any { it.routeId == "direct:V6_ONLY" && !it.serviceAccepted })
        val (_, d) = decideAfter(r, gpt)
        d as Decision.Use
        assertEquals("direct", d.routeId)
        assertEquals("the family that works, never both", FamilyPolicy.V4_ONLY, d.family)
    }

    @Test fun `a network where every Iranian bypass is dead and only the foreign exit gets through`() {
        // MCI 2026-09-29: fragment and Serverless reached nothing; YouTube direct was filtered.
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true, heavy = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.TCP_TIMEOUT)
            .route("fragment", Behave.TCP_TIMEOUT)
            .route("worker", Behave.OK, rttMs = 180, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "DE")
        val r = discover(fake, yt)
        assertEquals(Tri.YES, r.diagnosis[Axis.CENSORSHIP].state)
        val (_, d) = decideAfter(r, yt)
        assertEquals("worker", (d as Decision.Use).routeId)
    }

    @Test fun `a custom site that works locally spends no foreign-exit quota`() {
        val site = com.mlmvpn.scanner.engines.mae.registry.ServiceRegistry.customSite("example.org")!!
        val fake = FakeNet().route("direct", Behave.BARE_403).route("serverless", Behave.OK, rttMs = 90)
            .echo("worker", FamilyPolicy.V4_ONLY, "US")
        discover(fake, site)
        assertTrue(fake.echoAsked.isEmpty())
        assertTrue(fake.observed.none { it.startsWith("worker") })
    }

    @Test fun `MCI with WARP - the unmetered UDP bypass carries YouTube, the Worker is kept for Gemini`() {
        val warpRoute = com.mlmvpn.scanner.engines.mae.route.WarpRoute("k", "p", "172.16.0.2", "", listOf(0, 0, 0), "162.159.192.1:2408")
        val provs = listOf(DirectRoute, ServerlessRoute, FragmentRoute, warpRoute, worker)
        val routes = FakeNet.routes() + ProbeRoute("warp", RouteKind.BYPASS, FamilyPolicy.V4_ONLY, 7)
        // Measured: WARP opens YouTube and is refused by Gemini (its exit is Iran to them).
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true, heavy = true))
        val ytNet = FakeNet().route("direct", Behave.TLS_RESET).route("serverless", Behave.TCP_TIMEOUT).route("fragment", Behave.TCP_TIMEOUT)
            .route("warp", Behave.OK, rttMs = 170, bps = 1_200_000.0, family = FamilyPolicy.V4_ONLY)
            .route("worker", Behave.OK, rttMs = 180, bps = 1_200_000.0, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "BG")
        val (_, dy) = PolicyEngine.mergeDiscovery(MaeState(), yt, net, runBlocking { Discovery(ytNet, now = { 1L }).run(yt, routes) }, { provs }, healthy, 1L)
        assertEquals("warp", (dy as Decision.Use).routeId)

        val gem = FakeNet.service("gemini", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val gemNet = FakeNet().route("direct", Behave.GEO_403_SIGNATURE).route("serverless", Behave.TCP_TIMEOUT).route("fragment", Behave.TCP_TIMEOUT)
            .route("warp", Behave.GEO_403_SIGNATURE, family = FamilyPolicy.V4_ONLY)
            .route("worker", Behave.OK, rttMs = 180, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "BG")
        val (_, dg) = PolicyEngine.mergeDiscovery(MaeState(), gem, net, runBlocking { Discovery(gemNet, now = { 1L }).run(gem, routes) }, { provs }, healthy, 1L)
        assertEquals("worker", (dg as Decision.Use).routeId)
    }
}
