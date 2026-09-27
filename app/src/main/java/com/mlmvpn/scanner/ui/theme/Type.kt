package com.mlmvpn.scanner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mlmvpn.scanner.R

/**
 * IRANSansX, used for the whole app rather than only for Persian.
 *
 * Two reasons it is not language-switched. The family carries a full Latin set, so English does
 * not fall back to anything; and the app mixes scripts constantly -- a Persian label beside a
 * server name, a protocol, an IP -- so a font that changed with the locale would put two
 * typefaces on the same line.
 *
 * The Latin-numeral cut is the one shipped. See res/font/iransansx.xml for why the FaNum cut,
 * which draws digits with Persian glyphs, is wrong for a screen full of ports and addresses.
 */
val IranSans = FontFamily(
    Font(R.font.iransansx_regular, FontWeight.Normal),
    Font(R.font.iransansx_medium, FontWeight.Medium),
    Font(R.font.iransansx_demibold, FontWeight.SemiBold),
    Font(R.font.iransansx_bold, FontWeight.Bold),
    // Nothing heavier is bundled, so the two weights above Bold resolve to Bold rather than to
    // a synthesised smear.
    Font(R.font.iransansx_bold, FontWeight.ExtraBold),
    Font(R.font.iransansx_bold, FontWeight.Black),
)

/**
 * Material's own type scale with the family swapped in.
 *
 * Every one of the fifteen styles has to be named: `Typography` has no "default family" field, so
 * anything left out silently keeps the platform sans and shows up as one stray English-looking
 * line in the middle of a Persian screen.
 */
fun iranSansTypography(base: Typography): Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = IranSans),
    displayMedium = base.displayMedium.copy(fontFamily = IranSans),
    displaySmall = base.displaySmall.copy(fontFamily = IranSans),
    headlineLarge = base.headlineLarge.copy(fontFamily = IranSans),
    headlineMedium = base.headlineMedium.copy(fontFamily = IranSans),
    headlineSmall = base.headlineSmall.copy(fontFamily = IranSans),
    titleLarge = base.titleLarge.copy(fontFamily = IranSans),
    titleMedium = base.titleMedium.copy(fontFamily = IranSans),
    titleSmall = base.titleSmall.copy(fontFamily = IranSans),
    bodyLarge = base.bodyLarge.copy(fontFamily = IranSans),
    bodyMedium = base.bodyMedium.copy(fontFamily = IranSans),
    bodySmall = base.bodySmall.copy(fontFamily = IranSans),
    labelLarge = base.labelLarge.copy(fontFamily = IranSans),
    labelMedium = base.labelMedium.copy(fontFamily = IranSans),
    labelSmall = base.labelSmall.copy(fontFamily = IranSans),
)
