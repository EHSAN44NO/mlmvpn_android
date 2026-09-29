package com.mlmvpn.scanner.engines.mae

import com.mlmvpn.scanner.engines.mae.FakeNet.Behave
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.Health
import com.mlmvpn.scanner.engines.mae.model.ServiceHints
import com.mlmvpn.scanner.engines.mae.policy.Decision
import com.mlmvpn.scanner.engines.mae.policy.Discovery
import com.mlmvpn.scanner.engines.mae.policy.PolicyEngine
import com.mlmvpn.scanner.engines.mae.policy.RepairLadder
import com.mlmvpn.scanner.engines.mae.policy.RepairState
import com.mlmvpn.scanner.engines.mae.policy.Symptom
import com.mlmvpn.scanner.engines.mae.probe.ProbeRoute
import com.mlmvpn.scanner.engines.mae.route.DirectRoute
import com.mlmvpn.scanner.engines.mae.route.FragmentRoute
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.ServerlessRoute
import com.mlmvpn.scanner.engines.mae.route.WarpRoute
import com.mlmvpn.scanner.engines.mae.route.WorkerRoute
import com.mlmvpn.scanner.engines.mae.store.MaeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairLadderTest {
    private val worker = WorkerRoute("w.example.workers.dev", "00000000-0000-0000-0000-000000000000")
    private val warp = WarpRoute("k", "p", "172.16.0.2", "", listOf(0, 0, 0), "162.159.192.1:2408")
    private val providers = listOf(DirectRoute, ServerlessRoute, FragmentRoute, warp, worker)
    private val healthy: (String) -> Health = { Health.HEALTHY }
    private val net = "n"

    @Test fun `ladder - climbs one rung per dislike, remembers failed routes, caps at five, resets after a day`() {
        var s: RepairState? = null
        for (i in 1..7) s = RepairLadder.next(s, "serverless:BOTH".takeIf { i == 1 } ?: "warp:BOTH", 1000L * i)
        assertEquals(5, s!!.level)
        assertEquals(listOf("serverless:BOTH", "warp:BOTH"), s.tried)
        assertTrue(RepairLadder.exhausted(s))
        val later = RepairLadder.next(s, "direct:V4_ONLY", 1000L * 7 + RepairLadder.RESET_MS + 1)
        assertEquals(1, later.level)
        assertEquals(listOf("direct:V4_ONLY"), later.tried)
    }

    @Test fun `ladder - the question comes from the second dislike, not the first`() {
        assertFalse(RepairLadder.needsQuestion(RepairState(level = 1)))
        assertTrue(RepairLadder.needsQuestion(RepairState(level = 2)))
    }

    @Test fun `ladder - every rung is a different approach`() {
        val plans = (1..5).map { l ->
            RepairLadder.plan(RepairState(level = l, symptom = if (l >= 2) Symptom.NOT_OPENING else null,
                tried = listOf("serverless:BOTH", "warp:BOTH").take(l.coerceAtMost(2))))
        }
        // With the same history, no two rungs do the same thing.
        val same = (1..5).map { l -> RepairLadder.plan(RepairState(level = l, symptom = if (l >= 2) Symptom.NOT_OPENING else null, tried = listOf("warp:BOTH"))) }
        assertEquals(5, same.map { it.copy(level = 0, why = "") }.distinct().size)
        assertFalse(plans[1].forceForeign)
        assertTrue("rung 3 of NOT_OPENING also tries foreign exits", plans[2].forceForeign)
        assertTrue("rung 5 is the deep check", plans[4].deep)
    }

    @Test fun `ladder - each symptom steers differently`() {
        fun p(sym: Symptom, l: Int = 3) = RepairLadder.plan(RepairState(level = l, symptom = sym, tried = listOf("warp:BOTH")))
        assertTrue(p(Symptom.GEO_BLOCKED).needsForeign)
        assertTrue(p(Symptom.MEDIA_CALLS).requireUdp)
        assertTrue(p(Symptom.SLOW).preferThroughput)
        assertTrue(p(Symptom.LOGIN).stableExit)
        assertTrue(p(Symptom.PARTIAL_LOAD).learnHosts)
        assertTrue(p(Symptom.PARTIAL_LOAD).requireUdp)
    }

    private fun state(): MaeState {
        // YouTube-like app where WARP was chosen, and the Worker and fragment also served it.
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true, heavy = true))
        val fake = FakeNet()
            .route("direct", Behave.TLS_RESET)
            .route("serverless", Behave.TCP_TIMEOUT)
            .route("fragment", Behave.OK, rttMs = 300, bps = 800_000.0)
            .route("warp", Behave.OK, rttMs = 120, bps = 3_000_000.0, family = FamilyPolicy.V4_ONLY)
            .route("worker", Behave.OK, rttMs = 200, bps = 1_500_000.0, family = FamilyPolicy.V4_ONLY).echo("worker", FamilyPolicy.V4_ONLY, "DE")
        val routes = FakeNet.routes() + ProbeRoute("warp", RouteKind.BYPASS, FamilyPolicy.V4_ONLY, 7)
        val r = runBlocking { Discovery(fake, now = { 1L }).run(yt, routes, forceForeign = true) }
        return PolicyEngine.mergeDiscovery(MaeState(), yt, net, r, { providers }, healthy, 1L).first
    }

    @Test fun `repair - the failed route is set aside, and a geo complaint forces a proven foreign exit`() {
        val s = state()
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true, heavy = true))
        val first = PolicyEngine.decide(s, yt, net, providers, healthy, 2L).first as Decision.Use
        assertEquals("warp", first.routeId)

        val rung1 = RepairLadder.plan(RepairState(level = 1, tried = listOf("warp:V4_ONLY")))
        val second = PolicyEngine.decide(s, yt, net, providers, healthy, 3L, rung1).first as Decision.Use
        assertNotEquals("warp", second.routeId)

        val geo = RepairLadder.plan(RepairState(level = 2, symptom = Symptom.GEO_BLOCKED, tried = listOf("warp:V4_ONLY")))
        val third = PolicyEngine.decide(s, yt, net, providers, healthy, 4L, geo).first as Decision.Use
        assertEquals("worker", third.routeId)
    }

    @Test fun `repair - calls need UDP, login avoids the Worker's changing exit`() {
        val s = state()
        val yt = FakeNet.service("youtube", ServiceHints(likelyCensored = true, heavy = true))
        val calls = RepairLadder.plan(RepairState(level = 2, symptom = Symptom.MEDIA_CALLS))
        val c = PolicyEngine.decide(s, yt, net, providers, healthy, 5L, calls).first as Decision.Use
        assertEquals("the only served route that carries UDP", "warp", c.routeId)
        val login = RepairLadder.plan(RepairState(level = 2, symptom = Symptom.LOGIN, tried = listOf("warp:V4_ONLY")))
        val l = PolicyEngine.decide(s, yt, net, providers, healthy, 6L, login).first as Decision.Use
        assertNotEquals("worker", l.routeId)
    }
}
