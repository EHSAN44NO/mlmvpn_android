package com.mlmvpn.core.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the tunnel back after a reboot.
 *
 * Only when the user asked for automatic reconnection and only when a tunnel
 * had actually been connected before, so a fresh install never starts a VPN on
 * its own. Android's own always-on VPN setting covers the same ground with a
 * stronger guarantee, but it is off by default and most people never find it.
 *
 * The VPN consent granted earlier survives a reboot, so no dialog is needed and
 * none could be shown from here anyway.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TunnelPreferences(context).autoReconnect) return

        // An intent with no action is what the service treats as "the system
        // started you" - it reads back the last configuration itself, which is
        // exactly the behaviour wanted here.
        runCatching {
            context.startForegroundService(Intent(context, TunnelVpnService::class.java))
        }
    }
}
