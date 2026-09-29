package com.mlmvpn.scanner.engines.mae.classify

import com.mlmvpn.scanner.engines.mae.model.Axis
import com.mlmvpn.scanner.engines.mae.model.AxisValue
import com.mlmvpn.scanner.engines.mae.model.Diagnosis
import com.mlmvpn.scanner.engines.mae.model.Tri
import com.mlmvpn.scanner.engines.mae.probe.Observation
import com.mlmvpn.scanner.engines.mae.probe.Step

/**
 * Turns probe observations into a multi-axis [Diagnosis].
 *
 * Each rule is one piece of evidence with a weight, pushing one axis toward YES or NO. Weights on
 * the same side combine as independent evidence (1 - Π(1 - w)), so two weak signals make a strong
 * one but no pile of weak ones reaches certainty. An axis is decided only past [DECIDE] and with a
 * clear margin over the other side; otherwise it stays UNKNOWN -- a valid answer, which tells the
 * engine to gather more evidence (usually: try a foreign route) instead of guessing.
 *
 * The single most important property: a bare HTTP 403 is weak (0.2) evidence of a geo block.
 * It can be a WAF, a bot check or a login wall; it takes the service's own words (a region
 * signature) and/or a foreign route succeeding where the local one was refused.
 */
object Classifier {
    const val DECIDE = 0.75
    private const val MARGIN = 0.2

    data class Rule(
        val axis: Axis,
        val toward: Tri,
        val weight: Double,
        val label: String,
        val test: (List<Observation>) -> Boolean,
    )

    private fun direct(o: List<Observation>) = o.filter { it.direct }
    private fun local(o: List<Observation>) = o.filter { !it.foreign }
    private fun foreign(o: List<Observation>) = o.filter { it.foreign }
    private fun bypass(o: List<Observation>) = o.filter { !it.foreign && !it.direct }

    val RULES: List<Rule> = listOf(
        // --- DNS
        Rule(Axis.DNS_INTERFERENCE, Tri.YES, 0.85, "system DNS answers a sinkhole/private address") { o ->
            direct(o).any { it.dns?.systemBogus == true }
        },
        Rule(Axis.DNS_INTERFERENCE, Tri.YES, 0.45, "system DNS disagrees with DoH") { o ->
            direct(o).any { it.dns?.disagree == true }
        },
        Rule(Axis.DNS_INTERFERENCE, Tri.NO, 0.8, "direct path reached the service with its own certificate") { o ->
            direct(o).any { it.answered }
        },
        // --- TCP
        Rule(Axis.TCP_INTERFERENCE, Tri.YES, 0.5, "direct TCP reset or timed out") { o ->
            direct(o).any { it.tcp == Step.RESET || it.tcp == Step.TIMEOUT }
        },
        Rule(Axis.TCP_INTERFERENCE, Tri.YES, 0.6, "…while another route reached the service") { o ->
            direct(o).any { it.tcp == Step.RESET || it.tcp == Step.TIMEOUT } && o.any { !it.direct && it.answered }
        },
        Rule(Axis.TCP_INTERFERENCE, Tri.NO, 0.8, "direct TCP connected") { o -> direct(o).any { it.tcp == Step.OK } },
        // --- TLS
        Rule(Axis.TLS_INTERFERENCE, Tri.YES, 0.8, "handshake cut after TCP connected (SNI filtering)") { o ->
            direct(o).any { it.tcp == Step.OK && (it.tls == Step.RESET || it.tls == Step.TIMEOUT) }
        },
        Rule(Axis.TLS_INTERFERENCE, Tri.YES, 0.9, "someone else's certificate on the service's address") { o ->
            direct(o).any { it.tls == Step.CERT_MISMATCH }
        },
        Rule(Axis.TLS_INTERFERENCE, Tri.NO, 0.8, "direct TLS handshake completed") { o -> direct(o).any { it.tls == Step.OK } },
        // --- Reachability over the plain path
        Rule(Axis.REACHABILITY, Tri.YES, 0.9, "direct path answered") { o -> direct(o).any { it.answered } },
        Rule(Axis.REACHABILITY, Tri.NO, 0.6, "direct path never completed TLS") { o ->
            direct(o).isNotEmpty() && direct(o).none { it.tls == Step.OK }
        },
        // --- Censorship: interference on the path, cleared by a bypass
        Rule(Axis.CENSORSHIP, Tri.YES, 0.7, "direct path failed at DNS/TCP/TLS") { o ->
            direct(o).isNotEmpty() && direct(o).none { it.answered } &&
                direct(o).any { it.dns?.systemBogus == true || it.tls == Step.CERT_MISMATCH || it.tls == Step.RESET || it.tcp == Step.RESET || it.tcp == Step.TIMEOUT || it.tls == Step.TIMEOUT }
        },
        Rule(Axis.CENSORSHIP, Tri.YES, 0.5, "two independent kinds of interference (DNS sinkhole + TCP/TLS cut)") { o ->
            val d = direct(o)
            val dnsHit = d.any { it.dns?.systemBogus == true }
            val pathHit = d.any { it.tls == Step.RESET || it.tls == Step.CERT_MISMATCH || it.tcp == Step.RESET }
            d.none { it.answered } && dnsHit && pathHit
        },
        Rule(Axis.CENSORSHIP, Tri.YES, 0.6, "a bypass or foreign route reached what direct could not") { o ->
            direct(o).isNotEmpty() && direct(o).none { it.answered } && o.any { !it.direct && it.answered }
        },
        Rule(Axis.CENSORSHIP, Tri.NO, 0.85, "direct path answered with the service's certificate") { o -> direct(o).any { it.answered } },
        // --- Geo restriction: the service's own refusal, confirmed by a foreign route
        Rule(Axis.GEO_RESTRICTION, Tri.YES, 0.2, "HTTP 403/451 on a local route (weak alone)") { o ->
            local(o).any { it.answered && (it.http!!.status == 403 || it.http.status == 451) }
        },
        Rule(Axis.GEO_RESTRICTION, Tri.YES, 0.6, "service's own region-refusal text on a local route") { o ->
            local(o).any { it.refusedCountry }
        },
        Rule(Axis.GEO_RESTRICTION, Tri.YES, 0.5, "the same region-refusal text on two or more local routes") { o ->
            local(o).count { it.refusedCountry } >= 2
        },
        Rule(Axis.GEO_RESTRICTION, Tri.YES, 0.5, "a foreign route is served where local routes were refused") { o ->
            local(o).any { it.answered && !it.usable } && foreign(o).any { it.usable }
        },
        Rule(Axis.GEO_RESTRICTION, Tri.NO, 0.8, "a local route was served normally") { o ->
            local(o).any { it.usable && it.http!!.okSignature }
        },
        Rule(Axis.GEO_RESTRICTION, Tri.NO, 0.5, "a local route answered without refusal") { o ->
            local(o).any { it.usable }
        },
        // --- Service down: nothing reaches it, including abroad
        Rule(Axis.SERVICE_DOWN, Tri.YES, 0.75, "no route (local or foreign) reached the service") { o ->
            foreign(o).isNotEmpty() && local(o).isNotEmpty() && o.none { it.answered }
        },
        Rule(Axis.SERVICE_DOWN, Tri.YES, 0.5, "the service answered 5xx on every route") { o ->
            o.count { it.answered } >= 2 && o.filter { it.answered }.all { it.http!!.status >= 500 }
        },
        Rule(Axis.SERVICE_DOWN, Tri.NO, 0.9, "some route got a real answer") { o ->
            o.any { it.answered && it.http!!.status < 500 }
        },
        // --- Degraded: works, but slowly on the best local route
        Rule(Axis.DEGRADED, Tri.YES, 0.5, "best working local route is over 1.5 s") { o ->
            local(o).filter { it.usable }.mapNotNull { it.rttMs }.minOrNull()?.let { it > 1500 } == true
        },
        Rule(Axis.DEGRADED, Tri.NO, 0.6, "a local route answered under 600 ms") { o ->
            local(o).filter { it.usable }.mapNotNull { it.rttMs }.any { it < 600 }
        },
    )

    fun classify(observations: List<Observation>, now: Long = System.currentTimeMillis(), rules: List<Rule> = RULES): Diagnosis {
        if (observations.isEmpty()) return Diagnosis(emptyMap(), now)
        val axes = Axis.values().associateWith { axis ->
            val fired = rules.filter { it.axis == axis && runCatching { it.test(observations) }.getOrDefault(false) }
            val yes = combine(fired.filter { it.toward == Tri.YES }.map { it.weight })
            val no = combine(fired.filter { it.toward == Tri.NO }.map { it.weight })
            val why = fired.map { (if (it.toward == Tri.YES) "+ " else "− ") + it.label }
            when {
                yes >= DECIDE && yes - no >= MARGIN -> AxisValue(Tri.YES, yes, why)
                no >= DECIDE && no - yes >= MARGIN -> AxisValue(Tri.NO, no, why)
                else -> AxisValue(Tri.UNKNOWN, yes, why)
            }
        }.filterValues { it.evidence.isNotEmpty() }
        return Diagnosis(axes, now)
    }

    fun combine(ws: List<Double>): Double = 1.0 - ws.fold(1.0) { acc, w -> acc * (1.0 - w.coerceIn(0.0, 1.0)) }
}
