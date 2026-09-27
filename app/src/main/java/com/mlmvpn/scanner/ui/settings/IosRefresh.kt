package com.mlmvpn.scanner.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pull-to-refresh, iOS style: pull the page down past a point, let go, and it holds open on a small
 * spinner while the page reloads, then springs shut.
 *
 * Written here rather than taken from a library because there is none at this Compose version --
 * material3 1.1.2 has no pull-to-refresh, and the one in Material 2 would be the only Material 2
 * component in the app and looks like Android, not like the rest of these screens. It is also the
 * only way to share the gesture with [IosScreen], which already owns the downward pull for its title.
 *
 * Works on anything that scrolls: attach [connection] with `Modifier.nestedScroll` to the scrolling
 * container and draw [IosRefreshIndicator] above it.
 */
@Stable
class IosRefreshState internal constructor(
    private val scope: CoroutineScope,
    private val maxPx: Float,
    private val holdPx: Float,
    private val onRefresh: State<(suspend () -> Unit)?>,
) {
    /** How far the page is pulled past its top, in pixels. */
    var pull by mutableFloatStateOf(0f)
        private set

    /** True from the moment the pull is released past the hold point until the reload is done. */
    var refreshing by mutableStateOf(false)
        private set

    /** 0..1 on the way to the point where letting go refreshes. */
    val progress: Float get() = if (holdPx > 0f) (pull / holdPx).coerceIn(0f, 1f) else 0f

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Scrolling back up takes the pull back first.
            if (available.y < 0f && pull > 0f && !refreshing) {
                val used = minOf(-available.y, pull)
                pull -= used
                return Offset(0f, -used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Only a finger pulls. A fling that reaches the top must not open the spinner by itself.
            if (available.y > 0f && source == NestedScrollSource.Drag && !refreshing) {
                val room = 1f - (pull / maxPx)
                pull = (pull + available.y * 0.5f * room).coerceIn(0f, maxPx)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pull <= 0f || refreshing) return Velocity.Zero
            if (pull >= holdPx && onRefresh.value != null) start() else settle(0f)
            return available
        }
    }

    private fun start() {
        val action = onRefresh.value ?: return
        refreshing = true
        scope.launch {
            settle(holdPx)
            try {
                action()
            } catch (e: Throwable) {
                // A failed reload leaves the page as it was; the screen reports its own errors.
            } finally {
                refreshing = false
                settle(0f)
            }
        }
    }

    private suspend fun settle(target: Float) {
        animate(pull, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
            pull = v
        }
    }
}

@Composable
fun rememberIosRefreshState(onRefresh: (suspend () -> Unit)?): IosRefreshState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val current = rememberUpdatedState(onRefresh)
    return remember(density) {
        with(density) { IosRefreshState(scope, 110.dp.toPx(), 64.dp.toPx(), current) }
    }
}

/** The gap the pull opens, with the spinner in it: filling as it is pulled, turning while it reloads. */
@Composable
fun IosRefreshIndicator(state: IosRefreshState, modifier: Modifier = Modifier) {
    val height = with(LocalDensity.current) { state.pull.toDp() }
    if (height <= 0.5.dp && !state.refreshing) return
    Box(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        if (state.refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Ios.SecondaryLabel,
                strokeWidth = 2.dp,
            )
        } else if (height > 12.dp) {
            CircularProgressIndicator(
                progress = state.progress,
                modifier = Modifier.size(22.dp),
                color = Ios.SecondaryLabel,
                strokeWidth = 2.dp,
            )
        }
    }
}
