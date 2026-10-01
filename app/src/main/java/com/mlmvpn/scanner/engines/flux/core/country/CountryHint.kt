package com.mlmvpn.scanner.engines.flux.core.country

import java.util.Locale

/**
 * What a node's own name claims about its country: a flag emoji, or a two-letter code standing
 * alone ("CA 🇨🇦 | …", "vless-DE", "[x] US"). A hint only -- it orders candidates when the user
 * picks a country and lists countries not yet verified -- and never proof: [EgressVerifier] decides
 * where a route exits.
 */
object CountryHint {

    private val ISO = Locale.getISOCountries().toHashSet()
    /** Two-letter words that are far more often something else in these names. */
    private val NOISE = setOf("IP", "TV", "WS", "AS", "AM", "IT", "IS", "IN", "TO", "NO", "ME", "BY", "DO", "SO", "AT", "BE", "MY", "AI", "GO", "OR")

    fun of(label: String): String? {
        flag(label)?.let { return it }
        return label.split(Regex("[^A-Za-z]+"))
            .firstOrNull { it.length == 2 && it.all { c -> c.isUpperCase() } && it in ISO && it !in NOISE }
    }

    /** The first regional-indicator pair (a flag emoji) as an ISO code. */
    private fun flag(s: String): String? {
        val cps = s.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            val a = cps[i] - 0x1F1E6; val b = cps[i + 1] - 0x1F1E6
            if (a in 0..25 && b in 0..25) {
                val cc = "${'A' + a}${'A' + b}"
                if (cc in ISO) return cc
            }
        }
        return null
    }
}
