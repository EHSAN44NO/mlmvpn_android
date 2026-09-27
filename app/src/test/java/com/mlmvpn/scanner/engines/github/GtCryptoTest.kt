package com.mlmvpn.scanner.engines.github

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The phone must open exactly what the runner seals, and sign passes exactly as the Worker checks
 * them. The fixture was sealed by the runner's own code (github-tunnel/runner/seal.mjs) with a key
 * pair from Node's X25519, so a difference anywhere — TweetNaCl's scalar multiplication, the HKDF,
 * the GCM layout, the pass's HMAC key — fails here instead of on a real cloud session.
 */
class GtCryptoTest {
    private val fx: JSONObject = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("gt/sealed-fixture.json")!!.bufferedReader().use { it.readText() }
    )
    private fun b64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    @Test
    fun publicKeyMatchesNodeX25519() {
        assertArrayEquals(b64u(fx.getString("pub")), GtCrypto.publicOf(b64u(fx.getString("priv"))))
    }

    @Test
    fun opensWhatTheRunnerSealed() {
        val sealed = fx.getJSONObject("sealed")
        val text = GtCrypto.openRaw(
            b64u(fx.getString("priv")), b64u(sealed.getString("epk")), b64u(sealed.getString("iv")),
            b64u(sealed.getString("ct")), fx.getString("sid"),
        )
        val got = JSONObject(text)
        val want = fx.getJSONObject("payload")
        assertEquals(want.getString("wsPath"), got.getString("wsPath"))
        assertEquals(want.getJSONObject("uuids").getString("direct"), got.getJSONObject("uuids").getString("direct"))
        assertEquals("brave-sea-owl.trycloudflare.com", got.getJSONArray("hosts").getString(0))
        // And the transport it describes passes the same checks the Windows client applies.
        val t = GtTransport.from(got)
        assertEquals(listOf("brave-sea-owl.trycloudflare.com"), t.hosts)
        assertEquals(3, t.rev)
        assertEquals("US", t.runnerCountry)
    }

    @Test
    fun refusesAnotherSessionsFile() {
        val sealed = fx.getJSONObject("sealed")
        try {
            GtCrypto.openRaw(b64u(fx.getString("priv")), b64u(sealed.getString("epk")), b64u(sealed.getString("iv")),
                b64u(sealed.getString("ct")), "GM-someone-else")
            fail("a file sealed for another session must not open")
        } catch (e: Exception) {
            assertTrue(e !is AssertionError)
        }
    }

    @Test
    fun passSignatureMatchesTheWorker() {
        val want = b64u(fx.getString("passSig"))
        assertArrayEquals(want, GtCrypto.passSig(fx.getString("secret"), fx.getString("passText")))
    }

    @Test
    fun theRunnersOwnAddressIsReadOnlyAsAPlainAddress() {
        // What the phone compares «what sites see» against to catch an exit leaking (healLeak).
        val p = JSONObject(fx.getJSONObject("payload").toString())
        assertEquals("", GtTransport.from(p).runnerIp)
        p.getJSONObject("runner").put("ip", "20.169.100.246")
        assertEquals("20.169.100.246", GtTransport.from(p).runnerIp)
        p.getJSONObject("runner").put("ip", "2603:1030:b:3::152")
        assertEquals("2603:1030:b:3::152", GtTransport.from(p).runnerIp)
        p.getJSONObject("runner").put("ip", "evil.example.com")
        assertEquals("", GtTransport.from(p).runnerIp)
    }

    @Test
    fun transportRejectsForeignHostsAndPaths() {
        val p = JSONObject(fx.getJSONObject("payload").toString())
        p.put("hosts", org.json.JSONArray().put("evil.example.com"))
        try { GtTransport.from(p); fail("a host outside trycloudflare.com must be refused") } catch (_: IllegalArgumentException) {}
        val q = JSONObject(fx.getJSONObject("payload").toString()).put("wsPath", "/../../etc")
        try { GtTransport.from(q); fail("a path with anything but [A-Za-z0-9_-] must be refused") } catch (_: IllegalArgumentException) {}
    }
}
