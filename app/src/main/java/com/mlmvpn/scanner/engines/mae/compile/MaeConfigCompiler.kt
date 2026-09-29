package com.mlmvpn.scanner.engines.mae.compile

import com.mlmvpn.scanner.engines.mae.model.DnsPath
import com.mlmvpn.scanner.engines.mae.model.ServiceDef
import com.mlmvpn.scanner.engines.mae.policy.Decision
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compiles MAE's per-service decisions into ONE Xray config, on top of the Serverless profile.
 *
 * The base is the Serverless config untouched: everything MAE does not route explicitly keeps
 * Serverless's exact path and speed. On top it adds:
 *  - every route's outbounds (stable tags);
 *  - one balancer `svc-<id>` per routed service, whose selector is the chosen outbound -- so a
 *    route change is `OverrideBalancerTarget(svc-<id>, tag)` at runtime and touches no other
 *    service (falls back to recompiling when the live API is not available);
 *  - rules sending the service's domains and IP ranges to its balancer, most specific domains
 *    first (gemini's `generativelanguage.googleapis.com` must win over google's `googleapis.com`);
 *  - FakeDNS for the services' domains when the base has a fakedns server, so a flow with no
 *    readable hostname (ECH, no SNI) is still recognised; IP ranges cover apps that never resolve
 *    a name at all (Telegram);
 *  - outbound traffic counters (stats + policy.system) for passive throughput learning;
 *  - optionally the gRPC API on a Unix socket, for live switching.
 *
 * Pure: org.json only, unit-tested on the JVM.
 */
object MaeConfigCompiler {
    const val REMARKS = "MAE"
    const val BLOCK_TAG = "block"
    const val API_TAG = "mae-api"

    data class ServiceRoute(
        val service: ServiceDef,
        val decision: Decision,
        /**
         * Send the service's UDP to block instead of the base. True for services that must exit
         * abroad on a TCP-only route: their QUIC falls back to TCP rather than leave directly.
         */
        val blockUdp: Boolean,
    )

    fun balancerTag(serviceId: String) = "svc-" + serviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    fun udpBalancerTag(serviceId: String) = balancerTag(serviceId) + "-u"

    /**
     * (TCP target, UDP target) outbound tags for a service's decision, or null when the chosen
     * provider is not in this config. UDP goes to block when the route cannot carry it or the
     * service must not leak it ([ServiceRoute.blockUdp]).
     */
    fun targetsFor(r: ServiceRoute, providers: List<RouteProvider>): Pair<String, String>? = when (val d = r.decision) {
        is Decision.Block -> BLOCK_TAG to BLOCK_TAG
        is Decision.Use -> {
            val p = providers.firstOrNull { it.id == d.routeId }
            if (p == null) null
            else p.tag(d.family) to (if (p.caps.udp && !r.blockUdp) p.tag(d.family) else BLOCK_TAG)
        }
    }

    private fun balancer(tag: String, target: String) = JSONObject().put("tag", tag)
        .put("selector", JSONArray().put(target)).put("strategy", JSONObject().put("type", "random"))

    fun compile(
        base: String,
        providers: List<RouteProvider>,
        routes: List<ServiceRoute>,
        apiSocketPath: String? = null,
        dns: DnsPath? = null,
        /**
         * Where the base's own bypass rules send unknown traffic. Null keeps the base (Serverless
         * fragment). On a network where fragment was measured dead, MAE passes the direct tag so
         * sites it knows nothing about still load rather than time out.
         */
        defaultVia: String? = null,
        /** Developer mode: Xray's info log and access log, one line per connection and route. */
        verbose: Boolean = false,
    ): String {
        val json = JSONObject(base)
        if (verbose) json.put("log", JSONObject().put("loglevel", "info").put("access", "").put("dnsLog", false))
        json.remove("version")
        json.put("remarks", REMARKS)

        val outbounds = json.optJSONArray("outbounds") ?: JSONArray().also { json.put("outbounds", it) }
        val have = (0 until outbounds.length()).map { outbounds.getJSONObject(it).optString("tag") }.toMutableSet()
        if (BLOCK_TAG !in have) {
            outbounds.put(JSONObject().put("tag", BLOCK_TAG).put("protocol", "blackhole")); have += BLOCK_TAG
        }
        for (p in providers) for (o in p.outbounds()) {
            if (have.add(o.getString("tag"))) outbounds.put(o)
        }

        enableStats(json)
        dns?.let { applyDns(json, it) }

        val routing = json.optJSONObject("routing") ?: JSONObject().also { json.put("routing", it) }
        val baseRules = routing.optJSONArray("rules") ?: JSONArray()
        val balancers = routing.optJSONArray("balancers") ?: JSONArray()
        val ours = JSONArray()

        // Each service's rules, emitted per domain depth so that the most specific domains of ALL
        // services come first: gemini's `generativelanguage.googleapis.com` before google's
        // `googleapis.com`, whatever order the services were picked in.
        data class Emit(val depth: Int, val json: JSONObject)
        val emits = mutableListOf<Emit>()
        for (r in routes) {
            val s = r.service
            val byDepth = s.domains.groupBy { depth(it) }
            fun both(net: String, bal: String) {
                byDepth.forEach { (dep, ds) -> emits += Emit(dep, rule(ds.map { "domain:$it" }, null, net, null, bal)) }
                if (s.ipRanges.isNotEmpty()) emits += Emit(-1, rule(null, s.ipRanges, net, null, bal))
            }
            // Every service gets the same shape whatever its decision: one TCP balancer and one
            // UDP balancer. A route change -- including to or from "blocked", and from a TCP-only
            // exit to one that carries UDP -- is then only a balancer target, switchable live.
            val (tcpTarget, udpTarget) = targetsFor(r, providers) ?: continue
            balancers.put(balancer(balancerTag(s.id), tcpTarget))
            balancers.put(balancer(udpBalancerTag(s.id), udpTarget))
            both("tcp", balancerTag(s.id))
            both("udp", udpBalancerTag(s.id))
        }
        // Stable sort: depth descending, emission order kept within a depth.
        emits.sortedByDescending { it.depth }.forEach { ours.put(it.json) }

        // Ours go after the base's DNS plumbing (DoH-through-fragment, port 53) and before the
        // rest of its rules, so they take precedence over Serverless's own domain lists.
        val merged = JSONArray()
        var i = 0
        while (i < baseRules.length() && isDnsPlumbing(baseRules.getJSONObject(i))) merged.put(baseRules.get(i++))
        for (k in 0 until ours.length()) merged.put(ours.get(k))
        while (i < baseRules.length()) {
            val r = baseRules.getJSONObject(i++)
            if (defaultVia != null && r.optString("outboundTag") in BASE_BYPASS_TAGS) r.put("outboundTag", defaultVia)
            merged.put(r)
        }
        routing.put("rules", merged)
        if (balancers.length() > 0) routing.put("balancers", balancers)

        addFakeDns(json, routes.filter { it.decision is Decision.Use }.flatMap { it.service.domains })

        if (apiSocketPath != null) addApi(json, apiSocketPath)
        return json.toString()
    }

    /**
     * The probe core's config: the same base DNS and outbounds, one local SOCKS inbound per
     * outbound tag, and nothing else in routing. Probing through it measures exactly the
     * transport the real config will use.
     */
    fun compileProbe(base: String, providers: List<RouteProvider>, socksPorts: Map<String, Int>, dns: DnsPath? = null): String {
        val json = JSONObject(compile(base, providers, emptyList(), dns = dns))
        json.put("inbounds", JSONArray().apply {
            socksPorts.forEach { (tag, port) ->
                put(JSONObject().put("tag", "probe-$tag").put("listen", "127.0.0.1").put("port", port)
                    .put("protocol", "socks").put("settings", JSONObject().put("udp", false)))
            }
        })
        val routing = json.getJSONObject("routing")
        val baseRules = routing.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
        var i = 0
        while (i < baseRules.length() && isDnsPlumbing(baseRules.getJSONObject(i))) rules.put(baseRules.get(i++))
        socksPorts.keys.forEach { tag ->
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("probe-$tag")).put("outboundTag", tag))
        }
        routing.put("rules", rules)
        routing.remove("balancers")
        json.remove("api")
        return json.toString()
    }

    /**
     * The gRPC API on a Unix socket inside the app's private directory. Never loopback TCP: the
     * API has no authentication, and on a port any app on the phone could add an outbound and
     * steer this user's traffic through it. A file socket under filesDir is reachable only by
     * this app's UID.
     */
    private fun addApi(json: JSONObject, socketPath: String) {
        json.put("api", JSONObject().put("tag", API_TAG)
            .put("services", JSONArray().put("RoutingService").put("HandlerService").put("StatsService")))
        val inbounds = json.optJSONArray("inbounds") ?: JSONArray().also { json.put("inbounds", it) }
        inbounds.put(JSONObject().put("tag", API_IN_TAG).put("listen", socketPath).put("port", 0)
            .put("protocol", "dokodemo-door").put("settings", JSONObject().put("address", "127.0.0.1")))
        val routing = json.getJSONObject("routing")
        val rules = routing.optJSONArray("rules") ?: JSONArray()
        val withApi = JSONArray().put(JSONObject().put("type", "field")
            .put("inboundTag", JSONArray().put(API_IN_TAG)).put("outboundTag", API_TAG))
        for (k in 0 until rules.length()) withApi.put(rules.get(k))
        routing.put("rules", withApi)
    }

    const val API_IN_TAG = "mae-api-in"

    /**
     * Points the base's DoH server (`no-filter-dns`) and its plumbing rule at a DNS path that
     * works on this network. Base configs without that server are left alone.
     */
    private fun applyDns(json: JSONObject, dns: DnsPath) {
        val servers = json.optJSONObject("dns")?.optJSONArray("servers") ?: return
        var found = false
        for (k in 0 until servers.length()) {
            val s = servers.optJSONObject(k) ?: continue
            if (s.optString("tag") == DNS_TAG) {
                dns.url?.let { s.put("address", it) }
                found = true
            }
        }
        if (!found) return
        val rules = json.optJSONObject("routing")?.optJSONArray("rules") ?: return
        for (k in 0 until rules.length()) {
            val r = rules.optJSONObject(k) ?: continue
            val inb = r.optJSONArray("inboundTag") ?: continue
            if ((0 until inb.length()).any { inb.getString(it) == DNS_TAG }) r.put("outboundTag", dns.via)
        }
    }

    const val DNS_TAG = "no-filter-dns"

    /** The Serverless base's fragment outbounds, re-pointed by `defaultVia`. */
    val BASE_BYPASS_TAGS = setOf("tcp-fragment-tls", "tcp-fragment")

    private fun enableStats(json: JSONObject) {
        json.put("stats", json.optJSONObject("stats") ?: JSONObject())
        val policy = json.optJSONObject("policy") ?: JSONObject().also { json.put("policy", it) }
        val system = policy.optJSONObject("system") ?: JSONObject().also { policy.put("system", it) }
        system.put("statsOutboundUplink", true)
        system.put("statsOutboundDownlink", true)
    }

    private fun addFakeDns(json: JSONObject, domains: List<String>) {
        if (domains.isEmpty()) return
        val servers = json.optJSONObject("dns")?.optJSONArray("servers") ?: return
        for (k in 0 until servers.length()) {
            val s = servers.optJSONObject(k) ?: continue
            if (s.optString("address") == "fakedns") {
                val list = s.optJSONArray("domains") ?: JSONArray().also { s.put("domains", it) }
                val have = (0 until list.length()).map { list.getString(it) }.toMutableSet()
                domains.forEach { d -> if (have.add("domain:$d")) list.put("domain:$d") }
                return
            }
        }
    }

    private fun isDnsPlumbing(r: JSONObject): Boolean =
        r.has("inboundTag") || r.optString("outboundTag") == "dns-out"

    private fun depth(domain: String) = domain.count { it == '.' }

    private fun rule(domains: List<String>?, ips: List<String>?, network: String?, outboundTag: String?, balancerTag: String?) =
        JSONObject().apply {
            put("type", "field")
            domains?.let { put("domain", JSONArray(it)) }
            ips?.let { put("ip", JSONArray(it)) }
            network?.let { put("network", it) }
            outboundTag?.let { put("outboundTag", it) }
            balancerTag?.let { put("balancerTag", it) }
        }
}
