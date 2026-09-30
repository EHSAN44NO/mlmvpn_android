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
     * Reached through a Cloudflare config, the service needs the US exit (a Durable Object in
     * North America on the user's own account; see [com.mlmvpn.scanner.utils.XrayJsonGenerator.geminiExit]).
     * A Worker leaves from the colo nearest the phone and Google places that in Iran or Russia.
     * Measured (Irancell, 2026-09-30): Gemini through a Worker-based VLESS config got
     * `"rtQCxc":-210` (Iran's time zone) and answered every prompt with "Something went wrong (1060)".
     */
    val usExit: Boolean = false,
    val failMode: FailMode = FailMode.OPEN,
    /**
     * The app refuses Iranian addresses whatever its probe page shows. TikTok's website opens
     * from Iran while its app does not (the user, 2026-09-30: five dislikes on Iranian routes, none
     * opened it): the web page is no evidence for the app, so the app always goes abroad.
     */
    val requiresForeign: Boolean = false,
    /**
     * What the app checks on the phone itself, beyond the network: `sim` (the SIM card's country),
     * `timezone` (the phone's time zone). No route changes these; when the phone says Iran, MAE
     * tells the user instead of trying route after route.
     */
    val clientChecks: List<String> = emptyList(),
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
    /** How much of the answer to read; 0 = the default few KB. Google's verdict flags sit near 8 KB. */
    val readBytes: Int = 0,
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
                needsForeignGeo = if (hints.requiresForeign) AxisValue(Tri.YES, 0.8, listOf("registry: the app refuses Iranian addresses"))
                    else from(Axis.GEO_RESTRICTION, base.needsForeignGeo),
                needsUdp = base.needsUdp,
            )
        }
    }

    val wantsForeign get() = needsForeignGeo.state == Tri.YES
    val wantsBypass get() = needsBypass.state == Tri.YES
}
