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
    /**
     * The app says there is no internet while everything else works. TikTok's way of refusing a
     * country: to the app it is a country refusal, whatever the words on the screen.
     */
    APP_SAYS_OFFLINE,
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
    /**
     * Also look at the phone itself: an app that checks the SIM's country or the time zone refuses
     * Iran through any exit, and no rung fixes that -- the user has to be told.
     */
    val deviceCheck: Boolean = false,
    val why: String = "",
)

/**
 * The repair ladder: up to [MAX] different approaches for an app the user keeps saying does not
 * work. The first rung needs no question; from the second the user is asked what is wrong, and
 * the answer steers every rung after it. "Opened" resets the ladder, and so does a day's quiet.
 *
 * The first route an app gets is NOT a cheap guess that the ladder then fixes: it is the full
 * staged discovery. The ladder is for what probes cannot see -- the app's own login, a CDN the
 * page did not name, an exit the service quietly dislikes, a country check the web page does not
 * show.
 *
 * Pure, unit-tested.
 */
object RepairLadder {
    const val MAX = 5
    const val RESET_MS = 24 * 3600_000L

    /** [s] while it is still current: a day's quiet starts the ladder over. */
    fun active(s: RepairState?, now: Long): RepairState? = s?.takeIf { now - it.at <= RESET_MS }

    /** One more "didn't open": the next rung, remembering what was in use when it failed. */
    fun next(current: RepairState?, failedRoute: String?, now: Long): RepairState {
        val base = active(current, now) ?: RepairState()
        return base.copy(
            level = (base.level + 1).coerceAtMost(MAX),
            tried = (base.tried + listOfNotNull(failedRoute)).distinct(),
            at = now,
        )
    }

    /** The rung a "didn't open" now would reach. */
    fun nextLevel(current: RepairState?, now: Long): Int = ((active(current, now)?.level ?: 0) + 1).coerceAtMost(MAX)

    /** From the second rung the user is asked what is wrong before anything is tried. */
    fun needsQuestion(level: Int) = level >= 2
    fun needsQuestion(s: RepairState) = needsQuestion(s.level)

    fun exhausted(s: RepairState) = s.level >= MAX

    /** A symptom that means the app refused the country, whatever its screen says. */
    fun isGeo(symptom: Symptom?) = symptom == Symptom.GEO_BLOCKED || symptom == Symptom.APP_SAYS_OFFLINE

    /**
     * The plan for rung [s].
     *
     * [likelyGeo]: the app usually refuses Iranian addresses (a registry hint). [onLocalRoute]: it
     * failed on a route that leaves with an Iranian address. Together they send even the first rung
     * abroad: for such an app another Iranian route is the least likely fix (TikTok, 2026-09-30:
     * five dislikes, and every rung before the third was another Iranian route).
     */
    fun plan(s: RepairState, likelyGeo: Boolean = false, onLocalRoute: Boolean = false): RepairPlan {
        val triedRoutes = s.tried.map { it.substringBefore(':') }.toSet()
        // The LAST failure, not the last distinct route: `toSet()` keeps first-seen order.
        val lastRoute = s.tried.lastOrNull()?.substringBefore(':')
        val L = s.level
        val deep = L >= MAX
        if (L <= 1 || s.symptom == null) {
            val abroad = likelyGeo && (onLocalRoute || L >= 2)
            return RepairPlan(L, s.symptom, excludedRoutes = setOfNotNull(lastRoute), forceForeign = abroad, needsForeign = abroad,
                learnHosts = true, deep = deep,
                why = if (abroad) "an app that usually refuses Iranian addresses: a proven foreign exit"
                    else "re-checked everything; the route that failed is set aside")
        }
        return when (s.symptom) {
            // Rung 2: every failed route aside. 3: foreign exits probed too. 4 and 5: assume the
            // block is really a quiet country refusal and require a foreign exit (5 also re-measures
            // everything). An app that usually refuses Iran goes abroad from rung 2.
            Symptom.NOT_OPENING -> RepairPlan(L, s.symptom,
                excludedRoutes = triedRoutes, forceForeign = L >= 3 || likelyGeo, needsForeign = L >= 4 || likelyGeo,
                learnHosts = true, deep = deep,
                why = "nothing loads: every route that failed is set aside" + when {
                    L >= 5 -> ", every route re-measured, abroad"
                    L == 4 || likelyGeo -> ", treated as a country block"
                    L == 3 -> ", foreign exits tried too"
                    else -> ""
                })
            Symptom.PARTIAL_LOAD -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 4) triedRoutes else emptySet(), learnHosts = true, requireUdp = L >= 3, deep = deep,
                forceForeign = likelyGeo, needsForeign = likelyGeo,
                why = "loads partly: its other domains re-learned" + if (L >= 3) ", a route that also carries video (UDP)" else "")
            Symptom.GEO_BLOCKED, Symptom.APP_SAYS_OFFLINE -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) triedRoutes else setOfNotNull(lastRoute), forceForeign = true, needsForeign = true,
                stableExit = L >= 4, deep = deep, deviceCheck = true,
                why = "country refusal: only proven foreign exits" + if (L >= 3) ", a different exit than before" else "")
            Symptom.SLOW -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) setOfNotNull(lastRoute) else emptySet(), preferThroughput = true, deep = true,
                forceForeign = likelyGeo, needsForeign = likelyGeo,
                why = "slow: throughput measured harder and ranked first")
            Symptom.LOGIN -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) triedRoutes else emptySet(), stableExit = true, forceForeign = L >= 3 || likelyGeo,
                needsForeign = L >= 4 || likelyGeo, deep = deep, deviceCheck = L >= 3,
                why = "login trouble: one stable exit, one IP family" + if (L >= 4 || likelyGeo) ", abroad" else "")
            Symptom.MEDIA_CALLS -> RepairPlan(L, s.symptom,
                excludedRoutes = if (L >= 3) triedRoutes else emptySet(), requireUdp = true, preferThroughput = L >= 3, deep = deep,
                why = "calls/video: only routes that carry UDP")
        }
    }
}
