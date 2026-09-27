package com.mlmvpn.scanner.lan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mlmvpn.scanner.MainActivity
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.S

// =================================================================================================
// The address, in the shade, while sharing is on.
//
// The whole point of this feature is a value that has to travel to another machine, and the user
// is usually holding the phone in one hand and typing on the other device with the other. Making
// them come back into the app and navigate to a screen to re-read an IP address is the one
// interaction the rest of this feature is built to avoid, so the address lives in a notification
// with a copy button on it.
//
// "Stop sharing" is there for the same reason from the other direction: a user who has finished
// lending their connection should not have to open the app to stop lending it.
// =================================================================================================

object LanNotification {

    private const val CHANNEL_ID = "lan_sharing"
    private const val NOTIFICATION_ID = 4711

    const val ACTION_COPY = "com.mlmvpn.scanner.lan.COPY_ADDRESS"
    const val ACTION_STOP = "com.mlmvpn.scanner.lan.STOP_SHARING"
    private const val EXTRA_ADDRESS = "address"

    @Volatile
    private var shownFor: String? = null

    /**
     * Post, update or clear the notification to match [status].
     *
     * Called from the same poll that drives the setup server, for the same reason: one place
     * deciding, so the shade and the screen cannot disagree.
     *
     * The `shownFor` guard is not an optimisation. Re-posting an identical notification every two
     * seconds makes it jump to the top of the shade each time and, on some launchers, re-alerts —
     * which turns a helpful reminder into something the user swipes away and mutes.
     */
    fun sync(context: Context, status: LanStatus) {
        val endpoint = status.proxyEndpoint
        if (!status.ready || endpoint == null) {
            clear(context)
            return
        }
        if (shownFor == endpoint) return
        show(context, status, endpoint)
        shownFor = endpoint
    }

    private fun show(context: Context, status: LanStatus, endpoint: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        ensureChannel(manager)

        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // MUTABLE is not an option here and the extra is not user data: the receiver needs the
        // address to put on the clipboard, and a copy action that copies nothing is worse than
        // no copy action. FLAG_IMMUTABLE with the value baked into the extra is the supported
        // shape for exactly this.
        val copy = PendingIntent.getBroadcast(
            app,
            1,
            Intent(app, LanNotificationReceiver::class.java)
                .setAction(ACTION_COPY)
                .putExtra(EXTRA_ADDRESS, endpoint),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getBroadcast(
            app,
            2,
            Intent(app, LanNotificationReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val active = status.clients.count { it.connections > 0 }
        val body = if (active > 0) {
            S(R.string.lan_notif_body_active, endpoint, active)
        } else {
            S(R.string.lan_notif_body_waiting, endpoint)
        }

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(S(R.string.lan_notif_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .addAction(0, S(R.string.lan_notif_copy), copy)
            .addAction(0, S(R.string.lan_notif_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun clear(context: Context) {
        shownFor = null
        val manager = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                S(R.string.lan_notif_channel),
                // LOW: this is a reference the user comes back to, not an event. At DEFAULT it
                // would make a sound the first time sharing starts, which is noise for something
                // the user just did deliberately.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = S(R.string.lan_notif_channel_desc)
                setShowBadge(false)
            }
        )
    }
}

/** Handles the two buttons on [LanNotification]. */
class LanNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LanNotification.ACTION_COPY -> {
                val address = intent.getStringExtra("address").orEmpty()
                if (address.isEmpty()) return
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("proxy", address))
                // Android 13+ shows its own clipboard confirmation, so a toast there would be a
                // second one saying the same thing.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, S(R.string.lanweb_copied), Toast.LENGTH_SHORT).show()
                }
            }

            LanNotification.ACTION_STOP -> {
                NetworkSettings.setAllowLan(context, false)
                LanSetupServer.shutdown()
                LanNotification.clear(context)
            }
        }
    }
}
