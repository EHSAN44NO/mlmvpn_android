package com.mlmvpn.scanner.openvpn

import com.mlmvpn.scanner.quick.GeoLabel
import java.util.Locale

/**
 * Where a profile's server is, for people: a flag and the country's name in the app's language.
 *
 * Built from the profile's own name ("TunnelBear Czech Republic"), through the same resolver the
 * quick-connect list uses, so a country reads identically on every screen. The flag is drawn
 * from the ISO code, never copied from text. TunnelBear's three US profiles differ only by
 * region, which is kept as a short suffix.
 */
object OpenVpnPlaces {
    data class Place(val code: String?, val flag: String, val name: String)

    private val cache = HashMap<String, Place>()

    @Synchronized
    fun of(profile: Profile, fa: Boolean): Place = cache.getOrPut(profile.id + fa) {
        val bare = profile.name.replace(Regex("(?i)^tunnelbear\\s*"), "").removeSuffix(".txt").trim()
        val locale = if (fa) Locale("fa") else Locale.ENGLISH
        val country = GeoLabel.countryFromText(listOf(bare), locale)
            ?: hostCode(profile)?.let { GeoLabel.countryFromCode(it, locale) }
        if (country == null) return@getOrPut Place(null, "🌐", bare.ifBlank { profile.name })
        val region = usRegion(bare, fa)
        Place(country.code, country.flag, if (region != null) "${country.name} · $region" else country.name)
    }

    /** `de.lazerpenguin.com` -> DE, as a fallback when the name says nothing. */
    private fun hostCode(profile: Profile): String? =
        profile.remotes.firstOrNull()?.host?.substringBefore('.')?.takeIf { it.length == 2 }?.uppercase(Locale.US)

    private fun usRegion(bare: String, fa: Boolean): String? {
        val b = bare.lowercase(Locale.US)
        if (!b.startsWith("united states")) return null
        return when {
            b.endsWith("east") -> if (fa) "شرق" else "East"
            b.endsWith("west") -> if (fa) "غرب" else "West"
            b.endsWith("central") -> if (fa) "مرکز" else "Central"
            else -> null
        }
    }
}
