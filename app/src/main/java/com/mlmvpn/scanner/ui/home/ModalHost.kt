package com.mlmvpn.scanner.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.ui.LocalContentTopInset
import com.mlmvpn.scanner.ui.LocalSystemTopPadding

/**
 * The backdrop for a full-screen modal.
 *
 * A `Dialog` opens in its OWN window, so nothing the activity painted reaches it -- including the
 * wallpaper. Several full-screen modals were therefore filling themselves with [BgDark], a 26%
 * black wash designed to sit ON the wallpaper and let it through. With no wallpaper behind it
 * that wash is simply a dim pane of nothing, which is exactly the "the modal has no background,
 * it has gone transparent" that gets reported about the server lists.
 *
 * This paints what a hosted screen gets from AppScreen -- the same wallpaper at the same blur,
 * then the same scrim -- so a full-screen modal is indistinguishable from a page. It also
 * provides [LocalContentTopInset], because a modal that fills the screen has to clear the status
 * bar itself the way a page does.
 *
 * Use it for modals that fill the screen. A small centred dialog wants `DialogSurface` instead:
 * it is a card floating over the current screen, not a replacement for it.
 */
@Composable
fun IosModalHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = Appearance.isDark()
    Box(modifier = modifier.fillMaxSize()) {
        if (Appearance.wallpaperEverywhere) {
            HomeWallpaper(
                wallpaperId = Wallpapers.current,
                blurPercent = Wallpapers.blur,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Matches AppScreen's host exactly. If these two ever diverge, a modal will read
                // as a slightly different shade of the same screen, which is worse than an
                // obviously different one.
                .background(
                    when {
                        !Appearance.wallpaperEverywhere ->
                            if (dark) Color(0xFF000000) else Color(0xFF1C1C1E)
                        dark -> Color.Black.copy(alpha = 0.45f)
                        else -> Color.Black.copy(alpha = 0.20f)
                    }
                )
        ) {
            CompositionLocalProvider(
                LocalContentTopInset provides LocalSystemTopPadding.current + 0.dp
            ) {
                content()
            }
        }
    }
}
