package com.mlmvpn.scanner.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import com.mlmvpn.scanner.ui.theme.*
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The one button treatment the app uses for an action.
 *
 * A solid slab of accent with dark or white text on it was the pre-redesign style, and it is the
 * single loudest thing that can appear on a screen sitting over a wallpaper -- it reads as a
 * sticker laid on the glass rather than part of it. Every other surface here is tinted glass with
 * a hairline; a button is the same, with a brighter rim and accent-coloured text so it still
 * reads as the thing to press.
 *
 * Pass the accent that means something on that screen -- [Primary] for a normal action, [RedError]
 * to disconnect or delete, [GreenOk] to confirm. Neutral buttons (a secondary of a pair) pass a
 * neutral fill and get a plain separator rim from [iosNeutralBorder].
 */
@Composable
fun iosButtonColors(accent: Color = Primary): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = accent.copy(alpha = 0.22f),
    // WHITE label, not the accent. The tinted fill and the accent rim already say which action
    // this is; painting the words in it too made every primary button on every screen a patch of
    // blue text, which is the one thing the app's palette reserves for nothing at all. A
    // destructive button still reads destructive: its fill and its rim are red.
    contentColor = TextPrimary,
    // Disabled keeps the shape but drops almost all of the tint, so a disabled button reads as
    // present-but-inert rather than as a differently-coloured live one.
    disabledContainerColor = accent.copy(alpha = 0.08f),
    disabledContentColor = TextDim,
)

/** The rim that goes with [iosButtonColors]. Falls back to a hairline when disabled. */
@Composable
fun iosButtonBorder(accent: Color = Primary, enabled: Boolean = true): BorderStroke =
    BorderStroke(width = 1.5.dp, color = if (enabled) accent else BorderDark)

/**
 * For a button that is deliberately not the primary action on its screen -- the "cancel" half of
 * a pair, or a row of equal-weight choices. Glass with a separator rim and plain white text, so
 * the accent stays reserved for the one thing you most likely want.
 */
@Composable
fun iosNeutralButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = Color.White.copy(alpha = 0.08f),
    contentColor = TextPrimary,
    disabledContainerColor = Color.White.copy(alpha = 0.04f),
    disabledContentColor = TextDim,
)

/** The rim that goes with [iosNeutralButtonColors]. */
@Composable
fun iosNeutralBorder(): BorderStroke = BorderStroke(width = 1.dp, color = BorderDark)

/**
 * The one text-field treatment the app uses.
 *
 * Fields were painted [SurfaceDark] -- the CARD colour -- so a field sitting inside a card was the
 * same shade as the card around it and disappeared into it. iOS insets a field instead: a touch
 * lighter than whatever it sits on, a hairline that lights up in the accent on focus, and white
 * text in both appearances like every other label here.
 *
 * DO NOT let a bulk rewrite of `TextFieldDefaults.colors` reach the call below: this function
 * IS the replacement, so rewriting its own body makes it call itself. That produced a
 * StackOverflowError the moment any screen with a text field composed.
 *
 * `TextFieldDefaults.colors` covers the filled fields and the outlined ones alike -- the border
 * arguments are simply ignored by the filled variant, and the indicator arguments by the outlined
 * one -- so one helper serves both and no call site has to pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun iosFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.08f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
    disabledContainerColor = Color.White.copy(alpha = 0.03f),
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    disabledTextColor = TextDim,
    cursorColor = Primary,
    focusedIndicatorColor = Primary,
    unfocusedIndicatorColor = BorderDark,
    disabledIndicatorColor = BorderDark,
    focusedLabelColor = Primary,
    unfocusedLabelColor = TextMuted,
    disabledLabelColor = TextDim,
    focusedPlaceholderColor = TextDim,
    unfocusedPlaceholderColor = TextDim,
)

/**
 * The look of a pop-up menu.
 *
 * `DropdownMenu` puts this modifier on the Column INSIDE its own elevated surface, so a plain
 * `.background(colour)` here paints a square block inside a rounded card and leaves four corners
 * of a different shade showing. That mismatch is what made these menus look unfinished. Rounding
 * the fill to the same radius as the surface behind it removes the seam, and the extra vertical
 * padding gives the items the breathing room an iOS menu has.
 *
 * The surface behind it comes from the Material colour scheme, which is tuned to the same value.
 */
@Composable
fun Modifier.iosMenu(): Modifier = this
    .background(DialogSurface, MenuShape)
    .padding(vertical = 4.dp)

/** Matches the radius Material gives the menu surface closely enough to hide the join. */
val MenuShape = RoundedCornerShape(14.dp)
