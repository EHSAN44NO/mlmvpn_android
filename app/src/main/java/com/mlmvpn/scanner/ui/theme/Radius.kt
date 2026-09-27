package com.mlmvpn.scanner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The app's corner radii. Four values, and nothing else.
 *
 * Before this there were eighteen distinct radii across three hundred and eighty-three call sites
 * -- 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24 -- which is not a scale, it is a
 * record of whatever each screen's author happened to type. Nothing about that reads as one
 * design, and the difference between a 10 and a 12 is invisible on its own and obvious when they
 * sit next to each other.
 *
 * The tiers are chosen the way iOS chooses them: by the ROLE of the thing, not by its size.
 *
 *  - [badge]   small, incidental shapes: status pips, count bubbles, inline tags
 *  - [control] things you press or type into: buttons, text fields, chips
 *  - [panel]   mid-weight containers: a row's card, a section inside a screen
 *  - [card]    the top-level grouped surfaces: settings groups, dialogs, sheets
 *
 * The dock keeps its own 30dp, because it is a bubble rather than a card, and the home icon tiles
 * keep their percentage-based squircle: a fixed radius on a tile whose size the user can change
 * would go square as the tile grew.
 */
object IosRadius {
    val badge = 8.dp
    val control = 12.dp
    val panel = 16.dp
    val card = 22.dp
}

val BadgeShape = RoundedCornerShape(IosRadius.badge)
val ControlShape = RoundedCornerShape(IosRadius.control)
val PanelShape = RoundedCornerShape(IosRadius.panel)
val CardShape = RoundedCornerShape(IosRadius.card)
