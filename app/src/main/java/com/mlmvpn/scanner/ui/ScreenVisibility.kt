package com.mlmvpn.scanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the screen this composable belongs to is the one being shown.
 *
 * AppScreen keeps every tab it has opened composed and parks the hidden ones off to the side (see
 * its feature host), so that a sweep or a half-filled form survives a trip to another tab. The
 * price is that everything those tabs run keeps running: a clock ticking once a second, a log
 * polled every two, an animation asking for every frame -- for a tab nobody can see, and, because
 * a coroutine's `delay` does not stop with the activity, with the app in the background as well,
 * for as long as the tunnel keeps the process alive. AppScreen provides false here for every
 * parked tab.
 */
val LocalTabVisible = compositionLocalOf { true }

/**
 * True while this screen is really on show: its tab is the active one and the app is in front.
 * For deciding whether to compose something that animates for as long as it exists.
 */
@Composable
fun rememberOnScreen(): State<Boolean> {
    val tabVisible = LocalTabVisible.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val started = remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            started.value = true
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                started.value = false
            }
        }
    }
    val startedNow by started
    return rememberUpdatedState(tabVisible && startedNow)
}

/**
 * [LaunchedEffect] for work that only means something while the screen can be seen -- a clock, a
 * live readout, a polled log. [block] runs while this screen is on show, is cancelled when its tab
 * is parked or the app goes to the background, and starts again from the top when it comes back,
 * so it must read whatever it shows afresh rather than count on its own state (an elapsed time
 * works from a start stamp, not from ticks it added up).
 */
@Composable
fun LaunchedWhileVisible(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val tabVisible = LocalTabVisible.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(block)
    LaunchedEffect(tabVisible, lifecycle, *keys) {
        if (!tabVisible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { current() }
    }
}

/**
 * [StateFlow.value] as state, collected only while the screen is on show (see [LaunchedWhileVisible]).
 * The last value stays on screen meanwhile and is brought up to date the moment it is shown again;
 * a flow shared `WhileSubscribed` upstream then stops doing its work while nobody is looking.
 */
@Composable
fun <T> StateFlow<T>.collectWhileVisible(): State<T> {
    val state = remember(this) { mutableStateOf(value) }
    LaunchedWhileVisible(this) { collect { state.value = it } }
    return state
}
