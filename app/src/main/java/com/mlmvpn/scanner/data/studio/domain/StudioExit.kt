package com.mlmvpn.scanner.data.studio.domain

/**
 * One way out of the installation in a given country («لوکیشن»): a SOCKS5 or HTTP-CONNECT server the
 * operator owns or rents. A config pinned to [cc] leaves through one of the enabled exits there, and
 * is refused -- never sent out directly -- when none of them answers.
 *
 * [cc] is `ZZ` for an exit added without a country, until its first test says where it leaves from.
 */
data class StudioExit(
    val id: String,
    val cc: String,
    val label: String,
    /** The full URL, password included. Shown only as [display]. */
    val url: String,
    val display: String,
    val enabled: Boolean,
    /** `up`, `down` or `unknown`. */
    val health: String,
    val exitIp: String?,
    /** The country the last test actually came out in, which may disagree with [cc]. */
    val exitCc: String?,
    val latencyMs: Int?,
    val checkedAt: Long?,
) {
    val isUp: Boolean get() = health == "up"
    val isDown: Boolean get() = health == "down"
    /** Filed under one country, measured leaving from another -- the worst kind of wrong exit. */
    val isMisfiled: Boolean get() = exitCc != null && cc != "ZZ" && exitCc != cc
}

/** What a paste of exits did: the ids added, and every line that was not, with the reason. */
data class ExitAddResult(val added: List<String>, val rejected: List<Pair<String, String>>)

/**
 * Countries an operator is likely to want, in the order a Persian-speaking operator looks for them.
 * Any other two-letter code still works; these are what the picker offers first.
 */
object ExitCountries {
    val common = listOf(
        "DE", "NL", "GB", "FR", "FI", "SE", "US", "CA", "TR", "AE",
        "AM", "RU", "JP", "SG", "IT", "ES", "PL", "AT", "CH", "RO",
    )

    private val faNames = mapOf(
        "DE" to "آلمان", "NL" to "هلند", "GB" to "انگلیس", "FR" to "فرانسه", "FI" to "فنلاند",
        "SE" to "سوئد", "US" to "آمریکا", "CA" to "کانادا", "TR" to "ترکیه", "AE" to "امارات",
        "AM" to "ارمنستان", "RU" to "روسیه", "JP" to "ژاپن", "SG" to "سنگاپور", "IT" to "ایتالیا",
        "ES" to "اسپانیا", "PL" to "لهستان", "AT" to "اتریش", "CH" to "سوئیس", "RO" to "رومانی",
        "NO" to "نروژ", "DK" to "دانمارک", "BG" to "بلغارستان", "LV" to "لتونی", "KZ" to "قزاقستان",
        "IN" to "هند", "HK" to "هنگ‌کنگ", "KR" to "کره جنوبی", "AU" to "استرالیا", "BR" to "برزیل",
        "QA" to "قطر", "SA" to "عربستان", "OM" to "عمان", "IQ" to "عراق", "GE" to "گرجستان",
        "UA" to "اوکراین", "CZ" to "چک", "HU" to "مجارستان", "PT" to "پرتغال", "IE" to "ایرلند",
        "LU" to "لوکزامبورگ", "BE" to "بلژیک", "GR" to "یونان", "CY" to "قبرس", "TH" to "تایلند",
        "MY" to "مالزی", "ID" to "اندونزی", "VN" to "ویتنام", "UZ" to "ازبکستان", "AZ" to "آذربایجان",
        "TW" to "تایوان", "MX" to "مکزیک", "ZA" to "آفریقای جنوبی", "AR" to "آرژانتین",
    )

    fun flag(cc: String): String {
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    /**
     * «آلمان», or the code itself for a country without a Persian name here. What the app shows:
     * the country badge beside it carries the code, so no flag glyph is drawn anywhere in the UI.
     */
    fun label(cc: String): String = if (cc == "ZZ") "نامشخص" else name(cc)

    /** The Persian name alone, or the code for a country without one. */
    fun name(cc: String): String = faNames[cc] ?: cc

    fun isValid(cc: String?): Boolean = cc != null && cc.length == 2 && cc.all { it in 'A'..'Z' }

    /**
     * A stored label with any flag glyph taken out. Labels written before build 18 carried one
     * («🇩🇪 آلمان»); the redesigned screens draw a badge instead and must not show both.
     */
    fun stripFlags(label: String): String =
        label.replace(Regex("[\\x{1F1E6}-\\x{1F1FF}\\x{26A0}\\x{FE0F}]"), "").trim()
}
