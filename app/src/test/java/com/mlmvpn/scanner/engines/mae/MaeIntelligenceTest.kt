package com.mlmvpn.scanner.engines.mae

import com.mlmvpn.scanner.engines.mae.FakeNet.Behave
import com.mlmvpn.scanner.engines.mae.compile.MaeConfigCompiler
import com.mlmvpn.scanner.engines.mae.model.FailMode
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.model.RouteMetrics
import com.mlmvpn.scanner.engines.mae.model.ServiceHints
import com.mlmvpn.scanner.engines.mae.model.ServicePolicy
import com.mlmvpn.scanner.engines.mae.policy.Decision
import com.mlmvpn.scanner.engines.mae.policy.Discovery
import com.mlmvpn.scanner.engines.mae.policy.EchoCache
import com.mlmvpn.scanner.engines.mae.policy.PolicyEngine
import com.mlmvpn.scanner.engines.mae.policy.RepairLadder
import com.mlmvpn.scanner.engines.mae.policy.RepairState
import com.mlmvpn.scanner.engines.mae.policy.Symptom
import com.mlmvpn.scanner.engines.mae.probe.Observation
import com.mlmvpn.scanner.engines.mae.probe.ProbeRoute
import com.mlmvpn.scanner.engines.mae.probe.Step
import com.mlmvpn.scanner.engines.mae.registry.ServiceRegistry
import com.mlmvpn.scanner.engines.mae.route.CircuitBreaker
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteHealth
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.UserConfigRoute
import com.mlmvpn.scanner.engines.mae.route.WarpRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.engines.mae.store.MaeState
import com.mlmvpn.scanner.engines.mae.store.MaeStateCodec
import com.mlmvpn.scanner.engines.mae.store.SelectedService
import com.mlmvpn.scanner.engines.mae.store.UserEvidence
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What 1.2.38 changed in how MAE decides: route health from the transport, repairs that stick,
 * apps that refuse Iran whatever their web page says, and the compiled shape that lets a route
 * change reach every kind of traffic.
 */
class MaeIntelligenceTest {
    private val worker = WorkerRoute("mae.example.workers.dev", "00000000-0000-0000-0000-000000000000")
    private val warp = WarpRoute("k", "p", "172.16.0.2", "", listOf(0, 0, 0), "162.159.192.1:2408")
    private val cfg = UserConfigRoute(UserConfigRoute.idFor("node-1"), JSONObject().put("protocol", "vless").put("tag", "proxy"), "BG node")
    private val providers = listOf(DirectRoute, ServerlessRoute, FragmentRoute, warp, worker, cfg)
    private val healthy: (String) -> Health = { Health.HEALTHY }
    private val net = "netA"

    // ---------------------------------------------------------------- route health

    @Test fun `breaker - a route allowed but not probed is not locked out, and one bad service does not kill a live route`() {
        val b = CircuitBreaker(threshold = 3, baseBackoffMs = 1000, maxBackoffMs = 60_000, aliveGraceMs = 10_000)
        val k = CircuitBreaker.key("direct", net)
        b.observe(k, RouteHealth.Verdict.ALIVE, 0)
        // Three filtered apps in a row within the grace: direct is still alive for everything else.
        repeat(3) { b.observe(k, RouteHealth.Verdict.DEAD, 1_000L + it) }
        assertTrue(b.allow(k, 2_000))
        assertEquals(Health.HEALTHY, b.health(k, 2_000))
        // Nothing alive for longer than the grace: now it counts.
        repeat(3) { b.observe(k, RouteHealth.Verdict.DEAD, 20_000L + it) }
        assertEquals(Health.UNAVAILABLE, b.health(k, 20_500))
        assertTrue("after the backoff it is tried again, and asking twice does not lock it", b.allow(k, 22_000) && b.allow(k, 22_001))
        assertTrue("per network: the same route elsewhere is untouched", b.allow(CircuitBreaker.key("direct", "netB"), 20_500))
    }

    @Test fun `route health - transport signals, not whether one service answered`() {
        val filteredDirect = Observation("direct", false, true, tcp = Step.OK, tls = Step.RESET)
        assertEquals("TCP connected: the path is up, the filter cut TLS", RouteHealth.Verdict.ALIVE, RouteHealth.verdict(RouteKind.DIRECT, listOf(filteredDirect)))
        val deadDirect = Observation("direct", false, true, tcp = Step.TIMEOUT)
        assertEquals(RouteHealth.Verdict.DEAD, RouteHealth.verdict(RouteKind.DIRECT, listOf(deadDirect)))
        val echoOnly = Observation("worker", true, false, tcp = Step.OK, tls = Step.RESET, exitAlive = true)
        assertEquals("the exit echoed: alive even if this service was not reached", RouteHealth.Verdict.ALIVE, RouteHealth.verdict(RouteKind.FOREIGN, listOf(echoOnly)))
        assertEquals(RouteHealth.Verdict.UNKNOWN, RouteHealth.verdict(RouteKind.BYPASS, emptyList()))
        assertTrue(RouteHealth.networkLooksDown(listOf(deadDirect, Observation("serverless", false, false, tcp = Step.OK, tls = Step.TIMEOUT))))
        assertFalse(RouteHealth.networkLooksDown(listOf(filteredDirect)))
    }

    // ---------------------------------------------------------------- scorer

    @Test fun `scorer - a fail-open app is never blackholed, even when every route is paused`() {
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true))
        val dead: (String) -> Health = { Health.UNAVAILABLE }
        val d = PolicyEngine.decide(MaeState(), yt, net, providers, dead, 1L, fallbackRoute = DirectRoute.id).first
        assertTrue("fail-open must not become block", d is Decision.Use)
        assertEquals("the network's default path", DirectRoute.id, (d as Decision.Use).routeId)
    }

    @Test fun `scorer - a fail-closed app on a network not checked yet never takes an Iranian route`() {
        val gpt = FakeNet.service("chatgpt", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val d = PolicyEngine.decide(MaeState(), gpt, net, providers, healthy, 1L).first
        if (d is Decision.Use) assertEquals("only a foreign exit, never local", RouteKind.FOREIGN, providers.first { it.id == d.routeId }.kind)
    }

    @Test fun `scorer - a fail-closed app starts a new network on the foreign exit it proved elsewhere`() {
        val gpt = FakeNet.service("chatgpt", ServiceHints(likelyGeoRestricted = true, failMode = FailMode.CLOSED))
        val s = MaeState(policies = mapOf(MaeState.sk("chatgpt", "netB") to ServicePolicy("chatgpt", "netB", cfg.id, FamilyPolicy.V4_ONLY, decidedAt = 5)))
        val d = PolicyEngine.decide(s, gpt, net, providers, healthy, 10L).first as Decision.Use
        assertEquals(cfg.id, d.routeId)
        assertEquals(FamilyPolicy.V4_ONLY, d.family)
    }

    @Test fun `scorer - hysteresis holds for small and negative scores too`() {
        fun cand(id: String, ok: Int, bad: Int) = com.mlmvpn.scanner.engines.mae.policy.Candidate(id, RouteKind.BYPASS,
            RouteMetrics(ok, bad, consecutiveFailures = 3, lastOkAt = 1, lastTestedAt = 1), usable = true, acceptedFamilies = setOf(FamilyPolicy.BOTH))
        val a = cand("fragment", 1, 9); val b = cand("serverless", 1, 8)
        val d = com.mlmvpn.scanner.engines.mae.policy.RouteScorer.decide(PolicyEngine.requirements(MaeState(), FakeNet.service("x"), net),
            FailMode.OPEN, false, listOf(a, b), current = "fragment") as Decision.Use
        assertEquals("a hair better is not worth a switch", "fragment", d.routeId)
    }

    // ---------------------------------------------------------------- repairs

    @Test fun `ladder - an app that usually refuses Iran goes abroad on the first dislike`() {
        val p = RepairLadder.plan(RepairState(level = 1, tried = listOf("fragment:BOTH")), likelyGeo = true, onLocalRoute = true)
        assertTrue(p.forceForeign && p.needsForeign)
        val plain = RepairLadder.plan(RepairState(level = 1, tried = listOf("fragment:BOTH")))
        assertFalse(plain.needsForeign)
    }

    @Test fun `ladder - rung five keeps the country conclusion, and the last failure is the last one`() {
        val s = RepairState(level = 5, symptom = Symptom.NOT_OPENING, tried = listOf("direct:V4_ONLY", "warp:BOTH", "direct:V6_ONLY"))
        val p = RepairLadder.plan(s)
        assertTrue("rung 5 used to drop the foreign requirement", p.needsForeign)
        assertTrue(p.deep)
        val l = RepairLadder.plan(RepairState(level = 1, tried = listOf("direct:V4_ONLY", "warp:BOTH", "direct:V6_ONLY")))
        assertEquals(setOf("direct"), l.excludedRoutes)
    }

    @Test fun `ladder - a day's quiet starts over, and 'the app says no internet' is a country refusal`() {
        assertNull(RepairLadder.active(RepairState(level = 3, at = 0), RepairLadder.RESET_MS + 1))
        assertEquals(1, RepairLadder.nextLevel(RepairState(level = 3, at = 0), RepairLadder.RESET_MS + 1))
        val p = RepairLadder.plan(RepairState(level = 2, symptom = Symptom.APP_SAYS_OFFLINE, tried = listOf("worker:BOTH")))
        assertTrue(p.needsForeign && p.deviceCheck)
        assertEquals(setOf("worker"), p.excludedRoutes)
    }

    /** TikTok on a network where Iranian routes load its web page, and a foreign config serves it too. */
    private fun tiktokState(): Pair<MaeState, com.mlmvpn.scanner.engines.mae.model.ServiceDef> {
        val tt = FakeNet.service("tiktok", ServiceHints(likelyCensored = true, likelyGeoRestricted = true, heavy = true, requiresForeign = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.OK, rttMs = 90)
            .route("fragment", Behave.OK, rttMs = 110)
            .route(cfg.id, Behave.OK, rttMs = 260, family = FamilyPolicy.V4_ONLY).echo(cfg.id, FamilyPolicy.V4_ONLY, "BG")
        val routes = FakeNet.routes(withWorker = false) + ProbeRoute(cfg.id, RouteKind.FOREIGN, FamilyPolicy.V4_ONLY, 9)
        val r = runBlocking { Discovery(fake, now = { 1L }).run(tt, routes) }
        assertTrue("foreign probed although a local route answered", fake.echoAsked.isNotEmpty())
        return PolicyEngine.mergeDiscovery(MaeState(selected = listOf(SelectedService("tiktok"))), tt, net, r, { providers }, healthy, 1L).first to tt
    }

    @Test fun `TikTok - an app that refuses Iranian addresses goes abroad even when its web page opens locally`() {
        val (s, _) = tiktokState()
        val p = s.policies[MaeState.sk("tiktok", net)]!!
        assertEquals("the fast local routes serve the web page, not the app", cfg.id, p.routeId)
    }

    @Test fun `repairs stick - what the user reported survives a routine re-check`() {
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true, heavy = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.OK, rttMs = 80, bps = 3_000_000.0)
            .route("fragment", Behave.OK, rttMs = 300, bps = 800_000.0)
        val r = runBlocking { Discovery(fake, now = { 1L }).run(yt, FakeNet.routes(withWorker = false)) }
        var s = PolicyEngine.mergeDiscovery(MaeState(), yt, net, r, { providers }, healthy, 1L).first
        assertEquals("serverless", s.policies[MaeState.sk("youtube", net)]!!.routeId)
        // The user: "didn't open" on serverless. A routine refresh later must not walk back into it.
        s = PolicyEngine.withEvidence(s, "youtube", net, 2L, failedRoute = "serverless:BOTH")
        val again = PolicyEngine.mergeDiscovery(s, yt, net, r, { providers }, healthy, 3L).first
        assertEquals("fragment", again.policies[MaeState.sk("youtube", net)]!!.routeId)
        // "Opened" on serverless later clears it.
        val cleared = PolicyEngine.withEvidence(again, "youtube", net, 4L, openedRoute = "serverless:BOTH", openedLocally = true)
        assertNull(cleared.userEvidence[MaeState.sk("youtube", net)])
        // Evidence fades after a week.
        val old = PolicyEngine.withEvidence(MaeState(), "youtube", net, 0L, failedRoute = "serverless:BOTH")
        assertTrue(old.userEvidence[MaeState.sk("youtube", net)]!!.failedRoutes(UserEvidence.TTL_MS + 1).isEmpty())
    }

    @Test fun `a country refusal the user reported keeps the app abroad on routine re-checks`() {
        val sp = FakeNet.service("spotify", ServiceHints(likelyGeoRestricted = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.OK, rttMs = 80)
            .route("fragment", Behave.OK, rttMs = 90)
            .route(cfg.id, Behave.OK, rttMs = 300, family = FamilyPolicy.V4_ONLY).echo(cfg.id, FamilyPolicy.V4_ONLY, "DE")
        val routes = FakeNet.routes(withWorker = false) + ProbeRoute(cfg.id, RouteKind.FOREIGN, FamilyPolicy.V4_ONLY, 9)
        val r = runBlocking { Discovery(fake, now = { 1L }).run(sp, routes, forceForeign = true) }
        val s0 = PolicyEngine.withEvidence(MaeState(), "spotify", net, 1L, geo = true)
        val s = PolicyEngine.mergeDiscovery(s0, sp, net, r, { providers }, healthy, 2L).first
        assertEquals(cfg.id, s.policies[MaeState.sk("spotify", net)]!!.routeId)
    }

    @Test fun `pin - the pinned flag stays with the route the user pinned`() {
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true))
        val s = MaeState(policies = mapOf(MaeState.sk("youtube", net) to ServicePolicy("youtube", net, "gone", pinned = true)))
        val (_, p) = PolicyEngine.decide(s, yt, net, providers, healthy, 1L)
        assertFalse("another route is not the user's pin", p!!.pinned)
    }

    // ---------------------------------------------------------------- discovery

    @Test fun `discovery - exit echoes are reused across apps, and a dead network is not recorded as dead routes`() {
        val cache = EchoCache()
        val fake = FakeNet().route("direct", Behave.TLS_RESET).route("worker", Behave.OK, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "DE")
        val gem = FakeNet.service("gemini", ServiceHints(likelyGeoRestricted = true))
        runBlocking { Discovery(fake, now = { 1L }, echoes = cache, net = net).run(gem, FakeNet.routes()) }
        val first = fake.echoAsked.size
        runBlocking { Discovery(fake, now = { 2L }, echoes = cache, net = net).run(FakeNet.service("claude", ServiceHints(likelyGeoRestricted = true)), FakeNet.routes()) }
        assertEquals("the second app asked no exit again", first, fake.echoAsked.size)

        val down = runBlocking { Discovery(FakeNet(), now = { 1L }).run(gem, FakeNet.routes()) }
        assertTrue(down.offline)
    }

    @Test fun `discovery - throughput goes to two different routes, not two families of one`() {
        val fake = FakeNet()
            .route("direct", Behave.OK, rttMs = 50, family = FamilyPolicy.V4_ONLY)
            .route("direct", Behave.OK, rttMs = 60, family = FamilyPolicy.V6_ONLY)
            .route("serverless", Behave.OK, rttMs = 200)
        val routes = listOf(ProbeRoute("direct", RouteKind.DIRECT, FamilyPolicy.V4_ONLY), ProbeRoute("direct", RouteKind.DIRECT, FamilyPolicy.V6_ONLY),
            ProbeRoute("serverless", RouteKind.BYPASS, socksPort = 1))
        val r = runBlocking { Discovery(fake, now = { 1L }).run(FakeNet.service("github"), routes) }
        assertEquals(setOf("direct", "serverless"), r.measured)
    }

    // ---------------------------------------------------------------- compiled shape

    private val base = File("src/main/assets/serverless_v48_low_delay.json").readText()

    @Test fun `compiler - QUIC is refused on fragment routes and carried where the route helps it`() {
        val reg = ServiceRegistry.parse(File("src/main/assets/mae/services.json").readText())
        fun t(route: String) = MaeConfigCompiler.targetsFor(MaeConfigCompiler.ServiceRoute(reg.get("youtube")!!, Decision.Use(route, FamilyPolicy.BOTH, 1.0, ""), false), providers)!!
        assertEquals("tcp-fragment-tls", t("serverless").udp)
        assertEquals("block", t("serverless").quic)
        assertEquals("mae-wgd", t("warp").quic)
        assertEquals("mae-dd", t("direct").quic)
        assertEquals("block", t("fragment").quic)
    }

    @Test fun `compiler - an app's own connections follow its route first, and the live check has its inbound`() {
        val reg = ServiceRegistry.parse(File("src/main/assets/mae/services.json").readText())
        val routes = listOf(MaeConfigCompiler.ServiceRoute(reg.get("tiktok")!!, Decision.Use(cfg.id, FamilyPolicy.V4_ONLY, 1.0, ""), true, uids = listOf(10123)))
        val j = JSONObject(MaeConfigCompiler.compile(base, providers, routes, "/x/api.sock", canaryPort = 20808))
        val rules = j.getJSONObject("routing").getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val firstApp = rules.indexOfFirst { it.has("process") }
        val firstDomain = rules.indexOfFirst { it.has("domain") && it.optString("balancerTag").startsWith("svc-") }
        assertTrue("app rules come before domain rules", firstApp in 0 until firstDomain)
        assertEquals("10123", rules[firstApp].getJSONArray("process").getString(0))
        // Only the tun's connections have an owner to look up.
        assertTrue(rules.filter { it.has("process") }.all { it.getJSONArray("inboundTag").getString(0) == MaeConfigCompiler.TUN_IN_TAG })
        val inbounds = j.getJSONArray("inbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertTrue(inbounds.any { it.optString("tag") == MaeConfigCompiler.CANARY_IN_TAG && it.getInt("port") == 20808 && it.getString("listen") == "127.0.0.1" })
        val bal = j.getJSONObject("routing").getJSONArray("balancers").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("tag") } }
        assertTrue(bal.containsAll(listOf("svc-tiktok", "svc-tiktok-u", "svc-tiktok-q")))
        // The probe core never carries app rules or the live-check inbound.
        val probe = JSONObject(MaeConfigCompiler.compileProbe(base, providers, mapOf("tcp-fragment-tls" to 1111)))
        assertFalse(probe.toString().contains("\"process\""))
    }

    // ---------------------------------------------------------------- identity and memory

    @Test fun `user configs keep their identity whatever the list order, and tags never prefix each other`() {
        assertEquals(UserConfigRoute.idFor("node-1"), UserConfigRoute.idFor("node-1"))
        assertNotEquals(UserConfigRoute.idFor("node-1"), UserConfigRoute.idFor("node-2"))
        val tags = listOf("node-1", "node-2", "node-3").flatMap { n -> UserConfigRoute(UserConfigRoute.idFor(n), JSONObject(), n).let { r -> r.families.map { r.tag(it) } } }
        for (a in tags) for (b in tags) if (a != b) assertFalse("$a prefixes $b", b.startsWith(a))
    }

    @Test fun `state - schema 1 drops what was learned under list positions, and new fields round-trip`() {
        val v1 = JSONObject().put("schema", 1)
            .put("metrics", JSONObject().put("cfg1|tiktok|n", JSONObject().put("ok", 3)).put("worker|tiktok|n", JSONObject().put("ok", 1)))
            .put("proofs", JSONObject().put("cfg2:V4_ONLY|tiktok|n", JSONObject().put("r", "cfg2:V4_ONLY").put("f", "V4").put("acc", true)))
            .put("policies", JSONObject().put("tiktok|n", JSONObject().put("s", "tiktok").put("n", "n").put("r", "cfg3")))
        val s = MaeStateCodec.decode(v1.toString())
        assertEquals(setOf("worker|tiktok|n"), s.metrics.keys)
        assertTrue(s.proofs.isEmpty() && s.policies.isEmpty())

        val full = MaeState(
            userEvidence = mapOf("tiktok|n" to UserEvidence(geoAt = 5, failed = mapOf("fragment:BOTH" to 6))),
            liveApiFails = 2, liveApiBuild = 70, usExitVia = mapOf("n" to cfg.id), netSeen = mapOf("n" to 9),
            cloudLinks = listOf("v1:abc"), cloudLinksAt = 11, cloudLinksFailedAt = 12,
            metrics = mapOf("warp|tiktok|n" to RouteMetrics(1, 1, okRate = 0.42)),
        )
        val back = MaeStateCodec.decode(MaeStateCodec.encode(full))
        assertEquals(full.userEvidence, back.userEvidence)
        assertEquals(full.liveApiFails, back.liveApiFails)
        assertEquals(full.liveApiBuild, back.liveApiBuild)
        assertEquals(full.usExitVia, back.usExitVia)
        assertEquals(full.netSeen, back.netSeen)
        assertEquals(full.cloudLinks, back.cloudLinks)
        assertEquals(0.42, back.metrics["warp|tiktok|n"]!!.okRate!!, 1e-9)
    }

    @Test fun `metrics - success fades, so a route that failed long ago recovers its rate once it works`() {
        var m = RouteMetrics(successes = 0, failures = 20)
        repeat(5) { m = m.record(true, 100.0, it.toLong()) }
        assertTrue("five successes in a row outweigh twenty old failures", m.successRate > 0.8)
    }

    @Test fun `registry - TikTok refuses Iran and checks the phone, and Gemini's bundle leaves Play downloads alone`() {
        val reg = ServiceRegistry.parse(File("src/main/assets/mae/services.json").readText())
        val tt = reg.get("tiktok")!!
        assertTrue(tt.hints.requiresForeign)
        assertEquals(listOf("sim", "timezone"), tt.hints.clientChecks)
        assertTrue("tiktokv.us" in tt.domains)
        assertFalse(reg.get("gemini")!!.bundle.any { it == "gvt1.com" || it == "ggpht.com" })
        assertTrue("ggpht.com stays YouTube's", "ggpht.com" in reg.get("youtube")!!.domains)
    }
}
