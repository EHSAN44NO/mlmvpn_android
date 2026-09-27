package com.mlmvpn.core.geph

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * geph5-client's Config is `#[serde(deny_unknown_fields)]`: one key it does not know is a fatal
 * start-up error, not a warning. These pin the config this app writes to the struct's own fields
 * (libraries/geph5-misc-rpc/src/client_config.rs) and to the shapes the engine parses.
 */
class GephConfigTest {

    /** Every field of the engine's Config, as of geph5 3b7bc1a (the official 5.9.0 build's era). */
    private val upstreamFields = setOf(
        "socks5_listen", "http_proxy_listen", "pac_listen", "control_listen", "control_listen_unix",
        "control_listen_pipe", "exit_constraint", "allow_direct", "cache", "broker", "tunneled_broker",
        "broker_keys", "port_forward", "spoof_dns", "passthrough_china", "allow_lan", "dry_run",
        "credentials", "sess_metadata", "task_limit",
    )

    private fun run(
        exit: GephExitChoice = GephExitChoice.AUTO,
        cred: GephCredential? = GephCredential.Secret("123456789012345678901234"),
    ) = GephRun(
        credential = cred,
        exit = exit,
        dryRun = false,
        controlSocket = "/data/x/ctl.sock",
        cacheFile = "/data/x/cache/db-abc",
        forwards = listOf(GephForward("127.0.0.1:4000", "www.gstatic.com:443")),
    )

    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()

    @Test
    fun onlyKeysTheEngineKnows() {
        val cfg = GephConfig.build(run())
        assertTrue(upstreamFields.containsAll(keys(cfg)))
        assertEquals(GephConfig.KEYS.toSet(), keys(cfg))
    }

    @Test
    fun theControlPortIsNeverTcp() {
        val cfg = GephConfig.build(run())
        assertTrue(cfg.isNull("control_listen"))
        assertEquals("/data/x/ctl.sock", cfg.getString("control_listen_unix"))
    }

    @Test
    fun exitConstraintShapes() {
        assertEquals("auto", GephConfig.exitConstraint(GephExitChoice.AUTO))
        assertEquals(
            "PL",
            (GephConfig.exitConstraint(GephExitChoice("pl", null)) as JSONObject).getString("country"),
        )
        val cc = (GephConfig.exitConstraint(GephExitChoice("pl", "Warsaw")) as JSONObject).getJSONArray("country_city")
        assertEquals("PL", cc.getString(0))
        assertEquals("Warsaw", cc.getString(1))
    }

    @Test
    fun credentialShapes() {
        assertEquals("1234", GephCredential.Secret("1234").toJson().getString("secret"))
        val legacy = GephCredential.Legacy("u", "p").toJson().getJSONObject("legacy_username_password")
        assertEquals("u", legacy.getString("username"))
        assertEquals("p", legacy.getString("password"))
        // The query engine has no account; the network's dummy is an empty secret.
        assertEquals("", GephConfig.build(run(cred = null)).getJSONObject("credentials").getString("secret"))
    }

    @Test
    fun eachAccountGetsItsOwnCache() {
        assertNotEquals(GephCredential.Secret("1").tag(), GephCredential.Secret("2").tag())
        assertEquals(32, GephCredential.Secret("1").tag().length)
    }

    @Test
    fun theFilterIsWhatTheExitReads() {
        val cfg = GephConfig.build(run().copy(blockAds = true, blockAdult = false))
        val filter = cfg.getJSONObject("sess_metadata").getJSONObject("filter")
        assertTrue(filter.getBoolean("ads"))
        assertFalse(filter.getBoolean("nsfw"))
    }

    @Test
    fun theBrokerRaceIsUpstreamsFourPaths() {
        val race = GephConfig.build(run()).getJSONObject("broker").getJSONObject("priority_race")
        assertEquals(setOf("0", "500", "1500", "2500"), keys(race))
        assertTrue(race.getJSONObject("2500").has("aws_lambda"))
        assertEquals(
            "https://www.cdn77.com/",
            race.getJSONObject("0").getJSONObject("fronted").getString("front"),
        )
        val keys = GephConfig.build(run()).getJSONObject("broker_keys")
        assertEquals(setOf("master", "mizaru_free", "mizaru_plus", "mizaru_bw"), keys(keys))
    }

    @Test
    fun forwardsAreListenAndConnect() {
        val pf = GephConfig.build(run()).getJSONArray("port_forward") as JSONArray
        assertEquals("127.0.0.1:4000", pf.getJSONObject(0).getString("listen"))
        assertEquals("www.gstatic.com:443", pf.getJSONObject(0).getString("connect"))
    }

    @Test
    fun aTaskLimitIsNeverSet() {
        // It cancels the OLDEST connections when exceeded (taskpool.rs): live downloads cut off.
        assertTrue(GephConfig.build(run()).isNull("task_limit"))
    }
}
