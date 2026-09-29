package com.mlmvpn.scanner.engines.arena

/**
 * The arena's scoring, pure so it can be tested and explained.
 *
 * Each round becomes 0–100 **relative to the best competitor in that round**, as a ratio: for a
 * lower-is-better number (delay) `100 × best / mine`, for a higher-is-better one (speed)
 * `100 × mine / best`. A ratio says what the user would feel -- "half as fast scores 50" -- and
 * never hands the last of two close competitors a zero. A round that ran but that a competitor did
 * not finish scores 0 for it; a round that did not run at all (Quick mode) leaves the score, and
 * the weights of the rounds that did run are scaled back up to 1.
 */
object ArenaScore {

    const val LATENCY = "latency"
    const val REACH = "reach"
    const val SPEED = "speed"
    const val STABILITY = "stability"

    /** The balance: delay matters most, then throughput, then staying up, then reach. */
    val WEIGHTS = linkedMapOf(LATENCY to 0.35, REACH to 0.20, SPEED to 0.25, STABILITY to 0.20)

    private fun lowerBetter(v: Double, best: Double) = if (v <= 0) 0.0 else (100.0 * best / v).coerceAtMost(100.0)
    private fun higherBetter(v: Double, best: Double) = if (best <= 0) 0.0 else (100.0 * v / best).coerceIn(0.0, 100.0)

    /** Stability as one number: answered share (70%) and how little the delay wanders (30%). */
    private fun stabilityRaw(e: Entry, bestJitter: Long?): Double? {
        val success = e.stabilitySuccess ?: return null
        val jitterPart = if (bestJitter == null || e.jitter == null) 1.0 else (bestJitter + 20.0) / (e.jitter!! + 20.0)
        return 100.0 * (0.7 * success + 0.3 * jitterPart.coerceAtMost(1.0))
    }

    fun score(entries: List<Entry>): Scoreboard {
        val q = entries.filter { it.qualified }
        if (q.isEmpty()) return Scoreboard(emptyList(), emptyMap(), emptyMap())

        val parts = HashMap<String, MutableMap<String, Double>>()
        fun put(id: String, round: String, v: Double) { parts.getOrPut(id) { HashMap() }[round] = v }
        val ran = LinkedHashSet<String>()

        // Delay: the median of the round's samples.
        if (q.any { it.latency.isNotEmpty() }) {
            ran += LATENCY
            val best = q.mapNotNull { it.latencyMedian }.minOrNull()?.toDouble()
            for (e in q) put(e.panelId, LATENCY, if (best == null) 0.0 else e.latencyMedian?.let { lowerBetter(it.toDouble(), best) } ?: 0.0)
        }
        // Reach: a Cloudflare-hosted page answered at all, and how fast.
        if (q.any { it.reachTried }) {
            ran += REACH
            val best = q.mapNotNull { it.reachMs }.minOrNull()?.toDouble()
            for (e in q) put(e.panelId, REACH, if (best == null) 0.0 else e.reachMs?.let { lowerBetter(it.toDouble(), best) } ?: 0.0)
        }
        // Throughput over the same file for everybody.
        if (q.any { it.mbps != null }) {
            ran += SPEED
            val best = q.mapNotNull { it.mbps }.maxOrNull() ?: 0.0
            for (e in q) put(e.panelId, SPEED, e.mbps?.let { higherBetter(it, best) } ?: 0.0)
        }
        // Staying up over the short watch.
        if (q.any { it.stability.isNotEmpty() }) {
            ran += STABILITY
            val bestJitter = q.mapNotNull { it.jitter }.minOrNull()
            val raws = q.associate { it.panelId to (stabilityRaw(it, bestJitter) ?: 0.0) }
            val best = raws.values.maxOrNull() ?: 0.0
            for (e in q) put(e.panelId, STABILITY, higherBetter(raws.getValue(e.panelId), best))
        }

        val sum = ran.sumOf { WEIGHTS.getValue(it) }
        val weights = ran.associateWith { WEIGHTS.getValue(it) / sum }
        val standings = q.map { e ->
            val p = parts[e.panelId].orEmpty()
            Standing(e.panelId, weights.entries.sumOf { (r, w) -> w * (p[r] ?: 0.0) }, p)
        }.sortedWith(compareByDescending<Standing> { it.total }.thenBy { s -> q.first { it.panelId == s.panelId }.latencyMedian ?: Long.MAX_VALUE })

        // A category needs at least two competitors with that measurement, or it says nothing.
        val cats = LinkedHashMap<Category, String>()
        if (standings.size >= 2) cats[Category.OVERALL] = standings.first().panelId
        q.filter { it.latencyMedian != null }.takeIf { it.size >= 2 }?.minByOrNull { it.latencyMedian!! }?.let { cats[Category.LATENCY] = it.panelId }
        q.filter { it.mbps != null }.takeIf { it.size >= 2 }?.maxByOrNull { it.mbps!! }?.let { cats[Category.SPEED] = it.panelId }
        if (STABILITY in ran && q.count { it.stability.isNotEmpty() } >= 2) {
            standings.maxByOrNull { it.parts[STABILITY] ?: -1.0 }?.let { cats[Category.STABLE] = it.panelId }
        }
        q.filter { it.reachMs != null }.takeIf { q.count { e -> e.reachTried } >= 2 && it.isNotEmpty() }?.minByOrNull { it.reachMs!! }?.let { cats[Category.REACH] = it.panelId }

        return Scoreboard(standings, cats, weights)
    }
}
