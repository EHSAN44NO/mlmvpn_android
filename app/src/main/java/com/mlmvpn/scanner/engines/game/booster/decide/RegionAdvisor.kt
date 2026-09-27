package com.mlmvpn.scanner.engines.game.booster.decide

import com.mlmvpn.scanner.engines.game.booster.probe.PathStats
import kotlin.math.max

/**
 * Which of the game's regions to pick inside the game, from one short measurement of each.
 *
 * In 2026 this is the biggest lever a free booster has: the Middle East servers most Iranian
 * players used are gone, and the choice between Europe, Asia and whatever comes back is worth tens
 * of milliseconds that no route can buy. The advice keeps the region the player is on unless
 * another is better by a margin run-to-run noise cannot fake, and never recommends a region on its
 * reputation -- only on what answered from this line.
 */
object RegionAdvisor {

    /** One region of the game, measured: UDP echo and/or TCP handshakes to its anchor. */
    data class Sample(
        /** The game's region key ("EU", "ME", …). */
        val regionKey: String,
        val echo: PathStats?,
        val tcp: PathStats?,
    ) {
        /** Echo counts only if a real share of it came back: a trickle is a filtered line, not a result. */
        val echoAnswered: Boolean get() = echo != null && echo.n > 0 && echo.n * 5 >= echo.sent
        val tcpAnswered: Boolean get() = tcp?.answered == true
        val answered: Boolean get() = echoAnswered || tcpAnswered
    }

    data class Advice(
        /** The region the player is on now. */
        val current: String,
        /** The region to play on. Equal to [current] when staying is the advice. */
        val best: String,
        val currentAnswered: Boolean,
        /** How much lower the typical ping is on [best], when both answered. */
        val gainMs: Int?,
        val method: ProbeMethod,
        val bestP50: Int?,
        /** Nothing answered from this line; this is what players on the same operator reported. */
        val fromCrowd: Boolean = false,
    ) {
        val switch: Boolean get() = best != current
    }

    /** Better by at least this much, or by [MARGIN_SHARE] of the current ping if that is more. */
    const val MARGIN_MS = 10
    const val MARGIN_SHARE = 0.10

    fun advise(current: String, samples: List<Sample>): Advice? {
        val answered = samples.filter { it.answered }
        if (answered.isEmpty()) return null
        // Compare like with like: echo only if every region that answered answered echo.
        val method = if (answered.all { it.echoAnswered }) ProbeMethod.ECHO else ProbeMethod.TCP
        fun p50(s: Sample): Int? = if (method == ProbeMethod.ECHO) s.echo?.p50 else s.tcp?.takeIf { it.answered }?.p50
        fun loss(s: Sample): PathStats? = if (method == ProbeMethod.ECHO) s.echo else s.tcp
        val comparable = answered.filter { p50(it) != null }
        if (comparable.isEmpty()) return null

        val cur = comparable.firstOrNull { it.regionKey == current }
        val best = comparable.minWithOrNull(compareBy<Sample> { p50(it)!! }.thenBy { if (it.regionKey == current) 0 else 1 })!!
        if (cur == null) {
            return Advice(current, best.regionKey, currentAnswered = false, gainMs = null, method = method, bestP50 = p50(best))
        }
        val curP50 = p50(cur)!!
        val bestP50 = p50(best)!!
        val margin = max(MARGIN_MS.toDouble(), curP50 * MARGIN_SHARE)
        val clearlyFaster = curP50 - bestP50 >= margin
        val curLoss = loss(cur)
        val bestLoss = loss(best)
        val noWorseLoss = curLoss == null || bestLoss == null ||
            !(curLoss.lossIsMeaningful && bestLoss.lossIsMeaningful) || bestLoss.loss <= curLoss.loss + 1.0
        return if (best.regionKey != current && clearlyFaster && noWorseLoss) {
            Advice(current, best.regionKey, true, curP50 - bestP50, method, bestP50)
        } else {
            Advice(current, current, true, null, method, curP50)
        }
    }
}
