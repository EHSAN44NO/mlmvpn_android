package com.mlmvpn.scanner.ui.arena

/**
 * How each car "feels" about where it is, read off the live standings as they change: overtaking,
 * being overtaken, losing or taking the lead, coming off the bottom, the final stretch. Each change
 * yields one short-lived emoji for that car (the screen shows it for ~1.8 s), so the moods keep
 * changing as the order does. Pure logic -- no Compose -- so it can be tested on its own.
 *
 * Only cars that have been measured take part in the ranking: before their first number every car
 * scores 0, and ranking zeros would make everybody "overtake" everybody at the first measurement.
 */
class ArenaMoods {

    data class Mood(val panelId: String, val emoji: String, val at: Long)

    private var lastRank = emptyMap<String, Int>()
    private var everLed = mutableSetOf<String>()
    private var out = mutableSetOf<String>()
    private var finalStretch = false
    private var lastPhase: Any? = null

    /**
     * Feed the current standings (best first, measured cars only), the cars that are out, the race's
     * progress (0..1) and its phase; get the moods to show now.
     */
    fun update(order: List<String>, gaps: Map<String, Double>, retired: Set<String>, progress: Float, phase: Any?, now: Long): List<Mood> {
        val moods = mutableListOf<Mood>()
        fun say(id: String, e: String) { if (moods.none { it.panelId == id }) moods += Mood(id, e, now) }

        // Retired since last time: the crash face, once.
        for (id in retired - out) say(id, "😵")
        out = retired.toMutableSet()

        val rank = order.withIndex().associate { it.value to it.index }
        val last = order.size - 1
        if (lastRank.isNotEmpty() && order.size >= 2) {
            for ((id, r) in rank) {
                val before = lastRank[id] ?: continue
                when {
                    r < before && r == 0 -> say(id, if (id in everLed) "😤" else "🚀")    // back in front / takes the lead
                    r < before && before == lastRank.size - 1 -> say(id, "🤩")             // off the bottom
                    r < before -> say(id, "😎")                                             // overtakes
                    r > before && before == 0 -> say(id, "😱")                              // loses the lead
                    r > before && r == last -> say(id, "😰")                                // drops to last
                    r > before -> say(id, "😠")                                             // overtaken
                }
            }
        }
        order.firstOrNull()?.let { everLed.add(it) }

        // The final stretch, once: who is winning, who is pushing, who is hanging on.
        if (!finalStretch && progress >= 0.75f && order.size >= 2) {
            finalStretch = true
            say(order[0], "🏆")
            val lead = gaps[order[0]] ?: 0.0
            val second = gaps[order[1]] ?: 0.0
            say(order[1], if (lead - second <= 6.0) "🔥" else "😬")
            if (order.size >= 3) say(order[last], "😓")
        } else if (phase != lastPhase && lastPhase != null && order.isNotEmpty()) {
            // A new stage with the same leader: quietly confident.
            if (lastRank.isNotEmpty() && lastRank[order[0]] == 0) say(order[0], "😏")
        }
        lastPhase = phase
        lastRank = rank
        return moods
    }
}
