package com.mlmvpn.scanner.engines.mae.policy

/** What the user says is wrong, asked from the second "didn't open" on. */
enum class Symptom {
    /** Nothing loads at all. */
    NOT_OPENING,
    /** Opens, but images / videos / parts never load. */
    PARTIAL_LOAD,
    /** "Not available in your country", 403, access denied. */
    GEO_BLOCKED,
    /** Works but very slowly. */
    SLOW,
    /** Login, verification, captcha or account trouble. */
    LOGIN,
    /** Voice / video calls or live video do not work. */
    MEDIA_CALLS,
}

/** Where one app stands on the repair ladder on one network. */
data class RepairState(
    val level: Int = 0,
    val symptom: Symptom? = null,
    /** `route:FAMILY` pairs the user already reported as not working, oldest first. */
    val tried: List<String> = emptyList(),
    val at: Long = 0L,
)

/**
 * What one repair attempt does differently. Every field changes discovery or the decision; the
 * ladder makes each rung a genuinely different approach, not the same probe again.
 */
data class RepairPlan(
    val level: Int,
    val symptom: Symptom?,
    /** Routes not to choose again (the ones that failed for this app). */
    val excludedRoutes: Set<String> = emptySet(),
    /** Probe foreign exits even when a local route looks fine. */
    val forceForeign: Boolean = false,
    /** Treat the app as needing a foreign IP (the user saw a country refusal). */
    val needsForeign: Boolean = false,
    /** Only routes that carry UDP (QUIC video, calls). */
    val requireUdp: Boolean = false,
    /** Score throughput first and measure it harder. */
    val preferThroughput: Boolean = false,
    /** One stable exit: a single IP family, not the Worker whose exit changes per connection. */
    val stableExit: Boolean = false,
    /** Re-read the app's page for the other domains it loads from. */
    val learnHosts: Boolean = false,
    /** Everything: routes in backoff too, more candidates, bigger samples. */
    val deep: Boolean = false,
    val why: String = "",
)

/**
 * The repair ladder: up to [MAX] different approaches for an app the user keeps saying does not
 * work. The first rung needs no question; from the second the user is asked what is wrong, and
 * the answer steers every rung after it. "Opened" resets the ladder, and so does a day's quiet.
 *
 * The first route an app gets is NOT a cheap guess that the ladder then fixes: it is the full
 * staged discovery. The ladder is for what probes cannot see -- the app's own login, a CDN the
 * page did not name, an exit the service quietly dislikes.
 *
 * Pure, unit-tested.
 */
object RepairLadder {
    const val MAX = 5
    const val RESET_MS = 24 * 3600_000L

    /** One more "didn't open": the next rung, remembering what was in use when it failed. */
    fun next(current: RepairState?, failedRoute: String?, now: Long): RepairState {
        val fresh = current == null || now - current.at > RESET_MS
        val base = if (fresh) RepairState() else current!!
        return base.copy(
            level = (base.level + 1).coerceAtMost(MAX),
            tried = (base.tried + listOfNotNull(failedRoute)).distinct(),
            at = now,
        )
    }

    /** From the second rung the user is asked what is wrong before anything is tried. */
    fun needsQuestion(s: RepairState) = s.level >= 2

    fun exhausted(s: RepairState) = s.level >= MAX

    fun plan(s: RepairState): RepairPlan {
        val triedRoutes = s.tried.map { it.substringBefore(':') }.toSet()
        val lastRoute = triedRoutes.lastOrNull()
        val deep = s.level >= MAX
        if (s.level <= 1 || s.symptom == null) {
            return RepairPlan(s.level, s.symptom, excludedRoutes = setOfNotNull(lastRoute), learnHosts = true, deep = deep,
                why = "re-checked everything; the route that failed is set aside")
        }
        val L = s.level
        return when (s.symptom) {
            // Rung 2: every failed route aside. 3: foreign exits probed too. 4: assume the block is
            // really a quiet country refusal and require a foreign exit. 5: the deep check.
            Symptom.NOT_OPENING -> RepairPlan(L, s.symptom,
                excludedRoutes = triedRoutes, forceForeign = L >= 3, needsForeign = L == 4, learnHosts = true, deep = deep,
                why = "nothing loads: every route that failed is set aside" + when {
                    L >= 5 -> ", every route re-measured"
                    L == 4 -> ", treated as a country block"
                    L == 3 -> ", foreign exits tried too"
                    else -> ""
                })
            Symptom.PARTIAL_LOAD -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 4) triedRoutes else emptySet(), learnHosts = true, requireUdp = L >= 3, deep = deep,
                why = "loads partly: its other domains re-learned" + if (L >= 3) ", a route that also carries video (UDP)" else "")
            Symptom.GEO_BLOCKED -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) triedRoutes else emptySet(), forceForeign = true, needsForeign = true,
                stableExit = L >= 4, deep = deep,
                why = "country refusal: only proven foreign exits" + if (L >= 3) ", a different exit than before" else "")
            Symptom.SLOW -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) setOfNotNull(lastRoute) else emptySet(), preferThroughput = true, deep = true,
                why = "slow: throughput measured harder and ranked first")
            Symptom.LOGIN -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) triedRoutes else emptySet(), stableExit = true, forceForeign = L >= 3,
                needsForeign = L >= 4, deep = deep,
                why = "login trouble: one stable exit, one IP family" + if (L >= 4) ", abroad" else "")
            Symptom.MEDIA_CALLS -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) triedRoutes else emptySet(), requireUdp = true, preferThroughput = L >= 3, deep = deep,
                why = "calls/video: only routes that carry UDP")
        }
    }
}
