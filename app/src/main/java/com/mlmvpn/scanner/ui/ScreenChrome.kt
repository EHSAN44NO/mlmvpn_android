package com.mlmvpn.scanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mlmvpn.scanner.ui.home.fadingTopEdge

/**
 * A screen whose header floats over its own scrolling content.
 *
 * This is the shape every screen in the app ends up with, and it exists because the alternative --
 * a Column of "header, then list" -- cannot be made to look right. In a Column the list stops
 * where the header ends, so there is always an edge there; painting that edge to match only moves
 * the problem. Floating the header lets the list run the full height of the glass and dissolve
 * under the status bar, which is the only arrangement with no line in it at all.
 *
 * The header's height is MEASURED rather than passed in. Every screen's header is a different
 * size -- a bar, a bar plus tabs, a bar plus a search field -- and a hardcoded number would be
 * wrong on most of them and silently wrong when one of them changed.
 *
 * [content] receives the padding its scroll container must leave at the top: the system inset plus
 * whatever the header turned out to be. That has to go on the container's CONTENT padding, not on
 * the container itself, or the list stops at the header again.
 */
@Composable
fun FloatingHeaderLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val topInset = LocalContentTopInset.current
    var headerHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .fadingTopEdge(topInset + headerHeight)
        ) {
            content(topInset + headerHeight)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .zIndex(1f)
                .padding(top = topInset)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
        ) {
            header()
        }
    }
}
