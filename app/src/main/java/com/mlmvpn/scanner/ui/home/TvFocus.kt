package com.mlmvpn.scanner.ui.home

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// =================================================================================================
// Reaching the app with a remote control.
//
// The app declares LEANBACK_LAUNCHER, so it installs on Android TV and appears in the launcher --
// and then nothing works. Every control in it is built on `pointerInput`, which answers a finger
// and knows nothing about a directional pad: there was no focus anywhere in the tree, so a
// television showed a board of icons that could be looked at and never opened. That is worse than
// not shipping on TV at all, because it looks like an app rather than like an unfinished one.
//
// The fix is not a separate TV layout. The board built for a tablet is already the right shape on
// a television -- wide, few rows, large tiles -- so what is missing is only the ability to move a
// selection through it and press it. That is what this file adds, as one modifier.
// =================================================================================================

/**
 * True when this is a television rather than a touchscreen.
 *
 * Asked of `UiModeManager` rather than of the screen size, because size cannot tell a 55-inch
 * television from a large tablet and the two want opposite things: the tablet already has touch
 * and needs no focus ring, while the television has no touch at all. A DeX desktop or a tablet
 * with a keyboard still gets focus handling, because [tvFocusable] applies it everywhere -- this
 * flag only decides whether something takes focus on arrival without being asked.
 */
fun isTelevision(context: Context): Boolean {
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
fun rememberIsTelevision(): Boolean {
    val context = LocalContext.current
    return remember(context) { isTelevision(context) }
}

/**
 * Make something reachable and pressable with a directional pad, and visible while it is.
 *
 * Three separate things, and all three are needed — leaving any one out produces a specific
 * failure that looks like a different bug:
 *
 *  - **focusable**, or the pad cannot reach it at all. Compose works out the geometry of the
 *    traversal itself, so a Row of Rows navigates the way it looks.
 *  - **the key handler**, or the selection can be moved onto something and pressing it does
 *    nothing. `pointerInput` never sees a remote's centre key.
 *  - **the ring**, or focus moves invisibly and the remote appears dead while it is in fact
 *    working perfectly.
 *
 * `KeyEventType.KeyUp` rather than KeyDown: a held centre key repeats KeyDown, and on a launcher
 * board that opens the same screen several times before the first one has finished appearing.
 */
fun Modifier.tvFocusable(
    enabled: Boolean = true,
    cornerRadius: Int = 22,
    onPress: () -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this

    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    this
        .onFocusChanged { focused = it.isFocused }
        .focusable(interactionSource = interaction)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    onPress()
                    true
                }
                else -> false
            }
        }
        .then(
            if (focused) {
                Modifier
                    .clip(RoundedCornerShape(cornerRadius.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(
                        width = 2.dp,
                        color = Color.White.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(cornerRadius.dp),
                    )
            } else {
                Modifier
            }
        )
}
