package com.mlmvpn.scanner.engines.flux

import android.util.Log

/**
 * FLUX's logcat trail, all under one tag (`adb logcat -s FLUX`), detailed enough to rebuild a
 * failed connect from a user's log: every step, every candidate and why it passed or failed.
 *
 * Never a credential: candidates print through [com.mlmvpn.scanner.engines.flux.core.model.FluxNode.redacted]
 * (protocol, transport, security and a hashed id). Server addresses are public and are printed.
 */
internal object FluxLog {
    const val TAG = "FLUX"

    fun i(msg: String) { runCatching { Log.i(TAG, msg) } }
    fun w(msg: String, e: Throwable? = null) {
        runCatching { if (e == null) Log.w(TAG, msg) else Log.w(TAG, "$msg: ${e.javaClass.simpleName}: ${e.message?.take(200)}") }
    }
}
