package com.mlmvpn.scanner.engines.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderPanelTest {

    // The shape of SpiderPanel's worker head, not its code (which carries no licence).
    private val template = """
        // SpiderPanel — managed Cloudflare Pages Worker
        // The panel injects __PANEL_TOKEN__, __PANEL_DOMAIN__ and __WORKER_DOMAIN__
        const PANEL_TOKEN = __PANEL_TOKEN__;
        const PANEL_DOMAIN = __PANEL_DOMAIN__;
        const WORKER_DOMAIN = __WORKER_DOMAIN__;
        function f(env) { return env.SPIDER_KV; } // "/panel/config"
    """.trimIndent()

    private val values = mapOf(
        "PANEL_TOKEN" to "\"abc123\"",
        "PANEL_DOMAIN" to "\"mlmvpn-app\"",
        "WORKER_DOMAIN" to "\"x-spd.sub.workers.dev\"",
    )

    @Test fun injectThenReadBack() {
        val deployed = SpiderPanel.inject(template, values)
        assertTrue(deployed.contains("const PANEL_TOKEN = \"abc123\";"))
        assertEquals(values, SpiderPanel.injectedValues(deployed))
    }

    @Test fun normalizedDeployEqualsTemplate() {
        // The store compares these bytes: a deployed copy of version N must equal version N.
        assertEquals(template, SpiderPanel.normalize(SpiderPanel.inject(template, values)))
    }

    @Test fun newVersionCarriesTheOldValues() {
        val deployed = SpiderPanel.inject(template, values)
        val next = template.replace("return env.SPIDER_KV;", "return env.SPIDER_KV || null;")
        val updated = SpiderPanel.inject(next, SpiderPanel.injectedValues(deployed)!!)
        assertEquals(values, SpiderPanel.injectedValues(updated))
        assertTrue(updated.contains("|| null"))
    }

    @Test fun templateHasNoValues() {
        assertNull(SpiderPanel.injectedValues(template))
    }

    @Test fun recognisesItself() {
        assertTrue(SpiderPanel.looksLikeSpider(template))
    }
}
