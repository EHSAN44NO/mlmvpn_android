package com.mlmvpn.scanner.engines.mae.model

/** What happens to a service's traffic when no acceptable route is available. */
enum class FailMode {
    /** Fall back to the fastest bypass route: a plain site is better slow than dead. */
    OPEN,
    /** Block rather than leave by the wrong path (e.g. a service that bans accounts on an Iranian IP). */
    CLOSED,
}

/**
 * Priors from the registry: what is USUALLY true of a service. Used for cold start only. What is
 * actually true on this user's network is learned into [ObservedRequirements] and overrides these.
 */
data class ServiceHints(
    val likelyCensored: Boolean = false,
    val likelyGeoRestricted: Boolean = false,
    val usesUdp: Boolean = false,
    val prefersQuic: Boolean = false,
    val affinitySensitive: Boolean = false,
    val heavy: Boolean = false,
    /**
     * The service refuses exits on Cloudflare's network even where their IP geolocates abroad.
     * Measured (Irancell, 2026-09-30): Gemini loaded through a Worker-based VLESS exit ("RO")
     * and then answered every prompt with "Something went wrong (1060)".
     */
    val refusesCloudflare: Boolean = false,
    val failMode: FailMode = FailMode.OPEN,
)

/**
 * A safe, tiny probe: one GET of [url] that reads at most a few KB. [geoSignatures] are
 * lower-case substrings (in body or a header value) that mean "this country is not served";
 * [okSignatures] mean "this is really the service". No account, cookie or private content.
 */
data class ProbeSpec(
    val url: String,
    val geoSignatures: List<String> = emptyList(),
    val okSignatures: List<String> = emptyList(),
)

data class ServiceDef(
    val id: String,
    /** Resolved to `R.string.mae_service_<nameKey>`; custom sites show [displayName]. */
    val nameKey: String,
    val displayName: String = id,
    val packages: List<String> = emptyList(),
    /** Registered domains; each also covers its subdomains. */
    val domains: List<String> = emptyList(),
    /** CIDRs for traffic that never names a host (MTProto, some QUIC). */
    val ipRanges: List<String> = emptyList(),
    val probes: List<ProbeSpec> = emptyList(),
    val hints: ServiceHints = ServiceHints(),
    val custom: Boolean = false,
    /**
     * Domains that must leave by THIS app's exit whenever it needs a foreign one, even though
     * they are not the app's own: the account's other services, which the provider checks for
     * the same country. Measured (Irancell, 2026-09-29): the Gemini app's backend is
     * `robinfrontend-pa.googleapis.com`; left on Google's Iranian route, Gemini said "not
     * available in your country" while its own domains were abroad.
     */
    val bundle: List<String> = emptyList(),
)

/**
 * What MAE has learned a service needs on one network. Each is a belief with a confidence, and
 * UNKNOWN until evidence arrives; [ServiceHints] only seed the starting point.
 */
data class ObservedRequirements(
    val needsBypass: AxisValue = AxisValue(),
    val needsForeignGeo: AxisValue = AxisValue(),
    val needsUdp: AxisValue = AxisValue(),
    val affinity: AxisValue = AxisValue(),
) {
    companion object {
        /** Cold-start beliefs from the registry, deliberately weak so one real probe outweighs them. */
        fun fromHints(h: ServiceHints) = ObservedRequirements(
            needsBypass = prior(h.likelyCensored),
            needsForeignGeo = prior(h.likelyGeoRestricted),
            needsUdp = prior(h.usesUdp),
            affinity = prior(h.affinitySensitive),
        )

        private fun prior(likely: Boolean) =
            AxisValue(Tri.UNKNOWN, if (likely) 0.35 else 0.1, listOf("registry hint"))

        fun fromDiagnosis(d: Diagnosis, hints: ServiceHints): ObservedRequirements {
            val base = fromHints(hints)
            fun from(a: Axis, fallback: AxisValue) = d[a].takeIf { it.state != Tri.UNKNOWN } ?: fallback
            return base.copy(
                needsBypass = from(Axis.CENSORSHIP, base.needsBypass),
                needsForeignGeo = from(Axis.GEO_RESTRICTION, base.needsForeignGeo),
                needsUdp = base.needsUdp,
            )
        }
    }

    val wantsForeign get() = needsForeignGeo.state == Tri.YES
    val wantsBypass get() = needsBypass.state == Tri.YES
}
