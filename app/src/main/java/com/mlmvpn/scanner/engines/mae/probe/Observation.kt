package com.mlmvpn.scanner.engines.mae.probe

import com.mlmvpn.scanner.engines.mae.model.Family

enum class Step { OK, RESET, TIMEOUT, CERT_MISMATCH, ERROR, SKIPPED }

data class DnsEvidence(
    val systemIps: List<String>,
    val dohIps: List<String>,
) {
    /** Iran's sinkhole (10.10.34.x) or any private/loopback answer for a public name. */
    val systemBogus: Boolean get() = systemIps.isNotEmpty() && systemIps.all { isBogus(it) }
    val disagree: Boolean
        get() = systemIps.isNotEmpty() && dohIps.isNotEmpty() && systemIps.intersect(dohIps.toSet()).isEmpty()

    companion object {
        fun isBogus(ip: String): Boolean =
            ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("192.168.") || ip == "0.0.0.0" ||
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip) || ip == "::1" || ip == "::" ||
                ip.lowercase().startsWith("fd") || ip.lowercase().startsWith("fc")
    }
}

data class HttpEvidence(
    val status: Int,
    /** A registry geo signature matched in the first KBs of body or a header value. */
    val geoSignature: Boolean,
    /** A registry "this is really the service" signature matched. */
    val okSignature: Boolean,
)

/**
 * One probe of one service through one route. What the classifier reads; nothing here holds
 * response bodies or anything about the user -- only which steps passed.
 */
data class Observation(
    val routeId: String,
    /** The route exits abroad (proven or candidate). Only such routes can evidence geo. */
    val foreign: Boolean,
    /** Plain direct path, no bypass technique. Interference evidence comes from here. */
    val direct: Boolean,
    val family: Family? = null,
    val dns: DnsEvidence? = null,
    val tcp: Step = Step.SKIPPED,
    val tls: Step = Step.SKIPPED,
    val http: HttpEvidence? = null,
    val rttMs: Long? = null,
) {
    /** The service itself answered, over its own certificate. */
    val answered: Boolean get() = tls == Step.OK && http != null
    /** Answered, and not with a country refusal. */
    val usable: Boolean
        get() {
            val h = http ?: return false
            if (tls != Step.OK || h.geoSignature) return false
            return h.okSignature || (h.status in 200..499 && h.status != 403 && h.status != 451)
        }
    val refusedCountry: Boolean get() = answered && (http!!.geoSignature)
}
