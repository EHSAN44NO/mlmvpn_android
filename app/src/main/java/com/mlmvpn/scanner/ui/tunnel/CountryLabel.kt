package com.mlmvpn.scanner.ui.tunnel

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.mlmvpn.core.tunnel.IpFormatter
import java.util.Locale
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * A country, written the way the user reads.
 *
 * One place, because the app was showing countries three different ways at once: the exit card
 * localised them, the Tor and Psiphon setting rows printed the English names baked into
 * `TorRegions`/`PsiphonRegions`, and the picker printed those same English names under Persian
 * headings. On a Persian UI that reads as three different features.
 *
 * The name comes from the platform's own region data against the app's active locale, so there
 * is no translation table to maintain and no list that can fall behind: `US` is "United States"
 * in English and «ایالات متحده» in Persian because the OS says so. The engine packages keep
 * their English tables, which is right for what they are for -- they go into the connection log
 * and into `torrc`, where a Persian name would be wrong.
 */
object CountryLabel {

    /** "🇩🇰 دانمارک", or the code itself when it is not a real country code. */
    @Composable
    fun withFlag(code: String): String {
        val name = localized(code)
        val known = code.trim().takeIf { IpFormatter.isRealCountry(it) }
        return if (known != null) "${IpFormatter.flag(known)} $name" else name
    }

    /** Just the name, for a row that draws its own flag. */
    @Composable
    fun localized(code: String): String {
        val locale = LocalConfiguration.current.locales[0]
        return name(code, locale)
    }

    /**
     * How many exit relays Tor has in this country, in Persian.
     *
     * The number is the honest predictor of whether the choice will be honoured at all, which is
     * why it is next to every country rather than only next to the small ones. `TorRegions` owns
     * the counts and states them in English, which is right for the connection log and wrong for
     * a picker whose every other line is Persian.
     */
    fun torDetail(code: String): String {
        val raw = com.mlmvpn.core.tunnel.TorRegions.detail(code)
        val n = Regex("""^(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: return S(R.string.exit_capacity_unknown)
        val base = S(R.string.exit_relays, fa(n))
        return if (n < 15) S(R.string.base_may_fall_back_to_another_country, base) else base
    }

    /** How many servers Psiphon's embedded list carries for this country, in Persian. */
    fun psiphonDetail(code: String): String {
        val raw = com.mlmvpn.core.tunnel.PsiphonRegions.detail(code)
        val n = Regex("""^(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: return S(R.string.psiphon_lists_this_country_as_available)
        return S(R.string.servers_in_the_list_bundled_with_the, fa(n))
    }

    private fun fa(value: Int): String {
        val digits = charArrayOf('۰', '۱', '۲', '۳', '۴',
                                 '۵', '۶', '۷', '۸', '۹')
        return buildString {
            for (c in value.toString()) append(if (c in '0'..'9') digits[c - '0'] else c)
        }
    }

    /**
     * The non-composable form, for a list built outside composition.
     *
     * Falls back to the code rather than to an empty string: an unrecognised code is still more
     * useful than a blank row, and it makes a bad value visible instead of invisible.
     */
    fun name(code: String, locale: Locale): String {
        val key = code.trim().uppercase()
        if (!IpFormatter.isRealCountry(key)) return code.trim()
        return Locale("", key).getDisplayCountry(locale).ifBlank { key }
    }
}
