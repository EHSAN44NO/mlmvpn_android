package com.mlmvpn.scanner.engines.mae.store

import com.mlmvpn.scanner.engines.mae.model.Axis
import com.mlmvpn.scanner.engines.mae.model.AxisValue
import com.mlmvpn.scanner.engines.mae.model.Diagnosis
import com.mlmvpn.scanner.engines.mae.model.DnsPath
import com.mlmvpn.scanner.engines.mae.model.EgressProof
import com.mlmvpn.scanner.engines.mae.model.Family
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.model.ForeignEgressStatus
import com.mlmvpn.scanner.engines.mae.model.RouteMetrics
import com.mlmvpn.scanner.engines.mae.model.ServicePolicy
import com.mlmvpn.scanner.engines.mae.model.Tri
import org.json.JSONArray
import org.json.JSONObject

data class SelectedService(val id: String, val paused: Boolean = false)

/** The user's own foreign-exit Worker. [uuidSealed] is sealed by SecureStore before it gets here. */
data class EgressWorker(
    val accountId: String,
    val script: String,
    val url: String,
    val uuidSealed: String,
    val version: Int,
)

/** MAE's own WARP identity. [privateKeySealed] is sealed by SecureStore before it gets here. */
data class WarpIdentity(
    val privateKeySealed: String,
    val peerPublicKey: String,
    val v4: String,
    val v6: String,
    val reserved: List<Int>,
    val via: String,
    val at: Long,
)

data class Incident(val serviceId: String, val netKey: String, val at: Long, val outcome: String)

/**
 * Everything MAE remembers. Keys are `service|net` or `route|service|net`: learning is always per
 * network, because a route that works on one Iranian operator can be dead on the next.
 *
 * Holds only the services the user picked. No visited hosts, no history.
 */
data class MaeState(
    val onboarded: Boolean = false,
    val selected: List<SelectedService> = emptyList(),
    val customSites: List<String> = emptyList(),
    val diagnoses: Map<String, Diagnosis> = emptyMap(),
    val policies: Map<String, ServicePolicy> = emptyMap(),
    val metrics: Map<String, RouteMetrics> = emptyMap(),
    val proofs: Map<String, EgressProof> = emptyMap(),
    val egress: Map<String, ForeignEgressStatus> = emptyMap(),
    val incidents: List<Incident> = emptyList(),
    val worker: EgressWorker? = null,
    /** Outcome of the live-routing spike on this device: null untested, true works, false fall back. */
    val liveApiWorks: Boolean? = null,
    /** Working DNS path per network key. */
    val dnsPaths: Map<String, DnsPath> = emptyMap(),
    val warp: WarpIdentity? = null,
    /** Index into WarpRoute.ENDPOINTS per network key; advanced when the current one carries nothing. */
    val warpEndpoint: Map<String, Int> = emptyMap(),
    /** Last failed attempt to register WARP (retried after a while, not on every discovery). */
    val warpFailedAt: Long = 0L,
    /** Custom site id -> other domains its pages load from (CDNs), learned from the site itself. */
    val siteHosts: Map<String, List<String>> = emptyMap(),
    /** Which window of the user's saved configs is tried as foreign exits, per network key. */
    val userConfigOffset: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun sk(service: String, net: String) = "$service|$net"
        fun rk(route: String, service: String, net: String) = "$route|$service|$net"
        fun pk(route: String, service: String, net: String, f: Family) = "$route|$service|$net|${f.name}"
    }
}

/**
 * JSON codec with a schema version. [SCHEMA] bumps with every incompatible change and [migrate]
 * carries older files forward; a file from the future or one that does not parse is the caller's
 * cue to back it up and start fresh -- MAE relearns, it never crashes on its own memory.
 */
object MaeStateCodec {
    const val SCHEMA = 1
    private const val MAX_INCIDENTS = 30

    fun encode(s: MaeState): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("onboarded", s.onboarded)
        put("selected", JSONArray(s.selected.map { JSONObject().put("id", it.id).put("paused", it.paused) }))
        put("customSites", JSONArray(s.customSites))
        put("diagnoses", obj(s.diagnoses) { encodeDiagnosis(it) })
        put("policies", obj(s.policies) { p ->
            JSONObject().put("s", p.serviceId).put("n", p.netKey).put("r", p.routeId).put("f", p.family.name)
                .put("c", p.confidence).put("w", p.why).put("t", p.decidedAt).put("pin", p.pinned)
        })
        put("metrics", obj(s.metrics) { m ->
            JSONObject().put("ok", m.successes).put("bad", m.failures).put("cf", m.consecutiveFailures)
                .put("rtt", m.rttMs ?: JSONObject.NULL).put("bps", m.throughputBps ?: JSONObject.NULL)
                .put("lok", m.lastOkAt).put("lt", m.lastTestedAt)
        })
        put("proofs", obj(s.proofs) { p ->
            JSONObject().put("r", p.routeId).put("f", p.family.name).put("cc", p.country ?: JSONObject.NULL)
                .put("acc", p.serviceAccepted).put("why", p.reason).put("t", p.at)
        })
        put("egress", obj(s.egress) { e ->
            when (e) {
                ForeignEgressStatus.Untested -> JSONObject().put("k", "u")
                is ForeignEgressStatus.Proven -> JSONObject().put("k", "p").put("r", e.routeId).put("f", e.family.name)
                is ForeignEgressStatus.NoneFound -> JSONObject().put("k", "n").put("rej", JSONObject(e.rejected))
            }
        })
        put("incidents", JSONArray(s.incidents.takeLast(MAX_INCIDENTS).map {
            JSONObject().put("s", it.serviceId).put("n", it.netKey).put("t", it.at).put("o", it.outcome)
        }))
        s.worker?.let { w ->
            put("worker", JSONObject().put("a", w.accountId).put("s", w.script).put("u", w.url)
                .put("id", w.uuidSealed).put("v", w.version))
        }
        s.liveApiWorks?.let { put("liveApi", it) }
        put("dns", obj(s.dnsPaths) { d -> JSONObject().put("u", d.url ?: JSONObject.NULL).put("v", d.via).put("t", d.at) })
        s.warp?.let { w ->
            put("warp", JSONObject().put("k", w.privateKeySealed).put("p", w.peerPublicKey).put("v4", w.v4).put("v6", w.v6)
                .put("r", JSONArray(w.reserved)).put("via", w.via).put("t", w.at))
        }
        put("warpEp", JSONObject(s.warpEndpoint))
        put("warpFail", s.warpFailedAt)
        put("siteHosts", JSONObject().apply { s.siteHosts.forEach { (k, v) -> put(k, JSONArray(v)) } })
        put("ucOff", JSONObject(s.userConfigOffset))
    }.toString()

    /** Throws on anything it cannot read; the store decides what to do about that. */
    fun decode(text: String): MaeState {
        val root = migrate(JSONObject(text))
        return MaeState(
            onboarded = root.optBoolean("onboarded"),
            selected = root.optJSONArray("selected").objs().map { SelectedService(it.getString("id"), it.optBoolean("paused")) },
            customSites = root.optJSONArray("customSites").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) } },
            diagnoses = map(root.optJSONObject("diagnoses")) { decodeDiagnosis(it) },
            policies = map(root.optJSONObject("policies")) { o ->
                ServicePolicy(o.getString("s"), o.getString("n"), o.getString("r"),
                    runCatching { FamilyPolicy.valueOf(o.optString("f")) }.getOrDefault(FamilyPolicy.BOTH),
                    o.optDouble("c", 0.5), o.optString("w"), o.optLong("t"), o.optBoolean("pin"))
            },
            metrics = map(root.optJSONObject("metrics")) { o ->
                RouteMetrics(o.optInt("ok"), o.optInt("bad"), o.optInt("cf"),
                    o.optDoubleOrNull("rtt"), o.optDoubleOrNull("bps"), o.optLong("lok"), o.optLong("lt"))
            },
            proofs = map(root.optJSONObject("proofs")) { o ->
                EgressProof(o.getString("r"), Family.valueOf(o.getString("f")),
                    if (o.isNull("cc")) null else o.optString("cc"), o.optBoolean("acc"), o.optString("why"), o.optLong("t"))
            },
            egress = map(root.optJSONObject("egress")) { o ->
                when (o.optString("k")) {
                    "p" -> ForeignEgressStatus.Proven(o.getString("r"), FamilyPolicy.valueOf(o.getString("f")))
                    "n" -> ForeignEgressStatus.NoneFound(o.optJSONObject("rej")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } }.orEmpty())
                    else -> ForeignEgressStatus.Untested
                }
            },
            incidents = root.optJSONArray("incidents").objs().map { Incident(it.getString("s"), it.getString("n"), it.optLong("t"), it.optString("o")) },
            worker = root.optJSONObject("worker")?.let { w ->
                EgressWorker(w.getString("a"), w.getString("s"), w.getString("u"), w.getString("id"), w.optInt("v"))
            },
            liveApiWorks = if (root.has("liveApi")) root.optBoolean("liveApi") else null,
            dnsPaths = map(root.optJSONObject("dns")) { o -> DnsPath(if (o.isNull("u")) null else o.optString("u"), o.getString("v"), o.optLong("t")) },
            warp = root.optJSONObject("warp")?.let { w ->
                runCatching {
                    val r = w.getJSONArray("r")
                    WarpIdentity(w.getString("k"), w.getString("p"), w.optString("v4"), w.optString("v6"),
                        (0 until r.length()).map { r.getInt(it) }, w.optString("via"), w.optLong("t"))
                }.getOrNull()
            },
            warpEndpoint = root.optJSONObject("warpEp")?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }.orEmpty(),
            warpFailedAt = root.optLong("warpFail"),
            siteHosts = root.optJSONObject("siteHosts")?.let { o ->
                o.keys().asSequence().associateWith { k -> o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() }
            }.orEmpty(),
            userConfigOffset = root.optJSONObject("ucOff")?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }.orEmpty(),
        )
    }

    /** Brings an older file up to [SCHEMA]. Refuses a newer one: it may mean something we can't read. */
    fun migrate(root: JSONObject): JSONObject {
        val v = root.optInt("schema", 0)
        require(v in 0..SCHEMA) { "state schema $v is newer than $SCHEMA" }
        // schema 0 (never shipped) had no version field; nothing else differs.
        root.put("schema", SCHEMA)
        return root
    }

    fun encodeDiagnosis(d: Diagnosis): JSONObject = JSONObject().apply {
        put("t", d.at)
        put("a", JSONObject().apply {
            d.axes.forEach { (axis, v) ->
                put(axis.name, JSONObject().put("s", v.state.name).put("c", v.confidence).put("e", JSONArray(v.evidence)))
            }
        })
    }

    fun decodeDiagnosis(o: JSONObject): Diagnosis {
        val a = o.optJSONObject("a") ?: JSONObject()
        val axes = a.keys().asSequence().mapNotNull { k ->
            val axis = runCatching { Axis.valueOf(k) }.getOrNull() ?: return@mapNotNull null
            val v = a.getJSONObject(k)
            val ev = v.optJSONArray("e")
            axis to AxisValue(Tri.valueOf(v.getString("s")), v.optDouble("c"),
                if (ev == null) emptyList() else (0 until ev.length()).map { ev.getString(it) })
        }.toMap()
        return Diagnosis(axes, o.optLong("t"))
    }

    private fun <T> obj(m: Map<String, T>, enc: (T) -> JSONObject) =
        JSONObject().apply { m.forEach { (k, v) -> put(k, enc(v)) } }

    private fun <T> map(o: JSONObject?, dec: (JSONObject) -> T): Map<String, T> {
        if (o == null) return emptyMap()
        // One bad entry is dropped, not allowed to cost the whole file.
        return o.keys().asSequence().mapNotNull { k -> runCatching { k to dec(o.getJSONObject(k)) }.getOrNull() }.toMap()
    }

    private fun JSONArray?.objs(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.optDoubleOrNull(k: String): Double? =
        if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
}
