package com.mlmvpn.scanner.engines.flux

import android.content.Context
import com.mlmvpn.scanner.engines.flux.core.model.FluxCandidate
import com.mlmvpn.scanner.engines.flux.core.outbound.FluxOutbounds
import com.mlmvpn.scanner.engines.mae.model.FamilyPolicy
import com.mlmvpn.scanner.engines.mae.route.Capabilities
import com.mlmvpn.scanner.engines.mae.route.RouteKind
import com.mlmvpn.scanner.engines.mae.route.RouteProvider
import org.json.JSONObject

/**
 * FLUX as one of MAE's foreign exits.
 *
 * MAE gets FLUX's proven routes for the network it is on -- read from FLUX's store, nothing more.
 * FLUX does not race, start a tunnel or touch the VPN on MAE's behalf, and MAE never asks it to;
 * MAE measures these exits with its own probe core like any other, per service, and keeps or drops
 * them on its own evidence. That is what keeps the two from looping (MAE -> FLUX -> MAE) or fighting
 * over the TUN: inside MAE, FLUX only contributes outbounds.
 */
class FluxRoute(private val candidate: FluxCandidate) : RouteProvider {

    override val id: String = idFor(candidate.id)
    override val kind = RouteKind.FOREIGN
    override val caps = Capabilities(udp = candidate.node.carriesUdp)
    override val cost = 0.12
    // The family is fixed by the candidate (FLUX raced it on that family on this network).
    override val families = listOf(FamilyPolicy.BOTH)

    // Same length for every FLUX route, so no tag is a prefix of another.
    override fun tag(family: FamilyPolicy) = "mae-$id-d"

    override fun outbounds(): List<JSONObject> = listOf(FluxOutbounds.outbound(candidate, tag(FamilyPolicy.BOTH)))

    /** For diagnostics only. */
    val label: String get() = "FLUX ${candidate.node.redacted()}"

    companion object {
        const val PREFIX = "flx-"
        /** At most this many FLUX exits in MAE's set: each one is a probe and an outbound more. */
        const val MAX = 2

        fun idFor(candidateId: String): String {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(candidateId.toByteArray())
            return PREFIX + d.take(3).joinToString("") { "%02x".format(it) }
        }

        fun isFluxRoute(routeId: String?) = routeId?.startsWith(PREFIX) == true

        /** FLUX's proven routes on [net], as MAE providers. Empty when FLUX knows nothing there. */
        fun forNet(context: Context, net: String): List<RouteProvider> = runCatching {
            FluxEngine.init(context)
            FluxEngine.provenRoutes(net, MAX).map { FluxRoute(it) }
        }.getOrDefault(emptyList())
    }
}
