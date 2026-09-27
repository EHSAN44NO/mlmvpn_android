package com.mlmvpn.scanner.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalSystemBottomPadding = staticCompositionLocalOf { 0.dp }
val LocalSystemTopPadding = staticCompositionLocalOf { 0.dp }

/**
 * How much room a hosted screen must leave at the top of its own scrolling content.
 *
 * The feature host no longer pads the top: content runs the full height of the glass and fades
 * out under the status bar, which is the only way to get rid of the cut line there. The cost is
 * that each screen has to keep its FIRST item clear of the status bar and of the chrome bar
 * floating over it, and this is that number -- the system inset plus the bar when there is one.
 */
val LocalContentTopInset = staticCompositionLocalOf { 0.dp }

/**
 * The window's size in pixels, measured by the activity root.
 *
 * The frosted glass needs this to work out which slice of the backdrop each card is covering, and
 * it used to read `LocalView.current.width` instead. That is a plain field, not Compose state:
 * when the first composition ran before the view had been measured it read zero, and nothing ever
 * told the composition to look again. The result was glass that worked on the home screen -- which
 * recomposes every second for the traffic counter, so it eventually caught a real size -- and
 * stayed a flat translucent card everywhere quiet, like Settings. Publishing the measured size as
 * state removes the race entirely.
 */
val LocalWindowSizePx = staticCompositionLocalOf { androidx.compose.ui.unit.IntSize.Zero }
