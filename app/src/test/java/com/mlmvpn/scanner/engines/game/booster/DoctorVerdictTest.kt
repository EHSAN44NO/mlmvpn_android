package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.scanner.engines.game.booster.doctor.GameKind
import com.mlmvpn.scanner.engines.game.booster.doctor.HostCheck
import com.mlmvpn.scanner.engines.game.booster.doctor.HostVerdict
import com.mlmvpn.scanner.engines.game.booster.doctor.KindClassifier
import com.mlmvpn.scanner.engines.game.booster.doctor.Obstacle
import com.mlmvpn.scanner.engines.game.booster.doctor.SanctionProbe.Outcome
import com.mlmvpn.scanner.engines.game.booster.doctor.SanctionProbe
import com.mlmvpn.scanner.engines.game.booster.model.TrafficClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoctorVerdictTest {

    private fun host(cls: TrafficClass, o: Obstacle, name: String = "${cls.name.lowercase()}-${o.name.lowercase()}.example") =
        HostCheck(name, cls, emptyList(), null, emptyList(), null, o)

    @Test
    fun `a host that serves through the system answer is open, a 403 from it is a sanction`() {
        assertEquals(Obstacle.OK, HostVerdict.classify(true, Outcome.OPEN, null))
        assertEquals(Obstacle.GEO_BLOCKED, HostVerdict.classify(true, Outcome.GEO_BLOCKED, null))
    }

    @Test
    fun `a lying resolver is told apart from a filter by the clean answer`() {
        // Sinkholed, the clean address serves: only the DNS was wrong.
        assertEquals(Obstacle.DNS_POISONED, HostVerdict.classify(false, null, Outcome.OPEN))
        // A plausible but wrong address that resets, the clean one serves: still only DNS.
        assertEquals(Obstacle.DNS_POISONED, HostVerdict.classify(true, Outcome.TLS_RESET, Outcome.OPEN))
        // Both cut at the handshake: filtered on the name.
        assertEquals(Obstacle.SNI_BLOCKED, HostVerdict.classify(true, Outcome.TLS_RESET, Outcome.TLS_RESET))
        assertEquals(Obstacle.SNI_BLOCKED, HostVerdict.classify(false, null, Outcome.CERT_MISMATCH))
        assertEquals(Obstacle.IP_BLOCKED, HostVerdict.classify(true, Outcome.TCP_TIMEOUT, Outcome.TCP_TIMEOUT))
        // Poisoned AND refused on country.
        assertEquals(Obstacle.GEO_BLOCKED, HostVerdict.classify(false, null, Outcome.GEO_BLOCKED))
    }

    @Test
    fun `a name that resolves nowhere is unknown, never blocked`() {
        assertEquals(Obstacle.UNKNOWN, HostVerdict.classify(false, null, null))
        assertEquals(Obstacle.UNKNOWN, HostVerdict.classify(false, null, Outcome.ERROR))
        // With only the system's failed attempt to go on, its failure is what we know.
        assertEquals(Obstacle.SNI_BLOCKED, HostVerdict.classify(true, Outcome.TLS_RESET, null))
    }

    @Test
    fun `only 403 and 451 read as a country refusal`() {
        assertEquals(Outcome.GEO_BLOCKED, SanctionProbe.classifyStatus(403))
        assertEquals(Outcome.GEO_BLOCKED, SanctionProbe.classifyStatus(451))
        listOf(200, 204, 301, 302, 400, 401, 404, 405, 500).forEach {
            assertEquals("status $it", Outcome.OPEN, SanctionProbe.classifyStatus(it))
        }
    }

    @Test
    fun `filtering answers are recognised, real ones are not`() {
        listOf("10.10.34.35", "0.0.0.0", "127.0.0.1", "198.18.0.5", "198.41.0.4").forEach {
            assertTrue(it, HostVerdict.isFakeAnswer(it))
        }
        listOf("185.51.200.2", "104.16.1.1", "10.202.10.202").forEach { assertFalse(it, HostVerdict.isFakeAnswer(it)) }
        assertTrue(HostVerdict.isPrivate("172.20.1.1"))
        assertFalse(HostVerdict.isPrivate("172.32.1.1"))
    }

    @Test
    fun `the five kinds`() {
        assertEquals(GameKind.DIRECT, KindClassifier.classify(listOf(
            host(TrafficClass.LOGIN, Obstacle.OK), host(TrafficClass.CDN, Obstacle.OK))))
        // FC Mobile on an Iranian line: sign-in refused on country.
        assertEquals(GameKind.SANCTIONED, KindClassifier.classify(listOf(
            host(TrafficClass.LOGIN, Obstacle.GEO_BLOCKED), host(TrafficClass.API, Obstacle.OK))))
        // Every sign-in / service host filtered.
        assertEquals(GameKind.FILTERED, KindClassifier.classify(listOf(
            host(TrafficClass.LOGIN, Obstacle.SNI_BLOCKED), host(TrafficClass.API, Obstacle.IP_BLOCKED))))
        // The game plays; Google sign-in or the store does not.
        assertEquals(GameKind.PARTIAL, KindClassifier.classify(listOf(
            host(TrafficClass.LOGIN, Obstacle.OK), host(TrafficClass.SOCIAL, Obstacle.GEO_BLOCKED))))
        assertEquals(GameKind.BOTH, KindClassifier.classify(listOf(
            host(TrafficClass.LOGIN, Obstacle.GEO_BLOCKED), host(TrafficClass.API, Obstacle.SNI_BLOCKED))))
    }

    @Test
    fun `one dead name among working ones does not make a filtered game`() {
        val k = KindClassifier.classify(listOf(
            host(TrafficClass.LOGIN, Obstacle.OK), host(TrafficClass.LOGIN, Obstacle.IP_BLOCKED, "dead.example")))
        assertEquals(GameKind.PARTIAL, k)
        assertNull(KindClassifier.classify(listOf(host(TrafficClass.LOGIN, Obstacle.UNKNOWN))))
        assertNull(KindClassifier.classify(emptyList()))
    }

    @Test
    fun `each part shows its worst known obstacle`() {
        val per = KindClassifier.perClass(listOf(
            host(TrafficClass.LOGIN, Obstacle.OK, "a"), host(TrafficClass.LOGIN, Obstacle.GEO_BLOCKED, "b"),
            host(TrafficClass.CDN, Obstacle.UNKNOWN, "c"),
        ))
        assertEquals(mapOf(TrafficClass.LOGIN to Obstacle.GEO_BLOCKED), per)
    }
}
