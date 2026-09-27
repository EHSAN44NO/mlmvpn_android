package com.mlmvpn.scanner.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a running IP scan alive while the app is in the background.
 *
 * The scan itself lives in [ScannerManager]'s own coroutine scope and is not moved here -- the
 * screen reads its state directly and that is the right shape for it. What this service adds is
 * the only thing a plain scope cannot have: a reason for Android not to freeze the process.
 *
 * Without it a scan that runs for several minutes -- which is the normal case on a blocked network
 * -- was subject to every background restriction the platform has. The app is not "in use" while
 * the user is reading something else, so on many OEM builds its threads get frozen and its sockets
 * dropped; the scan then either stalls for as long as the screen is off or, worse, resumes into a
 * network that is no longer there and marks the rest of the address space dead. Neither failure
 * announces itself, and both look like a scan that simply found nothing.
 *
 * The notification is the other half of the deal, and it is not a formality: a process holding the
 * connection open for minutes should say so, and the user should be able to see the progress and
 * stop it from wherever they are rather than having to find the tab again.
 *
 * It stops itself the moment the scan does; the only stateful thing it owns is a wake lock.
 */
class IpScanService : Service() {

    companion object {
        private const val CHANNEL_ID = "ip_scan_channel"
        private const val NOTIFICATION_ID = 5504

        const val ACTION_STOP = "ACTION_STOP_IP_SCAN"

        /** Safety net: a wake lock that outlives any plausible scan is a battery bug, not a fix. */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /**
         * startForegroundService, not startService.
         *
         * From Android O a background start of a service that will call `startForeground` has to
         * use this form or the platform kills it. The 5-second deadline it imposes is met in
         * `onCreate`, which promotes the service before it does anything else.
         */
        fun start(context: Context) {
            val app = context.applicationContext
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    app, Intent(app, IpScanService::class.java)
                )
            }
        }

        /**
         * stopService, not a STOP intent.
         *
         * A STOP intent would START the service if it were not already up -- the notification
         * would flash on and off for a scan that had already ended.
         */
        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, IpScanService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(S(R.string.scanner_phase_ready), null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        acquireWakeLock()
        observeScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScannerManager.stopScan()
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /**
     * Mirror the scan into the notification, and die with it.
     *
     * Polling rather than collecting the flows: the stats update on nearly every probe, which on a
     * four-thousand-address sweep is thousands of notification rebuilds a minute for a line of
     * text nobody can read that fast. Once a second is what the user can actually perceive.
     */
    private fun observeScan() {
        monitorJob = serviceScope.launch {
            // A short grace period at the start: the service is launched alongside the scan and
            // may win the race, and stopping immediately because `isScanning` has not flipped yet
            // would leave the scan unprotected for the whole run.
            delay(1500)
            while (isActive) {
                if (!ScannerManager.isScanning.value) {
                    stopSelfCleanly()
                    return@launch
                }
                val stats = ScannerManager.stats.value
                val paused = ScannerManager.pauseMessage.value
                val text = paused ?: S(
                    R.string.ip_scan_notification_progress,
                    stats.probed,
                    stats.total,
                    stats.healthy,
                    stats.target,
                )
                val progress = if (paused != null) null else stats.probed to stats.total
                try {
                    NotificationManagerCompat.from(this@IpScanService)
                        .notify(NOTIFICATION_ID, buildNotification(text, progress))
                } catch (e: SecurityException) {
                    // Notification permission was revoked mid-scan; the scan carries on regardless.
                }
                delay(1000)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mlmvpn:ipscan").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            // A scan without a wake lock is worse, not broken. Never fail the scan over this.
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        wakeLock = null
    }

    private fun stopSelfCleanly() {
        monitorJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                S(R.string.ip_scan_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = S(R.string.ip_scan_notification_channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Pair<Int, Int>?): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, IpScanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(S(R.string.scanner_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_scanner)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Stopping from the shade matters more here than on most notifications: the scan is
            // holding the whole connection, and the user may notice that from another app.
            .addAction(0, S(R.string.scanner_stop), stopIntent)

        if (contentIntent != null) builder.setContentIntent(contentIntent)

        if (progress != null && progress.second > 0) {
            builder.setProgress(progress.second, progress.first, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
