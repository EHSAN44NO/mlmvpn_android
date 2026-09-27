package com.mlmvpn.scanner.engines.game.booster

import com.mlmvpn.core.warp.TweetNaclFast
import com.mlmvpn.scanner.engines.game.booster.brain.Brain
import com.mlmvpn.scanner.engines.game.booster.brain.CrowdRows
import com.mlmvpn.scanner.engines.game.booster.brain.SessionOutcome
import com.mlmvpn.scanner.engines.game.booster.brain.SessionRecord
import com.mlmvpn.scanner.engines.game.booster.crowd.CrowdAdvice
import com.mlmvpn.scanner.engines.game.booster.crowd.CrowdSnapshot
import com.mlmvpn.scanner.engines.game.booster.model.RouteKind
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrowdTest {

    // ── the snapshot ────────────────────────────────────────────────────────────────────────

    /** A slice signed by the deployed service (2026-09-25), with the key the app pins. */
    private val liveBody = """{"v":1,"k":"all","at":1790293249149,"rate":0.3,"flags":{},"kill":{},"g":{}}"""
    private val liveSig = "ikY1knEfhJ4WHuvY4/ofhpDZyZ16YTpSBumox2QxYqoqdD0BfmWVhv0cbQKUhxhqtFZe0zfd1EQoR/37NNPzAQ=="
    private val pinned = CrowdSnapshot.decodeBase64("P/1bzrQly13Bcb2eDa4pGERBbwQGc5UjBt/qeIIy9LI=")!!

    @Test
    fun `a slice signed by the real service verifies with the pinned key`() {
        val s = CrowdSnapshot.open(liveBody, liveSig, pinned)
        assertNotNull(s)
        assertEquals("all", s!!.key)
        assertEquals(0.3, s.rate!!, 0.0)
    }

    @Test
    fun `a changed body or another key is refused`() {
        assertNull(CrowdSnapshot.open(liveBody.replace("0.3", "1.0"), liveSig, pinned))
        val other = TweetNaclFast.Signature.keyPair().publicKey
        assertNull(CrowdSnapshot.open(liveBody, liveSig, other))
        assertNull(CrowdSnapshot.open(liveBody, "not base64 !!", pinned))
    }

    @Test
    fun `base64 decodes like the JVM's own`() {
        val rnd = java.util.Random(7)
        repeat(50) { n ->
            val bytes = ByteArray(n).also { rnd.nextBytes(it) }
            val text = java.util.Base64.getEncoder().encodeToString(bytes)
            assertArrayEquals(bytes, CrowdSnapshot.decodeBase64(text))
        }
    }

    private fun signed(body: String): Triple<String, String, ByteArray> {
        val kp = TweetNaclFast.Signature.keyPair()
        val sig = TweetNaclFast.Signature(null, kp.secretKey).detached(body.toByteArray())
        return Triple(body, java.util.Base64.getEncoder().encodeToString(sig), kp.publicKey)
    }

    private val mineBody = """
        {"v":1,"k":"as44244","at":1,"asn":44244,"g":{"fifamobile":{"n":300,
          "p":{"DD":[250,180,20],"D":[50,10,30]},"w":[40,1],"r":{"EU":[200,98],"AS":[20,160],"ME":[5,70]},
          "k":[10,250,0,40,0],"s":{"shecan":[160,10],"403":[40,40],"radar":[1,60]}}}}
    """.trimIndent()

    @Test
    fun `an operator's slice parses into what the booster decides with`() {
        val (b, s, k) = signed(mineBody)
        val mine = CrowdSnapshot.open(b, s, k)!!
        val all = CrowdSnapshot.parse("""{"v":1,"k":"all","at":1,"rate":0.2,"flags":{"warp":false},
            "kill":{"sdns":["begzar"],"asn":{"44244":{"sdns":["403"]}}},"g":{}}""")
        val a = CrowdAdvice(all, mine, 44244)
        assertEquals("shecan", a.bestSdns("fifamobile"))
        assertEquals(setOf("radar"), a.uselessSdns("fifamobile"))
        assertEquals(setOf("begzar", "403"), a.killedSdns())
        assertTrue(a.warpRarelyWins("fifamobile"))           // 1 win in 40
        assertEquals("EU" to 98, a.bestRegion("fifamobile"))  // ME has too few sessions
        assertFalse(a.enabled("warp"))
        assertTrue(a.enabled("sdns"))                          // unset means on
        assertEquals(0.2, a.reportRate(), 0.0)
        val dd = a.planStats("fifamobile", "DD")!!
        assertEquals(90, dd.ok * 100 / (dd.ok + dd.bad))
        assertNull(a.planStats("codm", "D"))
    }

    @Test
    fun `with no snapshot everything is on and nothing is claimed`() {
        val a = CrowdAdvice.NONE
        assertTrue(a.enabled("warp"))
        assertTrue(a.killedSdns().isEmpty())
        assertNull(a.bestSdns("codm"))
        assertFalse(a.warpRarelyWins("codm"))
        assertEquals(CrowdAdvice.DEFAULT_RATE, a.reportRate(), 0.0)
    }

    // ── this phone's memory ─────────────────────────────────────────────────────────────────

    @Test
    fun `evidence builds up, and fades`() {
        var t = 1_000_000_000L
        val b = Brain { t }
        assertNull(b.planRate("net", "codm", "D"))
        b.record("net", "codm", "D", 1, 1.0)
        assertNull("one session is not evidence", b.planRate("net", "codm", "D"))
        b.record("net", "codm", "D", 1, 1.0)
        val fresh = b.planRate("net", "codm", "D")!!
        assertEquals(0.75, fresh, 0.001)                     // (2+1)/(2+2)
        b.record("net", "codm", "D", -1, 3.0)                // an explicit "no" weighs three
        assertTrue(b.planRate("net", "codm", "D")!! < 0.5)
        t += 4 * Brain.HALF_LIFE_MS                           // two months later: almost nothing left
        assertNull(b.planRate("net", "codm", "D"))
    }

    @Test
    fun `the service that opened sign-ins here is preferred`() {
        val b = Brain { 1L }
        repeat(3) { b.recordSdns("net", "shecan", fixed = true) }
        repeat(3) { b.recordSdns("net", "radar", fixed = false) }
        assertEquals("shecan", b.bestSdns("net"))
        assertNull(b.bestSdns("other-net"))
    }

    @Test
    fun `memory survives a round trip`() {
        val b = Brain { 5L }
        b.record("n", "pubg", "W3", 1, 2.0)
        val c = Brain { 5L }
        c.loadJson(JSONObject(b.toJson().toString()))
        assertEquals(b.planRate("n", "pubg", "W3"), c.planRate("n", "pubg", "W3"))
        assertEquals("DD", Brain.planCode(RouteKind.DIRECT_DNS))
    }

    // ── how a session went ──────────────────────────────────────────────────────────────────

    private fun facts(min: Double, stopped: Boolean = true, goodMin: Double = min, badMin: Double = 0.0, lost: Boolean = false) =
        SessionOutcome.Facts((min * 60_000).toLong(), stopped, (goodMin * 60_000).toLong(), (badMin * 60_000).toLong(), lost)

    @Test
    fun `what a session says without a word from the player`() {
        assertEquals(1, SessionOutcome.implicit(facts(25.0)).outcome)                    // long and clean
        assertEquals(-1, SessionOutcome.implicit(facts(25.0, goodMin = 5.0, badMin = 20.0)).outcome) // mostly bad
        assertEquals(-1, SessionOutcome.implicit(facts(8.0, lost = true)).outcome)       // the route vanished
        val quick = SessionOutcome.implicit(facts(1.0))
        assertEquals(-1, quick.outcome)                                                  // stopped at once
        assertEquals(0.5, quick.weight, 0.0)                                             // ...weakly
        assertEquals(0, SessionOutcome.implicit(facts(6.0)).outcome)                     // too short to tell
    }

    // ── what is sent ────────────────────────────────────────────────────────────────────────

    private val record = SessionRecord("fifamobile", true, "EU", "DD", 2, true, false, 96,
        mapOf("shecan" to 1, "radar" to -1), "net", 1L, true)

    @Test
    fun `a session row carries exactly the fields the service accepts, and nothing that identifies anyone`() {
        val row = CrowdRows.session(record, 1)
        assertEquals(setOf("g", "r", "p", "o", "wm", "ww", "k", "p50", "s"), row.keys().asSequence().toSet())
        assertEquals(1, row.getJSONObject("s").getInt("shecan"))
        val fb = CrowdRows.feedback(record, -1)
        assertEquals(1, fb.getInt("f"))
        assertFalse(fb.has("s"))
        assertFalse(fb.has("p50"))
    }

    @Test
    fun `a record survives the trip through the pending-feedback store`() {
        assertEquals(record, SessionRecord.fromJson(JSONObject(record.toJson().toString())))
    }
}
