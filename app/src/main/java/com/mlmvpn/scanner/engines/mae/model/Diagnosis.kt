package com.mlmvpn.scanner.engines.mae.model

/**
 * What MAE believes about one service on one network, along several independent axes.
 *
 * Deliberately not one enum: a service can be censored AND geo-restricted at the same time (the
 * direct path is cut, and once a bypass gets through the service still refuses the country). Each
 * axis carries its own state, confidence and the evidence behind it, so a later probe can move one
 * axis without erasing what is known about the others.
 */
enum class Axis {
    /** Can anything of the service be reached at all over the plain path. */
    REACHABILITY,
    /** Someone between the user and the service is blocking it (DNS/TCP/TLS interference). */
    CENSORSHIP,
    /** The service itself refuses this line's country. */
    GEO_RESTRICTION,
    DNS_INTERFERENCE,
    TCP_INTERFERENCE,
    TLS_INTERFERENCE,
    UDP_BLOCKED,
    /** The service is down for everyone (no route reaches it). */
    SERVICE_DOWN,
    /** Works, but far slower or lossier than it should be. */
    DEGRADED,
}

enum class Tri { YES, NO, UNKNOWN }

data class AxisValue(
    val state: Tri = Tri.UNKNOWN,
    /** 0..1: how sure. UNKNOWN always carries its best partial confidence toward YES. */
    val confidence: Double = 0.0,
    val evidence: List<String> = emptyList(),
)

data class Diagnosis(
    val axes: Map<Axis, AxisValue> = emptyMap(),
    val at: Long = 0L,
) {
    operator fun get(a: Axis): AxisValue = axes[a] ?: AxisValue()
    fun yes(a: Axis) = get(a).state == Tri.YES

    /**
     * One word for the UI. Only a summary: routing reads the axes, never this. Order matters --
     * the most actionable cause first, and when two are both true (censored + geo) the UI still
     * shows one, while the route has to satisfy both.
     */
    val primary: Primary
        get() = when {
            axes.isEmpty() -> Primary.UNKNOWN
            yes(Axis.SERVICE_DOWN) -> Primary.SERVICE_DOWN
            yes(Axis.CENSORSHIP) && yes(Axis.GEO_RESTRICTION) -> Primary.CENSORED_AND_GEO
            yes(Axis.CENSORSHIP) -> Primary.CENSORED
            yes(Axis.GEO_RESTRICTION) -> Primary.GEO_RESTRICTED
            yes(Axis.DEGRADED) -> Primary.DEGRADED
            get(Axis.REACHABILITY).state == Tri.YES -> Primary.DIRECT_OK
            else -> Primary.UNKNOWN
        }

    enum class Primary { DIRECT_OK, CENSORED, GEO_RESTRICTED, CENSORED_AND_GEO, DEGRADED, SERVICE_DOWN, UNKNOWN }
}
