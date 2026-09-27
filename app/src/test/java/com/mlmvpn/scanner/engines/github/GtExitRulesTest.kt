package com.mlmvpn.scanner.engines.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * «سایت‌ها و برنامه‌ها از کشور دیگر»: what saving a rule does to the others, and the STUN answer
 * the UDP row is built from. Both are pure, so they are pinned here rather than on a phone.
 */
class GtExitRulesTest {
    private val kr = GtExitRule("KR", "", listOf("instagram.com"), listOf("org.telegram.messenger"))
    private val de = GtExitRule("DE", "", listOf("x.com", "reddit.com"))

    @Test
    fun aNewRuleForANewCountryGoesLast() {
        val p = GtExitPrefs(rules = listOf(kr)).withRule(de)
        assertEquals(listOf("KR", "DE"), p.rules.map { it.country })
    }

    @Test
    fun aSecondRuleForTheSameCountryIsFoldedIntoTheFirst() {
        val p = GtExitPrefs(rules = listOf(kr, de)).withRule(GtExitRule("KR", "", listOf("threads.net"), listOf("com.whatsapp")))
        assertEquals(2, p.rules.size)
        assertEquals(listOf("instagram.com", "threads.net"), p.rules[0].domains)
        assertEquals(listOf("org.telegram.messenger", "com.whatsapp"), p.rules[0].apps)
    }

    @Test
    fun aSiteOrAppLivesInOneRuleOnly() {
        // x.com moves from Germany to Japan; Telegram moves from Korea to Japan.
        val p = GtExitPrefs(rules = listOf(kr, de)).withRule(GtExitRule("JP", "", listOf("x.com"), listOf("org.telegram.messenger")))
        assertEquals(listOf("KR", "DE", "JP"), p.rules.map { it.country })
        assertEquals(listOf("reddit.com"), p.rules[1].domains)
        assertTrue(p.rules[0].apps.isEmpty())
        assertEquals(listOf("instagram.com"), p.rules[0].domains)
    }

    @Test
    fun aRuleLeftWithNothingIsDropped() {
        val p = GtExitPrefs(rules = listOf(kr, de)).withRule(GtExitRule("JP", "", listOf("instagram.com"), listOf("org.telegram.messenger")))
        assertEquals(listOf("DE", "JP"), p.rules.map { it.country })
    }

    @Test
    fun anEditKeepsItsPlaceAndCanChangeCountry() {
        val jp = GtExitRule("JP", "", listOf("pixiv.net"))
        val p = GtExitPrefs(rules = listOf(kr, de, jp)).withRule(GtExitRule("US", "psiphon", listOf("reddit.com")), replacing = 1)
        assertEquals(listOf("KR", "US", "JP"), p.rules.map { it.country })
        assertEquals("psiphon", p.rules[1].provider)
        assertEquals(listOf("reddit.com"), p.rules[1].domains)
    }

    @Test
    fun savingAnEmptyRuleDeletesIt() {
        val p = GtExitPrefs(rules = listOf(kr, de)).withRule(GtExitRule("DE", ""), replacing = 1)
        assertEquals(listOf("KR"), p.rules.map { it.country })
    }

    @Test
    fun neverMoreThanFiveCountries() {
        val five = listOf("KR", "DE", "JP", "US", "NL").map { GtExitRule(it, "", listOf("${it.lowercase()}.example.com")) }
        val p = GtExitPrefs(rules = five).withRule(GtExitRule("FR", "", listOf("fr.example.com")))
        assertEquals(GtExitPrefs.MAX_RULES, p.rules.size)
    }

    @Test
    fun appsSurviveTheRoundTripAndBadEntriesDoNot() {
        val p = GtExitPrefs("JP", "vpngate", listOf(kr, de))
        val back = GtExitPrefs.from(p.toJson())
        assertEquals(p, back)
        val dirty = GtExitPrefs.from(org.json.JSONObject(
            """{"country":"jp","rules":[{"country":"KR","domains":[],"apps":["not a package","com.whatsapp"]},{"country":"XX1","domains":["a.com"]}]}"""))
        assertEquals("JP", dirty.country)
        assertEquals(1, dirty.rules.size)
        assertEquals(listOf("com.whatsapp"), dirty.rules[0].apps)
    }

    @Test
    fun theDispatchInputNamesEveryCountryOnce() {
        val p = GtExitPrefs("JP", "", listOf(kr, de, GtExitRule("JP", "", listOf("a.com")), GtExitRule("US", "psiphon", listOf("b.com"))))
        assertEquals("JP,KR,DE,US:psiphon", p.dispatchInput())
    }

    // ── the STUN answer (RFC 8489) ──────────────────────────────────────────────────────

    private fun stunAnswer(tid: ByteArray, attrType: Int, ip: ByteArray, port: Int, xor: Boolean): ByteArray {
        val cookie = 0x2112A442
        val a = ip.copyOf()
        var p = port
        if (xor) {
            val magic = ByteBuffer.allocate(4).putInt(cookie).array()
            for (i in a.indices) a[i] = (a[i].toInt() xor magic[i].toInt()).toByte()
            p = port xor (cookie ushr 16)
        }
        val attr = ByteBuffer.allocate(12).putShort(attrType.toShort()).putShort(8).put(0).put(1).putShort(p.toShort()).put(a).array()
        // An unrelated attribute first (SOFTWARE, 3 bytes + padding), as real servers send.
        val soft = ByteBuffer.allocate(8).putShort(0x8022.toShort()).putShort(3).put("xr1".toByteArray()).put(0).array()
        return ByteBuffer.allocate(20 + soft.size + attr.size).putShort(0x0101).putShort((soft.size + attr.size).toShort())
            .putInt(cookie).put(tid).put(soft).put(attr).array()
    }

    @Test
    fun readsTheXorMappedAddress() {
        val tid = ByteArray(12) { (it * 7).toByte() }
        val msg = stunAnswer(tid, 0x0020, byteArrayOf(39, 111, 138.toByte(), 50), 40123, xor = true)
        assertEquals("39.111.138.50", GtUdpProbe.parseStun(msg, 0, msg.size, tid))
    }

    @Test
    fun fallsBackToThePlainMappedAddress() {
        val tid = ByteArray(12) { 1 }
        val msg = stunAnswer(tid, 0x0001, byteArrayOf(48, 211.toByte(), 211.toByte(), 38), 5000, xor = false)
        assertEquals("48.211.211.38", GtUdpProbe.parseStun(msg, 0, msg.size, tid))
    }

    @Test
    fun refusesAnAnswerToAnotherRequest() {
        val msg = stunAnswer(ByteArray(12) { 2 }, 0x0020, byteArrayOf(1, 2, 3, 4), 1, xor = true)
        assertNull(GtUdpProbe.parseStun(msg, 0, msg.size, ByteArray(12) { 3 }))
    }
}
