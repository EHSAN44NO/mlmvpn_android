package com.mlmvpn.scanner.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the screen is on, for the background work that only pays off while someone can see it.
 *
 * A tunnel left connected spends most of its life in a pocket. Work done there -- a speed meter
 * nobody reads, a route re-check nobody is waiting for, a wake lock held for a download nobody
 * started -- is what turned "connected all day" into "battery gone by noon". The services and
 * engines ask this one object instead of each registering a receiver of their own.
 *
 * One receiver for the whole process, registered on first use against the application context and
 * kept for the life of the process: the broadcasts it listens for are only ever delivered to a
 * receiver registered at runtime, and two of them cost nothing between screen changes.
 */
object ScreenState {

    private val _on = MutableStateFlow(true)

    /** True while the screen is on. Starts true, so nothing is held back before the first read. */
    val on: StateFlow<Boolean> = _on

    /** [SystemClock.elapsedRealtime] of the last screen-off, or 0 while the screen is on. */
    @Volatile
    var offSince: Long = 0L
        private set

    @Volatile
    private var receiver: BroadcastReceiver? = null

    /** Starts watching, once per process. Safe to call from any thread, as often as wanted. */
    fun watch(context: Context) {
        if (receiver != null) return
        synchronized(this) {
            if (receiver != null) return
            val app = context.applicationContext
            set(interactive(app))
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> set(false)
                        Intent.ACTION_SCREEN_ON -> set(true)
                    }
                }
            }
            runCatching {
                app.registerReceiver(r, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                })
                receiver = r
            }
        }
    }

    /**
     * The screen right now. Asks the system rather than trusting the last broadcast, so a caller
     * that runs before [watch] -- or in the instant between the screen changing and the broadcast
     * arriving -- still gets the truth.
     */
    fun isOn(context: Context): Boolean {
        watch(context)
        val now = interactive(context.applicationContext)
        if (now != _on.value) set(now)
        return now
    }

    /** How long the screen has been off, or 0 while it is on. */
    fun offForMs(): Long = offSince.let { if (it == 0L) 0L else SystemClock.elapsedRealtime() - it }

    private fun set(on: Boolean) {
        if (on) offSince = 0L else if (offSince == 0L) offSince = SystemClock.elapsedRealtime()
        _on.value = on
    }

    private fun interactive(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)
}
