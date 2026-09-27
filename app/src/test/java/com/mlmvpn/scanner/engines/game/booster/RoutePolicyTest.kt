package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.decide.Claims
import com.mlmvpn.scanner.engines.game.booster.decide.PathMeasurement
import com.mlmvpn.scanner.engines.game.booster.decide.ProbeMethod
import com.mlmvpn.scanner.engines.game.booster.decide.RouteDecider
import com.mlmvpn.scanner.engines.game.booster.decide.RoutePolicy
import com.mlmvpn.scanner.engines.game.booster.decide.RoutePolicy.Access
import com.mlmvpn.scanner.engines.game.booster.decide.Verdict
import com.mlmvpn.scanner.engines.game.booster.model.RouteChoice
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import com.mlmvpn.scanner.engines.game.booster.probe.ProbeStats
import com.mlmvpn.scanner.engines.game.booster.probe.RttSample
import com.mlmvpn.scanner.engines.game.booster.session.AccessState
import com.mlmvpn.scanner.engines.game.booster.session.VerdictKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyTest {

    private fun stats(base: Double, wobble: Double = 2.0) =
        ProbeStats.stats((0 until 60).map { RttSample(it, base + if (it % 2 == 0) 0.0 else wobble) }, 60)

    private val silent = PathMeasurement(RouteKind.DIRECT, ProbeMethod.TCP, PathStats.empty(12))
    private val direct = PathMeasurement(RouteKind.DIRECT, ProbeMethod.ECHO, stats(90.0))
    private val warp = PathMeasurement(RouteKind.WARP_MASQUE_H3, ProbeMethod.TCP, stats(160.0))
    private val betterWarp = PathMeasurement(RouteKind.WARP_MASQUE_H3, ProbeMethod.ECHO, stats(70.0))

    private fun choose(
        verdict: Verdict, d: PathMeasurement, w: PathMeasurement?, access: Access = Access(),
        choice: RouteChoice = RouteChoice.AUTO, udpSilent: Boolean = false,
    ) = RoutePolicy.choose(RoutePolicy.Input(verdict, choice, d, w, udpSilent, access))

    @Test
    fun `silent test points never push a working game into a tunnel`() {
        // Irancell: anchors dark, WARP answers, the game's own sign-in is fine.
        val out = choose(Verdict.MustTunnel(warp), silent, warp, udpSilent = true)
        assertEquals(RouteKind.DIRECT, out.route)
        assertEquals(VerdictKind.UNMEASURED, out.verdictKind)
        assertTrue(out.offerWarp)
    }

    @Test
    fun `a sign-in filtered on this line takes WARP when WARP reaches it`() {
        val out = choose(Verdict.MustTunnel(warp), silent, warp, Access(coreNeedsTunnel = true, anyProblem = true))
        assertEquals(RouteKind.WARP_MASQUE_H3, out.route)
        assertEquals(VerdictKind.MUST_TUNNEL, out.verdictKind)
        assertEquals(AccessState.OK, out.access)
    }

    @Test
    fun `a filtered sign-in with no WARP is said to be blocked`() {
        val out = choose(Verdict.StayDirect(direct, null), direct, null, Access(coreNeedsTunnel = true, anyProblem = true))
        assertEquals(RouteKind.DIRECT, out.route)
        assertEquals(AccessState.BLOCKED, out.access)
    }

    @Test
    fun `a sanctioned sign-in fixed by an anti-sanction DNS gets the DNS-only route`() {
        val out = choose(Verdict.StayDirect(direct, null), direct, null,
            Access(coreGeoBlocked = true, sanctionFixed = true, anyProblem = true))
        assertEquals(RouteKind.DIRECT_DNS, out.route)
        assertEquals(AccessState.FIXED_BY_DNS, out.access)
        assertFalse(out.sanctionUnfixed)
    }

    @Test
    fun `signing in beats a few milliseconds of WARP`() {
        val switch = Verdict.Switch(betterWarp, direct, RouteDecider.claims(direct.stats, betterWarp.stats))
        val out = choose(switch, direct, betterWarp, Access(coreGeoBlocked = true, sanctionFixed = true, anyProblem = true))
        assertEquals(RouteKind.DIRECT_DNS, out.route)
        assertTrue(out.warpYieldedToSignIn)
        // Unless the player asked for WARP.
        val pinned = choose(switch, direct, betterWarp, Access(coreGeoBlocked = true, sanctionFixed = true, anyProblem = true),
            choice = RouteChoice.WARP)
        assertEquals(RouteKind.WARP_MASQUE_H3, pinned.route)
    }

    @Test
    fun `a clearly better WARP is taken when nothing needs the direct line`() {
        val switch = Verdict.Switch(betterWarp, direct, Claims.NONE)
        val out = choose(switch, direct, betterWarp)
        assertEquals(RouteKind.WARP_MASQUE_H3, out.route)
        assertEquals(VerdictKind.SWITCH, out.verdictKind)
        assertFalse(out.offerWarp)
    }

    @Test
    fun `poisoned DNS is fixed on the direct line, a sanction nobody opened is said so`() {
        val out = choose(Verdict.StayDirect(direct, null), direct, null, Access(havePins = true, coreGeoBlocked = true, anyProblem = true))
        assertEquals(RouteKind.DIRECT_DNS, out.route)
        assertTrue(out.sanctionUnfixed)
    }

    @Test
    fun `the player's own line stays their own line`() {
        val out = choose(Verdict.MustTunnel(warp), silent, warp, Access(coreNeedsTunnel = true, anyProblem = true),
            choice = RouteChoice.DIRECT)
        assertEquals(RouteKind.DIRECT, out.route)
        assertFalse(out.offerWarp)
        assertEquals(AccessState.BLOCKED, out.access)
    }

    private fun chooseLearned(verdict: Verdict, d: PathMeasurement, w: PathMeasurement?, rates: Map<String, Double?>,
                              access: Access = Access()) =
        RoutePolicy.choose(RoutePolicy.Input(verdict, RouteChoice.AUTO, d, w, false, access, planRates = rates))

    @Test
    fun `a direct line that kept failing here gives way to a reachable WARP`() {
        val out = chooseLearned(Verdict.StayDirect(direct, betterWarp), direct, betterWarp, mapOf("D" to 0.2))
        assertEquals(RouteKind.WARP_MASQUE_H3, out.route)
        assertTrue(out.escalated)
        assertEquals(VerdictKind.LEARNED, out.verdictKind)
    }

    @Test
    fun `a WARP that kept failing here gives way to the direct line`() {
        val switch = Verdict.Switch(betterWarp, direct, Claims.NONE)
        val out = chooseLearned(switch, direct, betterWarp, mapOf("W3" to 0.1, "D" to 0.6))
        assertEquals(RouteKind.DIRECT, out.route)
        assertTrue(out.escalated)
    }

    @Test
    fun `learning never moves a sanctioned sign-in onto WARP, nor off the only route that reaches the game`() {
        val geo = Access(coreGeoBlocked = true, sanctionFixed = true, anyProblem = true)
        val out = chooseLearned(Verdict.StayDirect(direct, betterWarp), direct, betterWarp, mapOf("DD" to 0.1), geo)
        assertEquals(RouteKind.DIRECT_DNS, out.route)
        assertFalse(out.escalated)
        val onlyWarp = Access(coreNeedsTunnel = true, anyProblem = true)
        val kept = chooseLearned(Verdict.MustTunnel(warp), silent, warp, mapOf("W3" to 0.1), onlyWarp)
        assertEquals(RouteKind.WARP_MASQUE_H3, kept.route)
        assertFalse(kept.escalated)
    }

    @Test
    fun `too little evidence changes nothing, and a fragment fix keeps the game on the DNS-only route`() {
        val out = chooseLearned(Verdict.StayDirect(direct, null), direct, null, mapOf("D" to null))
        assertEquals(RouteKind.DIRECT, out.route)
        val frag = choose(Verdict.StayDirect(direct, null), direct, null, Access(fragmentFixes = true, anyProblem = true))
        assertEquals(RouteKind.DIRECT_DNS, frag.route)
    }
}
