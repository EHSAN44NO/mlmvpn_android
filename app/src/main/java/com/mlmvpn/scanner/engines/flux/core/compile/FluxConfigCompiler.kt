package com.mlmvpn.scanner.engines.flux.core.compile

import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two Xray configs FLUX runs: the tunnel (handed to MyVpnService) and the probe core (one SOCKS
 * listener per candidate, for the race).
 *
 * The tunnel carries the winner AND its standbys behind one balancer, watched by Xray's burst
 * observatory: when the primary dies, Xray moves new connections to a standby on its own, in well
 * under a second, without the TUN or the VPN service restarting. FLUX's own checks then re-plan in
 * the background.
 *
 * Leak rules, each deliberate:
 *  - every lookup is answered by FakeDNS inside the tunnel, and the real resolver behind it is a
 *    DoH server reached THROUGH the tunnel: no plaintext port-53 query leaves the phone;
 *  - candidates dial literal addresses only, so the tunnel never needs a resolver to come up;
 *  - nothing is routed direct except LAN ranges -- not the fake-DNS pool, not "Iranian" sites;
 *  - in IPv4 mode no IPv6 exists anywhere (no AAAA, no v6 pool, and MyVpnService adds no v6
 *    address, which makes Android block the family instead of leaking it).
 */
object FluxConfigCompiler {

    /** Read by MyVpnService: a FLUX tunnel (no app-side Google fix; FLUX owns its routing). */
    const val REMARKS = "mlm-flux"
    /** The IPv4-only variant: MyVpnService brings the TUN up without IPv6 at all. */
    const val REMARKS_V4 = "mlm-flux-v4"
    const val BALANCER = "flux"
    const val TAG_PREFIX = "flux-"
    const val DNS_TAG = "flux-dns"
    const val MAX_ROUTES = 3

    fun isFlux(remarks: String?): Boolean = remarks == REMARKS || remarks == REMARKS_V4

    private val LAN_V4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "100.64.0.0/10")
    private val LAN_V6 = listOf("fe80::/10", "fd00::/8", "::1/128")
    /** Iran's filtering answers hijacked connections from here (the block page): fail fast. */
    private val BLOCK_PAGE = listOf("10.10.34.0/24", "2001:4188:2:600::/64")

    /**
     * @param routes primary first, then standbys (at most [MAX_ROUTES] are used).
     * @param quicSink an outbound that answers QUIC with a refusal so apps fall back to TCP at
     *   once (the app's QuicRefuser), or null to drop QUIC silently. Used only when the primary
     *   cannot carry UDP.
     * @param mobile a slower observatory on mobile data.
     * @param safe the primary alone, with no balancer and no observatory: the fallback when the
     *   full config does not start on this core.
     */
    fun tunnel(routes: List<FluxCandidate>, mode: IpMode, localPort: Int, quicSink: JSONObject? = null, mobile: Boolean = false, safe: Boolean = false): String {
        require(routes.isNotEmpty()) { "no route" }
        val used = routes.distinctBy { it.id }.take(if (safe) 1 else MAX_ROUTES)
        // One route needs no balancer. A leastLoad balancer without the burst observatory is a
        // config the core refuses outright ("not all dependencies are resolved"), so the two only
        // ever appear together, and only with standbys to choose between.
        val balanced = used.size > 1
        fun toRoutes(r: JSONObject) = if (balanced) r.put("balancerTag", BALANCER) else r.put("outboundTag", "${TAG_PREFIX}0")
        val v6 = mode != IpMode.V4
        val json = JSONObject()
        json.put("remarks", if (v6) REMARKS else REMARKS_V4)
        json.put("log", JSONObject().put("loglevel", "warning"))

        json.put("inbounds", JSONArray()
            .put(JSONObject().put("tag", "socks").put("port", localPort).put("listen", "127.0.0.1").put("protocol", "mixed")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic").put("fakedns"))))
            // localPort+10000: the app-wide convention for a status probe through the tunnel. FLUX's
            // own "really connected" check goes through it too.
            .put(JSONObject().put("tag", "http").put("port", localPort + 10000).put("listen", "127.0.0.1").put("protocol", "http")))

        val outbounds = JSONArray()
        used.forEachIndexed { i, c -> outbounds.put(FluxOutbounds.outbound(c, "$TAG_PREFIX$i")) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "blocked").put("protocol", "blackhole"))
        val primaryUdp = used.first().node.carriesUdp
        val quicTag = if (!primaryUdp && quicSink != null) {
            outbounds.put(quicSink)
            quicSink.optString("tag")
        } else "blocked"
        json.put("outbounds", outbounds)

        json.put("dns", JSONObject()
            .put("tag", DNS_TAG)
            .put("servers", JSONArray()
                .put("fakedns")
                // Google first: a Cloudflare-Worker exit cannot reach Cloudflare's own resolver.
                .put(JSONObject().put("address", "https://8.8.8.8/dns-query"))
                .put(JSONObject().put("address", "https://1.1.1.1/dns-query")))
            .put("queryStrategy", if (v6) "UseIP" else "UseIPv4"))
        json.put("fakedns", JSONArray().apply {
            put(JSONObject().put("ipPool", "198.18.0.0/15").put("poolSize", 65535))
            if (v6) put(JSONObject().put("ipPool", "fc00::/18").put("poolSize", 65535))
        })

        val rules = JSONArray()
        rules.put(rule().put("port", "53").put("outboundTag", "dns-out"))
        rules.put(toRoutes(rule().put("inboundTag", JSONArray().put(DNS_TAG))))
        rules.put(rule().put("ip", JSONArray(BLOCK_PAGE)).put("outboundTag", "blocked"))
        rules.put(rule().put("ip", JSONArray(LAN_V4 + LAN_V6)).put("outboundTag", "direct"))
        if (!primaryUdp) {
            rules.put(rule().put("network", "udp").put("protocol", JSONArray().put("quic")).put("outboundTag", quicTag))
            rules.put(rule().put("network", "udp").put("port", "443").put("outboundTag", quicTag))
        }
        rules.put(toRoutes(rule().put("network", "tcp,udp")))

        if (!balanced) {
            json.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            return json.toString()
        }
        val costs = JSONArray()
        // The primary is preferred whenever it is alive; standbys cost more and take over only
        // when the observatory sees the primary fail.
        used.indices.forEach { i -> costs.put(JSONObject().put("regexp", false).put("match", "$TAG_PREFIX$i").put("value", 1.0 + i * 9.0)) }
        json.put("routing", JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", rules)
            .put("balancers", JSONArray().put(JSONObject()
                .put("tag", BALANCER)
                .put("selector", JSONArray(used.indices.map { "$TAG_PREFIX$it" }))
                .put("fallbackTag", "${TAG_PREFIX}0")
                .put("strategy", JSONObject().put("type", "leastLoad").put("settings", JSONObject()
                    .put("expected", 1)
                    .put("maxRTT", "3s")
                    .put("tolerance", 0.5)
                    .put("baselines", JSONArray().put("1s"))
                    .put("costs", costs))))))

        run {
            json.put("burstObservatory", JSONObject()
                .put("subjectSelector", JSONArray(used.indices.map { "$TAG_PREFIX$it" }))
                .put("pingConfig", JSONObject()
                    .put("destination", "https://www.gstatic.com/generate_204")
                    .put("interval", if (mobile) "2m" else "1m")
                    .put("sampling", 2)
                    .put("timeout", "5s")))
        }
        return json.toString()
    }

    /**
     * The probe core: one SOCKS inbound per candidate, wired 1:1 to that candidate's outbound by
     * tag. The map is explicit (candidate id -> port), never an index into a list, so a probe
     * can never measure a different candidate than the one it reports on.
     */
    fun probe(ports: Map<FluxCandidate, Int>): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        ports.entries.forEachIndexed { i, (c, port) ->
            val inTag = "pin-$i"; val outTag = "pout-$i"
            inbounds.put(JSONObject().put("tag", inTag).put("port", port).put("listen", "127.0.0.1").put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            outbounds.put(FluxOutbounds.outbound(c, outTag))
            rules.put(rule().put("inboundTag", JSONArray().put(inTag)).put("outboundTag", outTag))
        }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        return JSONObject()
            .put("remarks", "mlm-flux-probe")
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }

    private fun rule() = JSONObject().put("type", "field")
}
