package com.mlmvpn.scanner.crash

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.mlmvpn.scanner.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sends the crash reports waiting in the outbox, from the system's job scheduler.
 *
 * Scheduled by the crash handler in the moment before the process dies, so a report leaves within
 * minutes of a crash whether or not the app is ever opened again -- the launch dialog it replaces
 * only ever reached people who came back, landed on the home screen and tapped Send. Until the
 * collector (or the pool) says a report was filed, the job finishes asking to be rescheduled, and
 * the scheduler retries with exponential backoff, across reboots, whenever there is a network.
 */
class CrashUploadJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val done = runCatching { CrashReporter.flushQueue(applicationContext) }
                .onFailure { Log.w(TAG, "flush failed: ${it.message}") }
                .getOrDefault(false)
            jobFinished(params, !done)
        }
        return true
    }

    /** Stopped by the system (the network went away): try again later. */
    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CrashUploadJob"
        private const val JOB_ID = 0x0C7A5E

        /**
         * Makes sure a run is coming, as soon as there is a network. Safe from a dying process.
         *
         * Never replaces a job already pending: scheduling an existing id stops it if it is running,
         * and the process the job starts in runs CrashReporter.install, which calls this.
         */
        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, CrashUploadJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    // Survives a reboot (RECEIVE_BOOT_COMPLETED is already held for the boot receiver).
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            } catch (t: Throwable) {
                Log.w(TAG, "could not schedule: ${t.message}")
            }
        }
    }
}
