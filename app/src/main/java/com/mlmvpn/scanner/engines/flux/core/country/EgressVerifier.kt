package com.mlmvpn.scanner.engines.flux.core.country

import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import org.json.JSONObject

/**
 * Where a route really exits. Never the node's label: a link named "🇺🇸 US" says what its
 * publisher wrote, not where the traffic leaves. The country comes from asking the outside world,
 * through the route, and from more than one source when the answer matters.
 *
 * Sources are chosen to not share one database or one CDN:
 *  - Cloudflare's trace (`loc=`) -- answered by IP, so it works even where Cloudflare's names are filtered;
 *  - ip-api.com -- not behind Cloudflare, plain HTTP is fine (the request already rides the tunnel);
 *  - ipwho.is -- a third, used only to break a disagreement.
 */
object EgressVerifier {

    data class Source(val id: String, val url: String)

    val TRACE = Source("cf-trace", "https://1.1.1.1/cdn-cgi/trace")
    val IP_API = Source("ip-api", "http://ip-api.com/json/?fields=status,countryCode,query,as,org")
    val IPWHO = Source("ipwho", "https://ipwho.is/?fields=success,ip,country_code,connection")

    /** A fourth opinion, asked only when the others disagree. */
    val IFCONFIG = Source("ifconfig", "https://ifconfig.co/json")

    /** First pair asked; [IPWHO] only when these two disagree. */
    val PRIMARY = listOf(TRACE, IP_API)

    data class Observation(val source: String, val ip: String?, val countryCode: String?, val asn: String? = null, val org: String? = null)

    /** Reads one source's answer, or null when it is not a usable answer. */
    fun parse(sourceId: String, body: String): Observation? = runCatching {
        when (sourceId) {
            TRACE.id -> {
                val kv = body.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
                Observation(sourceId, kv["ip"], cc(kv["loc"]))
            }
            IP_API.id -> {
                val o = JSONObject(body)
                if (o.optString("status") != "success") return null
                Observation(sourceId, o.optString("query").ifEmpty { null }, cc(o.optString("countryCode")),
                    o.optString("as").substringBefore(' ').ifEmpty { null }, o.optString("org").ifEmpty { null })
            }
            IPWHO.id -> {
                val o = JSONObject(body)
                if (o.has("success") && !o.optBoolean("success")) return null
                val conn = o.optJSONObject("connection")
                Observation(sourceId, o.optString("ip").ifEmpty { null }, cc(o.optString("country_code")),
                    conn?.optString("asn")?.ifEmpty { null }?.let { "AS$it" }, conn?.optString("org")?.ifEmpty { null })
            }
            IFCONFIG.id -> {
                val o = JSONObject(body)
                Observation(sourceId, o.optString("ip").ifEmpty { null }, cc(o.optString("country_iso")),
                    o.optString("asn").ifEmpty { null }, o.optString("asn_org").ifEmpty { null })
            }
            else -> null
        }?.takeIf { it.countryCode != null }
    }.getOrNull()

    /**
     * One identity from what the sources said. Two agreeing: confidence 1. One alone: 0.6 -- good
     * enough to race on, re-checked in the background. Disagreeing with no majority: no country at
     * all (confidence 0), so the route serves Automatic but never a country choice.
     */
    fun combine(obs: List<Observation>, now: Long): FluxEgressIdentity {
        val valid = obs.filter { it.countryCode != null }
        if (valid.isEmpty()) return FluxEgressIdentity(verifiedAt = now, confidence = 0.0)
        val byCc = valid.groupBy { it.countryCode!! }
        val (cc, voters) = byCc.maxByOrNull { it.value.size }!!
        val confidence = when {
            voters.size >= 2 && voters.size > valid.size / 2.0 -> 1.0
            valid.size == 1 -> 0.6
            else -> 0.0
        }
        val ips = valid.mapNotNull { it.ip }
        return FluxEgressIdentity(
            ipv4 = ips.firstOrNull { Family.ofLiteral(it) == Family.V4 },
            ipv6 = ips.firstOrNull { Family.ofLiteral(it) == Family.V6 },
            countryCode = if (confidence > 0) cc else null,
            asn = voters.firstNotNullOfOrNull { it.asn },
            organization = voters.firstNotNullOfOrNull { it.org },
            verifiedAt = now,
            confidence = confidence,
        )
    }

    /**
     * Whether a route may serve the user's [chosen] country (null = Automatic: any working exit).
     * The node's own label plays no part.
     */
    fun accepts(identity: FluxEgressIdentity?, chosen: String?, now: Long): Boolean {
        if (chosen == null) return true
        if (identity == null || !identity.valid(now)) return false
        return identity.countryCode.equals(chosen, ignoreCase = true) && identity.confidence >= 0.6
    }

    private fun cc(raw: String?): String? = raw?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "XX" }
}
