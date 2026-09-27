package com.mlmvpn.scanner.engines.game.booster.crowd

import com.mlmvpn.core.warp.TweetNaclFast
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the crowd service knows, as the app reads it: one signed slice for everyone ("all": the
 * owner's switches and global counts) and one for the caller's operator ("as<ASN>").
 *
 * Every slice is verified against the service's Ed25519 public key before a single field is
 * believed -- the slice decides which DNS services are tried and whether WARP is measured, so a
 * forged one could steer every player on a network. A slice that does not verify is dropped.
 * Pure: no Android, so it is unit-tested with the same crypto the app runs.
 */
object CrowdSnapshot {

    data class PlanStats(val n: Int, val ok: Int, val bad: Int) {
        /** Share of the sessions with a verdict that worked, or null with fewer than [minVotes] votes. */
        fun successRate(minVotes: Int = 10): Double? = (ok + bad).takeIf { it >= minVotes }?.let { ok.toDouble() / it }
    }

    data class GameStats(
        val n: Int,
        val plans: Map<String, PlanStats>,
        val warpMeasured: Int,
        val warpWon: Int,
        /** Region key → (sessions, typical ping or null). */
        val regions: Map<String, Pair<Int, Int?>>,
        /** Sessions per kind 1–5. */
        val kinds: List<Int>,
        /** Anti-sanction DNS id → (opened, did not). */
        val sdns: Map<String, Pair<Int, Int>>,
    )

    data class Slice(
        val key: String,
        val at: Long,
        val asn: Int?,
        val games: Map<String, GameStats>,
        // Only in the "all" slice.
        val rate: Double? = null,
        val flags: Map<String, Boolean> = emptyMap(),
        val killSdns: Set<String> = emptySet(),
        val killSdnsByAsn: Map<Int, Set<String>> = emptyMap(),
    )

    /** Ed25519 over the body's exact UTF-8 bytes. */
    fun verify(body: String, signatureB64: String, publicKey: ByteArray): Boolean {
        val sig = decodeBase64(signatureB64) ?: return false
        return try {
            TweetNaclFast.Signature(publicKey, null).detached_verify(body.toByteArray(Charsets.UTF_8), sig)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Standard base64 (padding optional), or null. Written out because `java.util.Base64` starts at
     * API 26 and `android.util.Base64` does not exist on the unit-test JVM.
     */
    fun decodeBase64(text: String): ByteArray? {
        val clean = text.trim().trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in clean) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** A verified slice, or null if the signature or the body is not right. */
    fun open(body: String, signatureB64: String, publicKey: ByteArray): Slice? =
        if (!verify(body, signatureB64, publicKey)) null else parse(body)

    fun parse(body: String): Slice? = try {
        val o = JSONObject(body)
        if (o.optInt("v") != 1) null
        else Slice(
            key = o.getString("k"),
            at = o.optLong("at"),
            asn = if (o.has("asn")) o.optInt("asn") else null,
            games = o.optJSONObject("g")?.let { g ->
                g.keys().asSequence().mapNotNull { id -> g.optJSONObject(id)?.let { id to parseGame(it) } }.toMap()
            }.orEmpty(),
            rate = if (o.has("rate")) o.optDouble("rate").takeIf { !it.isNaN() } else null,
            flags = o.optJSONObject("flags")?.let { f ->
                f.keys().asSequence().filter { f.opt(it) is Boolean }.associateWith { f.getBoolean(it) }
            }.orEmpty(),
            killSdns = o.optJSONObject("kill")?.optJSONArray("sdns").strings().toSet(),
            killSdnsByAsn = o.optJSONObject("kill")?.optJSONObject("asn")?.let { a ->
                a.keys().asSequence().mapNotNull { k ->
                    val asn = k.toIntOrNull() ?: return@mapNotNull null
                    asn to a.optJSONObject(k)?.optJSONArray("sdns").strings().toSet()
                }.toMap()
            }.orEmpty(),
        )
    } catch (e: Exception) {
        null
    }

    private fun parseGame(g: JSONObject): GameStats {
        fun ints(a: JSONArray?, n: Int): List<Int> = (0 until n).map { i -> a?.optInt(i) ?: 0 }
        return GameStats(
            n = g.optInt("n"),
            plans = g.optJSONObject("p")?.let { p ->
                p.keys().asSequence().associateWith { k -> ints(p.optJSONArray(k), 3).let { PlanStats(it[0], it[1], it[2]) } }
            }.orEmpty(),
            warpMeasured = g.optJSONArray("w")?.optInt(0) ?: 0,
            warpWon = g.optJSONArray("w")?.optInt(1) ?: 0,
            regions = g.optJSONObject("r")?.let { r ->
                r.keys().asSequence().associateWith { k ->
                    val a = r.optJSONArray(k)
                    (a?.optInt(0) ?: 0) to a?.let { if (it.isNull(1)) null else it.optInt(1) }
                }
            }.orEmpty(),
            kinds = ints(g.optJSONArray("k"), 5),
            sdns = g.optJSONObject("s")?.let { s ->
                s.keys().asSequence().associateWith { k -> ints(s.optJSONArray(k), 2).let { it[0] to it[1] } }
            }.orEmpty(),
        )
    }

    private fun JSONArray?.strings(): List<String> =
        this?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
}

/**
 * What the booster takes from the crowd for one game on one operator: plain decisions with the
 * thresholds that make them safe, so the orchestrator never reads raw counts. Pure.
 */
class CrowdAdvice(private val all: CrowdSnapshot.Slice?, private val mine: CrowdSnapshot.Slice?, private val asn: Int?) {

    /** A switch the owner turned off; everything defaults to on. */
    fun enabled(flag: String): Boolean = all?.flags?.get(flag) ?: true

    /** Anti-sanction DNS services switched off for everyone, or for this operator. */
    fun killedSdns(): Set<String> = all?.killSdns.orEmpty() + (asn?.let { all?.killSdnsByAsn?.get(it) }.orEmpty())

    fun reportRate(): Double = (all?.rate ?: DEFAULT_RATE).coerceIn(0.0, 1.0)

    private fun game(id: String) = mine?.games?.get(id)

    /**
     * The anti-sanction DNS that opened this game's sign-in most reliably on this operator:
     * at least [MIN_VOTES] trials and a success rate above [SDNS_MIN_RATE]. Null when the crowd has
     * not seen enough -- the live trial on the line then decides alone, as before.
     */
    fun bestSdns(gameId: String): String? = game(gameId)?.sdns
        ?.filter { (_, v) -> v.first + v.second >= MIN_VOTES && v.first.toDouble() / (v.first + v.second) >= SDNS_MIN_RATE }
        ?.maxByOrNull { (_, v) -> v.first.toDouble() / (v.first + v.second) }?.key

    /** Services that almost never open this game on this operator: not worth the seconds of trying. */
    fun uselessSdns(gameId: String): Set<String> = game(gameId)?.sdns
        ?.filter { (_, v) -> v.first + v.second >= USELESS_MIN_VOTES && v.first.toDouble() / (v.first + v.second) < USELESS_RATE }
        ?.keys.orEmpty()

    /**
     * WARP measured many times for this game on this operator and almost never better: a quick
     * boost can skip measuring it. A thorough one, or a player who pinned WARP, still does.
     */
    fun warpRarelyWins(gameId: String): Boolean {
        val g = game(gameId) ?: return false
        return g.warpMeasured >= WARP_MIN_MEASURED && g.warpWon.toDouble() / g.warpMeasured < WARP_WIN_RATE
    }

    /** The region with the lowest typical ping on this operator, among those with enough sessions. */
    fun bestRegion(gameId: String): Pair<String, Int>? = game(gameId)?.regions
        ?.filter { (_, v) -> v.first >= REGION_MIN_N && v.second != null }
        ?.minByOrNull { (_, v) -> v.second!! }?.let { (k, v) -> k to v.second!! }

    /** How a plan did for this game on this operator, when enough people said. */
    fun planStats(gameId: String, plan: String): CrowdSnapshot.PlanStats? =
        game(gameId)?.plans?.get(plan)?.takeIf { it.ok + it.bad >= MIN_VOTES }

    companion object {
        const val DEFAULT_RATE = 0.3
        const val MIN_VOTES = 10
        const val SDNS_MIN_RATE = 0.6
        const val USELESS_MIN_VOTES = 20
        const val USELESS_RATE = 0.1
        const val WARP_MIN_MEASURED = 30
        const val WARP_WIN_RATE = 0.05
        const val REGION_MIN_N = 10

        val NONE = CrowdAdvice(null, null, null)
    }
}
