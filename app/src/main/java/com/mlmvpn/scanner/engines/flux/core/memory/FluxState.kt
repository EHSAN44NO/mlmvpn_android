package com.mlmvpn.scanner.engines.flux.core.memory

import com.mlmvpn.scanner.engines.flux.core.model.FailReason
import com.mlmvpn.scanner.engines.flux.core.model.Family
import com.mlmvpn.scanner.engines.flux.core.model.FluxEgressIdentity
import com.mlmvpn.scanner.engines.flux.core.model.FragmentProfile
import com.mlmvpn.scanner.engines.flux.core.model.IpMode
import com.mlmvpn.scanner.engines.flux.core.model.NetVerdict
import com.mlmvpn.scanner.engines.flux.core.model.Tri
import org.json.JSONArray
import org.json.JSONObject

/**
 * How one candidate has done on one network. Everything FLUX knows about a route is per network:
 * the best node on MCI is often dead on Irancell.
 */
data class FluxMetrics(
    /** Exponentially weighted success, 0..1; 0.5 for a route never tried. */
    val successEwma: Double = 0.5,
    /** Recent real-request round trips through the route (ms), newest last, at most [RTT_KEEP]. */
    val rtts: List<Long> = emptyList(),
    val handshakeMs: Long? = null,
    /** Measured throughput in kilobits per second, EWMA; null until measured. */
    val kbps: Double? = null,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastOkAt: Long = 0L,
    val lastFailAt: Long = 0L,
    /** The circuit breaker: not raced again before this time. */
    val cooldownUntil: Long = 0L,
    val lastReason: FailReason? = null,
    /** The literal address this candidate dialled the last time it worked (a resolved origin). */
    val dial: String? = null,
) {
    val p50: Long? get() = percentile(50)
    val p95: Long? get() = percentile(95)

    private fun percentile(p: Int): Long? {
        if (rtts.isEmpty()) return null
        val sorted = rtts.sorted()
        return sorted[((sorted.size - 1) * p / 100.0).toInt()]
    }

    fun coolingDown(now: Long) = cooldownUntil > now

    companion object { const val RTT_KEEP = 16 }
}

/** What FLUX has learned about one network. */
data class NetProfile(
    val lastSeen: Long = 0L,
    val verdict: NetVerdict = NetVerdict(),
    /**
     * The routes in use for a policy (`country|mode`), primary first then standbys. Warm start
     * reads this: a network seen before connects to its known route without racing.
     */
    val best: Map<String, List<String>> = emptyMap(),
    /** Cloudflare edges that answered here, best first, per family. */
    val edges: Map<Family, List<String>> = emptyMap(),
    /** The fragment profile that won here, or null while nothing says fragmenting helps. */
    val fragment: FragmentProfile? = null,
    /** Times a TLS handshake was reset right after the ClientHello here (SNI filtering). */
    val sniResets: Int = 0,
    /** Mux measured better here (YES) / worse (NO). */
    val mux: Tri = Tri.UNKNOWN,
)

data class FluxPrefs(
    /** ISO country code, or null for Automatic. */
    val country: String? = null,
    val ipMode: IpMode = IpMode.BOTH,
)

/** Mobile data spent on FLUX's own measurements today (the user's traffic is not counted). */
data class DataBudget(val day: Long = 0L, val bytes: Long = 0L)

/**
 * Everything FLUX keeps, in one versioned file (`filesDir/flux/state.json`). Excluded from backups
 * like MAE's: it is about the networks this device used and means nothing on another.
 */
data class FluxState(
    val nets: Map<String, NetProfile> = emptyMap(),
    /** `net|candidateId` -> metrics. */
    val metrics: Map<String, FluxMetrics> = emptyMap(),
    /** `nodeId@edge` -> the exit, as measured. Not per network: the exit does not move with the user. */
    val egress: Map<String, FluxEgressIdentity> = emptyMap(),
    val prefs: FluxPrefs = FluxPrefs(),
    val data: DataBudget = DataBudget(),
) {
    companion object {
        const val SCHEMA = 1
        fun metricsKey(net: String, candidateId: String) = "$net|$candidateId"
        fun policyKey(country: String?, mode: IpMode) = "${country ?: "auto"}|${mode.code}"
    }
}

/** Hand-written JSON codec: no reflection, no new dependency, and it survives an unknown field. */
object FluxStateCodec {

    fun encode(s: FluxState): String = JSONObject()
        .put("schema", FluxState.SCHEMA)
        .put("prefs", JSONObject().put("country", s.prefs.country ?: JSONObject.NULL).put("ipMode", s.prefs.ipMode.code))
        .put("data", JSONObject().put("day", s.data.day).put("bytes", s.data.bytes))
        .put("nets", JSONObject().also { o -> s.nets.forEach { (k, v) -> o.put(k, net(v)) } })
        .put("metrics", JSONObject().also { o -> s.metrics.forEach { (k, v) -> o.put(k, metrics(v)) } })
        .put("egress", JSONObject().also { o -> s.egress.forEach { (k, v) -> o.put(k, egress(v)) } })
        .toString()

    fun decode(text: String): FluxState {
        val o = JSONObject(text)
        val schema = o.optInt("schema", 0)
        require(schema in 1..FluxState.SCHEMA) { "unknown schema $schema" }
        val p = o.optJSONObject("prefs")
        val d = o.optJSONObject("data")
        return FluxState(
            nets = o.optJSONObject("nets")?.let { n -> n.keys().asSequence().associateWith { net(n.getJSONObject(it)) } } ?: emptyMap(),
            metrics = o.optJSONObject("metrics")?.let { m -> m.keys().asSequence().associateWith { metrics(m.getJSONObject(it)) } } ?: emptyMap(),
            egress = o.optJSONObject("egress")?.let { e -> e.keys().asSequence().associateWith { egress(e.getJSONObject(it)) } } ?: emptyMap(),
            prefs = FluxPrefs(
                country = p?.optString("country")?.takeIf { p.has("country") && !p.isNull("country") && it.isNotEmpty() },
                ipMode = IpMode.of(p?.optString("ipMode")),
            ),
            data = DataBudget(d?.optLong("day") ?: 0L, d?.optLong("bytes") ?: 0L),
        )
    }

    private fun net(n: NetProfile) = JSONObject()
        .put("seen", n.lastSeen)
        .put("v", JSONObject().put("c4", n.verdict.cfV4.code).put("c6", n.verdict.cfV6.code)
            .put("v6", n.verdict.v6.code).put("udp", n.verdict.udp.code).put("at", n.verdict.at))
        .put("best", JSONObject().also { o -> n.best.forEach { (k, v) -> o.put(k, JSONArray(v)) } })
        .put("edges", JSONObject().also { o -> n.edges.forEach { (k, v) -> o.put(k.code, JSONArray(v)) } })
        .put("frag", n.fragment?.code ?: JSONObject.NULL)
        .put("sniResets", n.sniResets)
        .put("mux", n.mux.code)

    private fun net(o: JSONObject): NetProfile {
        val v = o.optJSONObject("v")
        return NetProfile(
            lastSeen = o.optLong("seen"),
            verdict = NetVerdict(
                cfV4 = Tri.of(v?.optString("c4")), cfV6 = Tri.of(v?.optString("c6")),
                v6 = Tri.of(v?.optString("v6")), udp = Tri.of(v?.optString("udp")), at = v?.optLong("at") ?: 0L,
            ),
            best = o.optJSONObject("best")?.let { b -> b.keys().asSequence().associateWith { strings(b.getJSONArray(it)) } } ?: emptyMap(),
            edges = o.optJSONObject("edges")?.let { e -> e.keys().asSequence().mapNotNull { k -> Family.of(k)?.let { it to strings(e.getJSONArray(k)) } }.toMap() } ?: emptyMap(),
            fragment = if (o.isNull("frag") || !o.has("frag")) null else FragmentProfile.of(o.optString("frag")),
            sniResets = o.optInt("sniResets"),
            mux = Tri.of(o.optString("mux")),
        )
    }

    private fun metrics(m: FluxMetrics) = JSONObject()
        .put("s", m.successEwma)
        .put("rtt", JSONArray(m.rtts))
        .put("hs", m.handshakeMs ?: JSONObject.NULL)
        .put("kbps", m.kbps ?: JSONObject.NULL)
        .put("ok", m.okCount).put("fail", m.failCount).put("cf", m.consecutiveFailures)
        .put("lok", m.lastOkAt).put("lfail", m.lastFailAt).put("cool", m.cooldownUntil)
        .put("why", m.lastReason?.code ?: JSONObject.NULL)
        .put("dial", m.dial ?: JSONObject.NULL)

    private fun metrics(o: JSONObject) = FluxMetrics(
        successEwma = o.optDouble("s", 0.5),
        rtts = o.optJSONArray("rtt")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
        handshakeMs = if (o.isNull("hs")) null else o.optLong("hs"),
        kbps = if (o.isNull("kbps")) null else o.optDouble("kbps"),
        okCount = o.optInt("ok"), failCount = o.optInt("fail"), consecutiveFailures = o.optInt("cf"),
        lastOkAt = o.optLong("lok"), lastFailAt = o.optLong("lfail"), cooldownUntil = o.optLong("cool"),
        lastReason = if (o.isNull("why")) null else FailReason.of(o.optString("why")),
        dial = if (o.isNull("dial")) null else o.optString("dial"),
    )

    private fun egress(e: FluxEgressIdentity) = JSONObject()
        .put("v4", e.ipv4 ?: JSONObject.NULL).put("v6", e.ipv6 ?: JSONObject.NULL)
        .put("cc", e.countryCode ?: JSONObject.NULL).put("asn", e.asn ?: JSONObject.NULL)
        .put("org", e.organization ?: JSONObject.NULL).put("at", e.verifiedAt).put("conf", e.confidence)

    private fun egress(o: JSONObject) = FluxEgressIdentity(
        ipv4 = str(o, "v4"), ipv6 = str(o, "v6"), countryCode = str(o, "cc"), asn = str(o, "asn"),
        organization = str(o, "org"), verifiedAt = o.optLong("at"), confidence = o.optDouble("conf", 0.0),
    )

    private fun str(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.optString(k)
    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
}
