package com.mlmvpn.scanner.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from another screen for the V2Ray tab to open on something in particular.
 *
 * This exists because of how the app hosts its tabs: every visited tab stays composed and is moved
 * off-screen with `offset(x = 10000.dp)` rather than being torn down. A parameter passed into
 * [NodesTab] would therefore only be read the first time it was ever shown, and a tab the user has
 * already visited would ignore it -- which is precisely the case that matters, since the handoff
 * happens after they have been in V2Ray at least once.
 *
 * A one-shot flow instead: the sender posts a request, the tab consumes it on its next composition
 * and clears it. Consuming rather than merely reading is what stops the tab from re-focusing the
 * same folder every time the user comes back to it later.
 *
 * Kept deliberately small. It carries where to look, not what to do -- nothing here starts a
 * connection, because the connect path (VPN consent, then the service intent) lives in [NodesTab]
 * and a second copy of it is exactly the duplication this app keeps having to delete.
 */
object NodesFocus {

    /**
     * @param groupTitle the manual folder to select, e.g. the free-config group.
     * @param nodeId which config to make the active one, so the connect button is already aimed
     *   at something and the user is one tap from connected rather than one search plus one tap.
     */
    data class Request(val groupTitle: String?, val nodeId: String?)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun focus(groupTitle: String?, nodeId: String? = null) {
        _pending.value = Request(groupTitle, nodeId)
    }

    /** Called by the tab once it has applied the request. */
    fun consume() {
        _pending.value = null
    }
}
