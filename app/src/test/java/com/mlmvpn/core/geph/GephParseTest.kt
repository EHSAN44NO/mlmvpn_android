package com.mlmvpn.core.geph

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's and the broker's answers, in the shapes they really have (client_control.rs, lib.rs). */
class GephParseTest {

    @Test
    fun connInfoConnected() {
        val info = GephConnInfo.parse(JSONObject("""
            {"state":"Connected","sessions":[
              {"protocol":"sosistab3","bridge":"1.2.3.4:5000",
               "exit":{"c2e_listen":"5.6.7.8:1","b2e_listen":"5.6.7.8:2","country":"PL","city":"Warsaw","load":0.31,"expiry":1}},
              {"protocol":"sosistab3","bridge":null,
               "exit":{"c2e_listen":"5.6.7.8:1","b2e_listen":"5.6.7.8:2","country":"PL","city":"Warsaw","load":0.31,"expiry":1}}
            ]}""".trimIndent()))
        assertTrue(info.connected)
        assertEquals(2, info.sessions.size)
        assertEquals("PL", info.sessions[0].country)
        assertEquals("Warsaw", info.sessions[0].city)
        assertEquals("1.2.3.4:5000", info.sessions[0].bridge)
        assertNull(info.sessions[1].bridge)
        assertEquals("5.6.7.8", info.sessions[0].exitAddress)
    }

    @Test
    fun connInfoConnectingIsNotConnected() {
        assertFalse(GephConnInfo.parse(JSONObject("""{"state":"Connecting"}""")).connected)
        assertFalse(GephConnInfo.parse(null).connected)
    }

    @Test
    fun netStatusExitsAndLevels() {
        val exits = GephExits.parse(JSONObject("""
            {"exits":{
              "k1":["pub1",{"c2e_listen":"1.1.1.1:1","b2e_listen":"1.1.1.1:2","country":"PL","city":"Warsaw","load":0.2,"expiry":1},
                    {"allowed_levels":["Free","Plus"],"category":"core"}],
              "k2":["pub2",{"c2e_listen":"2.2.2.2:1","b2e_listen":"2.2.2.2:2","country":"SE","city":"Stockholm","load":0.5,"expiry":1},
                    {"allowed_levels":["Plus"],"category":"streaming"}]
            }}""".trimIndent()))
        assertEquals(2, exits.size)
        val pl = exits.first { it.country == "PL" }
        val se = exits.first { it.country == "SE" }
        assertTrue(pl.allows(GephAccount.Level.FREE))
        assertFalse(se.allows(GephAccount.Level.FREE))
        assertTrue(se.allows(GephAccount.Level.PLUS))
        assertTrue(se.allows(GephAccount.Level.BASIC))
        assertTrue(se.streaming)
        // Countries this account can use come first.
        val countries = GephExits.countries(exits, GephAccount.Level.FREE)
        assertEquals("PL", countries.first().code)
    }

    @Test
    fun accountLevels() {
        val future = System.currentTimeMillis() / 1000 + 86_400
        val free = GephAccount.Info.parse(JSONObject("""{"user_id":7,"plus_expires_unix":null}"""))
        val plus = GephAccount.Info.parse(JSONObject("""{"user_id":7,"plus_expires_unix":$future,"recurring":true}"""))
        val basic = GephAccount.Info.parse(JSONObject(
            """{"user_id":7,"plus_expires_unix":$future,"bw_consumption":{"mb_used":120,"mb_limit":5000,"renew_unix":$future}}"""))
        val expired = GephAccount.Info.parse(JSONObject("""{"user_id":7,"plus_expires_unix":1000}"""))
        assertEquals(GephAccount.Level.FREE, free.level)
        assertEquals(GephAccount.Level.PLUS, plus.level)
        assertTrue(plus.recurring)
        assertEquals(GephAccount.Level.BASIC, basic.level)
        assertEquals(5000, basic.bwLimitMb)
        assertEquals(GephAccount.Level.FREE, expired.level)
        // Survives the cache round trip.
        assertEquals(basic.copy(fetchedAt = 0), GephAccount.Info.fromCache(basic.toJson()).copy(fetchedAt = 0))
    }

    @Test
    fun accountCodesArePastedAnyHow() {
        assertEquals("123456789012345678901234", GephAccount.normalizeSecret("1234 5678 9012 3456 7890 1234"))
        assertEquals("123456789012345678901234", GephAccount.normalizeSecret("1234-5678-9012-3456-7890-1234"))
        assertEquals("123456789012345678901234", GephAccount.normalizeSecret("۱۲۳۴۵۶۷۸۹۰۱۲۳۴۵۶۷۸۹۰۱۲۳۴"))
        assertNull(GephAccount.normalizeSecret("12345"))
        assertEquals("1234 5678 9012", GephAccount.formatSecret("123456789012"))
    }

    @Test
    fun forwardsAreValidated() {
        assertTrue(GephSettings.validForward("127.0.0.1:2222", "example.com:22"))
        assertTrue(GephSettings.validForward("0.0.0.0:8080", "[2606:4700::1]:443"))
        assertFalse(GephSettings.validForward("127.0.0.1", "example.com:22"))
        assertFalse(GephSettings.validForward("127.0.0.1:99999", "example.com:22"))
        assertFalse(GephSettings.validForward("127.0.0.1:2222", "example.com"))
    }

    @Test
    fun resultsUnwrapEitherShape() {
        assertEquals(5, GephControl.unwrapOk(JSONObject("""{"Ok":5}""")))
        assertEquals("x", GephControl.unwrapOk("x"))
    }
}
