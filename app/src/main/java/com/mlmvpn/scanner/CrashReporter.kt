package com.mlmvpn.scanner

import com.mlmvpn.scanner.crash.CrashClient
import com.mlmvpn.scanner.crash.CrashText
import com.mlmvpn.scanner.crash.CrashUploadJob
import com.mlmvpn.scanner.utils.SecretRedactor
import android.app.Application
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash capture, and delivery of what it captured.
 *
 * Three kinds of failure close this app, and they need different handling:
 *
 *   - **JVM crashes** — an uncaught Kotlin/Java exception. The process dies immediately
 *     afterwards, so every one is written to a file under `files/crashlogs/` first, with the
 *     stack and the last breadcrumbs.
 *   - **Native crashes** (SIGSEGV, SIGABRT, fdsan, FORTIFY) — these never reach a Java handler.
 *     On the next launch the system's record of the death (ApplicationExitInfo) is turned into a
 *     report of its own: the signal, the library, the abort message, the readable part of the
 *     tombstone, and -- through [note] and the process state summary -- the version and the last
 *     thing the app was doing when it died.
 *   - **ANRs** — the system kills a frozen app. The same: the next launch writes a report from the
 *     exit record, with the thread dump.
 *
 * ## Where a report goes
 *
 * Into an outbox, and from there to the crash collector (worker-src/crash), with the Quick Connect
 * pool's `/crash` as the fallback -- see [CrashClient]. A JVM crash schedules [CrashUploadJob] in
 * the moment before the process dies, so its report leaves within minutes whether or not the app
 * is opened again; a report stays in the outbox until one of the two says it was filed.
 *
 * Automatically, unless the user turned that off (Settings → Crash report). The app's owner asked
 * for every crash to reach them: the launch dialog this replaced reached only people who came
 * back, landed on the home screen and tapped Send, which is why crashes kept being described in
 * words and never arrived as stacks. What is sent is the report text below -- the error, the
 * breadcrumbs, the device model and the app version, passed through [SecretRedactor] -- and
 * nothing from configs or traffic. The first time it happens the user is told, once, and shown
 * where to turn it off; with it off, the old dialog asks every time.
 *
 * The whole path across the app, the collector, the pool, D1, KV and the private repository is in
 * `docs/CRASH-REPORTS.md`. Read it before changing anything here that a Worker also depends on --
 * [signatureOf] in particular, which decides what counts as "the same bug".
 */
object CrashReporter {

    const val TAG = "MLMCrash"

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** A report of one crash of any kind: the thing that gets sent. */
    private const val CRASH_PREFIX = "crash-"
    /** How earlier runs ended, from ApplicationExitInfo, for the shared text. */
    private const val LAST_EXIT_PREFIX = "lastexit-"
    /** A VM shutdown note from the shutdown hook, written by every deliberate exit. */
    private const val EXIT_PREFIX = "exit-"

    /** Crash reports kept on the phone, newest first. */
    private const val KEEP_CRASHES = 20
    /** Exit histories and shutdown notes kept, each. */
    private const val KEEP_NOTES = 5
    /** At most this many reports go up in one pass; the rest wait for the next. */
    private const val MAX_PER_FLUSH = 5
    /** A report nobody could deliver in this long is given up on. */
    private const val OUTBOX_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    /** An ANR's thread dump or a native tombstone can run to megabytes; a report needs its top. */
    private const val MAX_TRACE_CHARS = 24_000

    private lateinit var logDir: File
    private lateinit var appContext: android.content.Context
    private var appVersion: String = "?"
    private var activityManager: android.app.ActivityManager? = null

    /** Rolling record of what the app was last doing, printed into every crash report. */
    private val breadcrumbs = ArrayDeque<String>()
    private const val MAX_BREADCRUMBS = 40

    fun install(app: Application) {
        logDir = File(app.filesDir, "crashlogs").apply { mkdirs() }
        appContext = app.applicationContext
        appVersion = try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (t: Throwable) { "?" }
        activityManager = app.getSystemService(Application.ACTIVITY_SERVICE) as? android.app.ActivityManager
        prune()
        publishState("start")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildReport(thread, error)
                // Logcat first: if writing the file fails for any reason, the report is still
                // in the buffer the stress test is being watched through.
                Log.e(TAG, text)
                val file = writeReport(text)
                // Handed to the system before this process dies: the job runs in a fresh one, as
                // soon as there is a network, whether or not anyone opens the app again.
                if (autoSend()) {
                    enqueue(file)
                    CrashUploadJob.schedule(appContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "failed to record crash: ${t.message}")
            }
            // Always hand back to the platform handler so the process still dies the normal
            // way — swallowing this leaves a half-dead app that behaves far worse than a crash.
            previous?.uncaughtException(thread, error)
        }

        // The app has been dying with ApplicationExitInfo reason=EXIT_SELF status=255 and no
        // signal, no FORTIFY line and no Java exception — that is a deliberate process exit,
        // not a crash. A shutdown hook runs for System.exit()/Runtime.exit() but NOT for a
        // native exit() or a signal, so this both names the caller and, by staying silent,
        // tells us the exit came from native code instead.
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                val who = Thread.currentThread().stackTrace.joinToString("\n  ") { it.toString() }
                val others = Thread.getAllStackTraces().entries.joinToString("\n") { (t, st) ->
                    "-- ${t.name} --\n  " + st.take(12).joinToString("\n  ") { it.toString() }
                }
                Log.e(TAG, "VM SHUTTING DOWN (deliberate exit)\nhook stack:\n  $who\n\nthreads:\n$others")
                try {
                    File(logDir, "$EXIT_PREFIX${stamp.format(Date())}.txt")
                        .writeText(SecretRedactor.redact("VM shutdown\n\nhook:\n  $who\n\nthreads:\n$others"))
                } catch (_: Throwable) {}
            })
        } catch (t: Throwable) {
            Log.w(TAG, "could not register shutdown hook: ${t.message}")
        }

        // The exit history and the outbox are the package's, not a process's: the main process
        // reads and sends, so the tunnel's process starting up does not do it all again.
        if (isMainProcess(app)) {
            reportPreviousExit(app)
            try {
                queueUnsent(app)
                if (queued().isNotEmpty()) CrashUploadJob.schedule(app)
            } catch (t: Throwable) {
                // A crash reporter must never be the crash.
                Log.w(TAG, "could not queue reports: ${t.message}")
            }
        }

        Log.i(TAG, "crash reporter installed; reports in ${logDir.absolutePath}")
    }

    private fun isMainProcess(app: Application): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName() == app.packageName
        } else {
            val pid = android.os.Process.myPid()
            activityManager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
                ?.let { it == app.packageName } ?: true
        }
    } catch (t: Throwable) {
        true
    }

    /** Distinguishes reports written inside the same second. See [writeReport]. */
    private val reportSequence = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Write one crash report, safely against the case that matters most: several threads dying
     * at once.
     *
     * The first version formatted a filename from [stamp] alone, which has one-second
     * resolution, and wrote straight to it. A relay bug in this app killed six threads inside
     * three milliseconds; all six handlers built the same path, all six truncated it, and the
     * file that survived was **zero bytes** — the report was destroyed by exactly the kind of
     * crash it was there to explain. [stamp] is also a `SimpleDateFormat`, which is not
     * thread-safe, so the six were racing on its internal calendar as well.
     *
     * So: the name carries a counter and the thread (or [tag]), formatting is serialised, and the
     * bytes go to a temporary file that is renamed into place only once it is complete. A
     * half-written report is worse than none, because it looks like a report.
     */
    @Synchronized
    private fun writeReport(raw: String, tag: String = Thread.currentThread().name): File {
        val text = SecretRedactor.redact(raw)
        val name = "$CRASH_PREFIX${stamp.format(Date())}-${reportSequence.incrementAndGet()}" +
            "-${tag.take(24).replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt"
        val target = File(logDir, name)
        val temp = File(logDir, "$name.part")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // A rename can only fail here for something like a full disk. Falling back to a
            // direct write is still better than losing the report.
            target.writeText(text)
            temp.delete()
        }
        return target
    }

    /**
     * Every report on disk, newest first, for the "send crash report" flow.
     *
     * Partial writes are excluded: a `.part` file is one that never finished, and handing a
     * truncated stack to someone reading it wastes the one chance the report had.
     */
    fun reports(): List<File> =
        if (!this::logDir.isInitialized) emptyList()
        else logDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    private fun crashReports(): List<File> = reports().filter { it.name.startsWith(CRASH_PREFIX) }

    /**
     * How many things on disk are worth sending: crash reports, and the records of runs that
     * ended badly. Not the shutdown notes every deliberate restart writes -- Settings counted
     * those as crashes.
     */
    fun reportCount(): Int =
        reports().count { it.name.startsWith(CRASH_PREFIX) || it.name.startsWith(LAST_EXIT_PREFIX) }

    /**
     * One text blob of the most recent reports, ready to be shared.
     *
     * Newest first and capped, because this is going into a message box: the newest report is
     * the one being asked about, and no one pastes half a megabyte into Telegram.
     *
     * The reports come first and the app's own notes after them, one of each. It used to be simply
     * the newest three files, and since an exit history was written on every launch, those three
     * were nearly always exit histories: the reports users sent said a JVM crash had happened
     * and left out the stack that would have said where.
     */
    fun collect(limit: Int = 3, maxChars: Int = 60_000): String {
        val all = reports()
        val files = all.filter { it.name.startsWith(CRASH_PREFIX) }.take(limit) + listOfNotNull(
            all.firstOrNull { it.name.startsWith(LAST_EXIT_PREFIX) },
            all.firstOrNull { it.name.startsWith(EXIT_PREFIX) },
        )
        if (files.isEmpty()) return ""
        val body = buildString {
            appendLine("MLM VPN crash reports")
            appendLine("app     : $appVersion")
            appendLine("device  : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            lastExitSummary?.let { appendLine("last exit: $it") }
            appendLine()
            files.forEach { f ->
                appendLine("========== ${f.name} ==========")
                appendLine(runCatching { f.readText() }.getOrElse { "(unreadable: ${it.message})" })
                appendLine()
            }
        }
        val clean = SecretRedactor.redact(body)
        return if (clean.length <= maxChars) clean else clean.take(maxChars) + "\n… (truncated)"
    }

    /**
     * The one line that identifies a crash: the exception, its message, and the first frame of
     * ours.
     *
     * "Ours" matters. The top frame of a Compose crash is nearly always inside the Compose
     * runtime and identical across completely unrelated bugs, so grouping on it would file every
     * UI crash in the app under one heading. The first `com.mlmvpn` frame is the line a developer
     * would actually open.
     *
     * In a JVM report the exception is the line after `--- stack ---`. It used to be the first
     * line anywhere that said "Exception" or "Error", and a breadcrumb that happened to say either
     * became the bug's title. Native-crash and ANR reports have no stack section; their `error:`
     * headline ([CrashText]) is the line.
     */
    fun signatureOf(text: String): String {
        val lines = text.lines()
        val stackAt = lines.indexOfFirst { it.trim() == "--- stack ---" }
        val stack = if (stackAt >= 0) lines.drop(stackAt + 1) else lines
        val cause = (if (stackAt >= 0) stack.firstOrNull { it.isNotBlank() } else null)?.trim()
            ?: lines.firstOrNull { it.startsWith("error") || it.contains("Exception") || it.contains("Error") }
                ?.trim()
                .orEmpty()
        val frame = stack.map { it.trim() }.firstOrNull { it.startsWith("at com.mlmvpn") }.orEmpty()
        return listOf(cause, frame).filter { it.isNotBlank() }.joinToString("  |  ").take(300)
    }

    // ---------------------------------------------------------------------------- the outbox
    //
    // A marker file per report, in a directory: the crash handler of whichever process died adds
    // one, the upload job removes it, and the filesystem is the one store every process of the
    // app sees the same way (SharedPreferences are cached per process).

    private fun outbox() = File(logDir, "outbox").apply { mkdirs() }
    private fun sentDir() = File(logDir, "sent").apply { mkdirs() }

    private fun enqueue(report: File) {
        try {
            if (!File(sentDir(), report.name).exists()) File(outbox(), report.name).createNewFile()
        } catch (t: Throwable) {
            Log.w(TAG, "could not queue ${report.name}: ${t.message}")
        }
    }

    /** The reports waiting to be delivered, oldest first. */
    private fun queued(): List<File> {
        if (!this::logDir.isInitialized) return emptyList()
        val now = System.currentTimeMillis()
        return outbox().listFiles().orEmpty().mapNotNull { marker ->
            val report = File(logDir, marker.name)
            if (!report.isFile || now - report.lastModified() > OUTBOX_MAX_AGE_MS) {
                marker.delete()
                null
            } else {
                report
            }
        }.sortedBy { it.lastModified() }
    }

    private fun markSent(report: File) {
        runCatching { File(sentDir(), report.name).createNewFile() }
        File(outbox(), report.name).delete()
    }

    private val flushLock = Mutex()

    /**
     * Delivers what is waiting in the outbox. True when nothing is left waiting.
     *
     * Stops at the first report that does not go through: when the network or both servers are
     * down, the rest would fail the same way, and the job's backoff is the right pace to retry.
     */
    suspend fun flushQueue(context: android.content.Context): Boolean = flushLock.withLock {
        for (report in queued().take(MAX_PER_FLUSH)) {
            val text = runCatching { report.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            if (text == null) {
                File(outbox(), report.name).delete()
                continue
            }
            val ok = CrashClient.send(
                context,
                // Reports written before redaction existed are cleaned here too.
                summary = SecretRedactor.redact(signatureOf(text)),
                body = SecretRedactor.redact(text),
                kind = CrashText.kindOf(text),
            )
            if (!ok) break
            markSent(report)
        }
        queued().isEmpty()
    }

    /**
     * With automatic sending on: every report the user has not been asked about goes into the
     * outbox -- written by a native crash or an ANR on this launch, or by a JVM crash whose own
     * handler could not queue it.
     */
    private fun queueUnsent(context: android.content.Context) {
        if (!autoSend()) return
        val prefs = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
        val offeredUpTo = prefs.getLong("offered_up_to", 0L)
        val fresh = crashReports().filter { it.lastModified() > offeredUpTo }
        if (fresh.isEmpty()) return
        fresh.forEach { enqueue(it) }
        prefs.edit()
            .putLong("offered_up_to", fresh.maxOf { it.lastModified() })
            .putLong("auto_reported_at", System.currentTimeMillis())
            .apply()
    }

    /**
     * Sends, now, what the user agreed to in the launch dialog (automatic sending off). True when
     * it was all delivered; whatever was not stays queued, and the job keeps trying.
     */
    suspend fun upload(context: android.content.Context): Boolean {
        val offeredUpTo = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            .getLong("offered_up_to", 0L)
        // Every crash since the last offer, not only the newest: two in a row are as often two
        // bugs as one, and the dialog asked about "a crash" without saying which.
        val crashes = crashReports()
        crashes.filter { it.lastModified() > offeredUpTo }.ifEmpty { crashes.take(1) }.forEach { enqueue(it) }
        val done = flushQueue(context)
        if (!done) CrashUploadJob.schedule(context)
        return done
    }

    // ---------------------------------------------------------------------------- the choice

    /** Present when the user turned automatic sending off. A file, so every process sees it. */
    private fun manualMarker() = File(logDir, ".manual-send")

    /** Whether crash reports are sent without asking. On unless the user turned it off. */
    fun autoSend(): Boolean = this::logDir.isInitialized && !manualMarker().exists()

    fun setAutoSend(on: Boolean) {
        if (!this::logDir.isInitialized) return
        if (on) {
            manualMarker().delete()
        } else {
            runCatching { manualMarker().createNewFile() }
            // Queued without asking, so withdrawn with the permission.
            outbox().listFiles().orEmpty().forEach { it.delete() }
        }
    }

    /**
     * True once: the first launch after a report was sent automatically, so the user hears that
     * it happened and where to turn it off.
     */
    fun noticeDue(context: android.content.Context): Boolean {
        if (!autoSend()) return false
        val prefs = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("auto_reported_at", 0L) > 0L && !prefs.getBoolean("auto_notice_shown", false)
    }

    fun markNoticeShown(context: android.content.Context) {
        context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("auto_notice_shown", true).apply()
    }

    /**
     * Hand the newest reports to whatever the user wants to send them with.
     *
     * Plain text in an ACTION_SEND rather than a file attachment: the destination is almost
     * always Telegram, where a pasted stack is readable in the chat and a .txt is one more tap
     * and a download for whoever has to read it. A FileProvider would also need a manifest entry
     * and a grant for every target app, which is a lot of moving parts for a support message.
     */
    fun share(context: android.content.Context) {
        val text = collect()
        if (text.isBlank()) return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "MLM VPN crash report")
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(send, null)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * The newest report the user has not been asked about, or null -- for the launch dialog, which
     * only asks when automatic sending is off.
     *
     * Tracked by timestamp rather than by a "shown" flag per file so that a burst of reports from
     * one crash prompts once, and so that clearing the marker cannot resurrect old prompts.
     */
    fun unreportedCrash(context: android.content.Context): File? {
        if (autoSend()) return null
        val newest = crashReports().firstOrNull() ?: return null
        val prefs = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
        return if (newest.lastModified() > prefs.getLong("offered_up_to", 0L)) newest else null
    }

    /** Remember that the user has been shown everything up to now, whatever they chose. */
    fun markOffered(context: android.content.Context) {
        context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            .edit().putLong("offered_up_to", System.currentTimeMillis()).apply()
    }

    /**
     * One-line summary of how the PREVIOUS run of this process ended, or null if it ended normally
     * (or the device is pre-API-30). Read once at startup; safe to read from the UI afterwards.
     */
    @Volatile var lastExitSummary: String? = null
        private set

    /**
     * Records that the app is about to kill itself on purpose (restartAppProcess). Without this
     * marker every deliberate relaunch looks identical to the bug we are chasing -- both surface
     * as ApplicationExitInfo REASON_EXIT_SELF -- and we cannot tell "the app restarted itself as
     * designed" apart from "the native core exited under us".
     */
    fun noteDeliberateExit(app: android.content.Context, why: String) {
        try {
            app.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE).edit()
                .putLong("deliberate_exit_at", System.currentTimeMillis())
                .putString("deliberate_exit_why", why)
                .commit()   // commit(), not apply(): the process dies milliseconds from now.
        } catch (t: Throwable) {
            Log.w(TAG, "could not record deliberate exit: ${t.message}")
        }
        Log.i(TAG, "DELIBERATE EXIT: $why")
    }

    /**
     * Asks the system how the previous runs actually died, writes that down, and turns every
     * crash it did not already have a report for -- native crashes and ANRs above all -- into one.
     *
     * ApplicationExitInfo carries the process NAME (main or the tunnel's), the importance at the
     * time of death (foreground vs cached), the system's description, the trace for native
     * crashes and ANRs, and the state summary this process published through [note]: its version
     * and the last thing it was doing.
     */
    private fun reportPreviousExit(app: Application) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val am = activityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 16)
            if (exits.isEmpty()) return

            val diag = app.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            val deliberateAt = diag.getLong("deliberate_exit_at", 0L)
            val deliberateWhy = diag.getString("deliberate_exit_why", null)
            // The system keeps a rolling list, so every launch used to write the same exits down
            // again -- one file per launch, which crowded the real crash stacks out of what users
            // sent and, through [prune], off the phone. Only exits not seen before count now.
            val seenUpTo = diag.getLong("exits_seen_up_to", 0L)
            val fresh = exits.filter { it.timestamp > seenUpTo }
            diag.edit()
                .remove("deliberate_exit_at").remove("deliberate_exit_why")
                .putLong("exits_seen_up_to", maxOf(seenUpTo, exits.maxOf { it.timestamp }))
                .apply()

            // Ours or the bug? If we marked a deliberate exit within a minute of it, it was ours.
            fun ours(e: android.app.ApplicationExitInfo) =
                deliberateAt > 0L && kotlin.math.abs(e.timestamp - deliberateAt) < 60_000

            val newest = exits[0]
            lastExitSummary = "exit: ${reasonName(newest.reason)}/${newest.status} " +
                "proc=${newest.processName.substringAfterLast(':', "main")} " +
                "imp=${newest.importance}" + if (ours(newest)) " (ours: $deliberateWhy)" else " (NOT ours)"
            Log.e(TAG, "LAST EXIT → $lastExitSummary")

            // Written down only when a run ended badly. Swiped from Recents, updated, reclaimed
            // while cached: that is how most runs end, and it says nothing about a bug.
            val bad = fresh.filter { noteworthy(it) && !ours(it) }
            if (bad.isEmpty()) return
            val traces = HashMap<android.app.ApplicationExitInfo, String>()
            val text = buildString {
                appendLine("=== how previous runs ended (newest first) ===")
                appendLine("recorded at : ${Date()}")
                if (deliberateAt > 0L) {
                    appendLine("NOTE: this app deliberately killed itself at ${Date(deliberateAt)} ($deliberateWhy)")
                }
                appendLine()
                fresh.forEach { e ->
                    appendLine("time       : ${Date(e.timestamp)}")
                    appendLine("process    : ${e.processName} (pid ${e.pid})")
                    appendLine("reason     : ${reasonName(e.reason)} (${e.reason})")
                    appendLine("status     : ${e.status}")
                    appendLine("importance : ${e.importance}")
                    appendLine("description: ${e.description}")
                    stateOf(e)?.let { appendLine("state      : $it") }
                    // Present for ANR/native-crash exits; this is the actual stack when there is one.
                    try {
                        e.traceInputStream?.use { s ->
                            val trace = readableTrace(e.reason, s.readBytes())
                            traces[e] = trace
                            appendLine("trace      :\n$trace")
                        }
                    } catch (_: Throwable) {}
                    appendLine("---")
                }
            }
            Log.e(TAG, text)
            File(logDir, "$LAST_EXIT_PREFIX${stamp.format(Date())}.txt").writeText(SecretRedactor.redact(text))

            bad.forEach { e -> reportFromExit(e, traces[e]) }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read exit reasons: ${t.message}")
        }
    }

    /**
     * A crash report of its own for an exit that left none: every native crash and ANR, a JVM
     * crash whose handler could not write (out of memory, out of disk), and the system killing the
     * app for being stuck at start-up or for using too much.
     */
    private fun reportFromExit(e: android.app.ApplicationExitInfo, trace: String?) {
        val kind = when (e.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
            android.app.ApplicationExitInfo.REASON_ANR -> "anr"
            android.app.ApplicationExitInfo.REASON_CRASH,
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "jvm"
            else -> return
        }
        // A JVM crash that wrote its own report needs no second one.
        if (e.reason == android.app.ApplicationExitInfo.REASON_CRASH &&
            crashReports().any { kotlin.math.abs(it.lastModified() - e.timestamp) < 30_000 }
        ) return

        val state = stateOf(e)
        val headline = when (kind) {
            "native" -> CrashText.nativeHeadline(e.processName, trace.orEmpty())
            "anr" -> CrashText.anrHeadline(e.processName, e.description)
            else -> "error: ${reasonName(e.reason)} (${CrashText.processLabel(e.processName)})" +
                (e.description?.takeIf { it.isNotBlank() }?.let { ": " + CrashText.normalize(it).take(160) } ?: "")
        }
        val text = buildString {
            appendLine(
                when (kind) {
                    "native" -> CrashText.NATIVE_HEADER
                    "anr" -> CrashText.ANR_HEADER
                    else -> CrashText.JVM_HEADER
                }
            )
            // The line the report is grouped by, first, ahead of anything that could look like one.
            appendLine(headline)
            appendLine("time      : ${Date(e.timestamp)}")
            appendLine("process   : ${e.processName} (pid ${e.pid})")
            appendLine("version   : ${state?.substringBefore('|')?.removePrefix("v=") ?: "? (reported by $appVersion)"}")
            appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            state?.substringAfter('|', "")?.takeIf { it.isNotBlank() }?.let { appendLine("last step : $it") }
            appendLine("reason    : ${reasonName(e.reason)} status ${e.status}, importance ${e.importance}")
            appendLine("described : ${e.description}")
            if (!trace.isNullOrBlank()) {
                appendLine()
                appendLine(if (kind == "native") "--- tombstone (readable parts) ---" else "--- trace ---")
                appendLine(trace)
            }
        }
        val file = writeReport(text, tag = kind + "-" + CrashText.processLabel(e.processName).removePrefix(":"))
        // Dated when it happened, not when it was found: the outbox and the dialog order by it.
        runCatching { file.setLastModified(maxOf(e.timestamp, 1L)) }
        if (autoSend()) enqueue(file)
    }

    /** What the process published through [publishState]: "v=<version>|<last step>", or null. */
    private fun stateOf(e: android.app.ApplicationExitInfo): String? = try {
        e.processStateSummary?.takeIf { it.isNotEmpty() }?.decodeToString()
    } catch (t: Throwable) {
        null
    }

    /**
     * An exit that can be a bug: a crash of either kind, an ANR, the system killing a process for
     * what it did, or a process ending itself with an error the app did not ask for.
     */
    private fun noteworthy(e: android.app.ApplicationExitInfo): Boolean = when (e.reason) {
        android.app.ApplicationExitInfo.REASON_CRASH,
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
        android.app.ApplicationExitInfo.REASON_ANR,
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        android.app.ApplicationExitInfo.REASON_SIGNALED,
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> true
        // tun2proxy's host exits(255) by design after every teardown (see Tun2proxyHostService);
        // anywhere else, a nonzero exit is native code ending the process under us.
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> e.status != 0 && !e.processName.endsWith(":tun")
        // Killed for memory while it was carrying the tunnel, not while it sat cached.
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY ->
            e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        else -> false
    }

    /**
     * An exit's trace as text, and only its top. An ANR's is a thread dump, text already, and can
     * run to megabytes; a native crash's is a binary tombstone, of which the readable runs --
     * the abort message, the backtrace's library and function names -- are the useful part.
     */
    private fun readableTrace(reason: Int, bytes: ByteArray): String {
        val text = if (reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
            CrashText.readableRuns(bytes)
        } else {
            bytes.decodeToString()
        }
        return if (text.length <= MAX_TRACE_CHARS) text else text.take(MAX_TRACE_CHARS) + "\n… (trace truncated)"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH(jvm)"
        5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"; else -> "UNKNOWN"
    }

    /**
     * Hands the system a line to keep with this process: its version and the last thing it was
     * doing. If the process then dies where no handler runs -- a native crash, an ANR -- the
     * system gives the line back with the record of the death, and [reportFromExit] puts it in the
     * report: "openTab home -> mae" is often the whole answer to where it happened.
     */
    private fun publishState(step: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            var bytes = "v=$appVersion|$step".toByteArray()
            // The system keeps at most 128 bytes.
            if (bytes.size > 127) bytes = bytes.copyOf(127)
            activityManager?.setProcessStateSummary(bytes)
        } catch (t: Throwable) {
            // Best effort: losing the line costs a little context, never the app.
        }
    }

    /**
     * Record what the user is doing. Cheap enough to call on every screen change or connect —
     * a string append and one call to the system, not I/O.
     */
    fun note(message: String) {
        val clean = SecretRedactor.redact(message)
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $clean"
        synchronized(breadcrumbs) {
            breadcrumbs.addLast(line)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
        publishState(clean)
        // Echoed to logcat under the same tag so it is visible in the run-up to a *native*
        // crash too, where the file below is never written.
        Log.i(TAG, "• $line")
    }

    private fun buildReport(thread: Thread, error: Throwable): String = buildString {
        appendLine(CrashText.JVM_HEADER)
        appendLine("time      : ${Date()}")
        appendLine("thread    : ${thread.name}")
        appendLine("version   : $appVersion")
        appendLine("device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE}")
        appendLine()
        appendLine("--- breadcrumbs (most recent last) ---")
        synchronized(breadcrumbs) { breadcrumbs.forEach { appendLine(it) } }
        appendLine()
        appendLine("--- stack ---")
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        appendLine(sw.toString())
    }

    /**
     * Keep the directory from growing without bound over a long stress run -- by kind. It used to
     * keep the newest thirty files of any kind, and the notes the app writes on its own outnumber
     * crashes many times over, so the stacks were the files that went.
     */
    private fun prune() {
        val files = logDir.listFiles()?.filter { it.isFile } ?: return
        fun keepNewest(n: Int, matches: (String) -> Boolean) = files.filter { matches(it.name) }
            .sortedByDescending { it.lastModified() }
            .drop(n)
            .forEach { it.delete() }
        keepNewest(KEEP_CRASHES) { it.startsWith(CRASH_PREFIX) && it.endsWith(".txt") }
        keepNewest(KEEP_NOTES) { it.startsWith(LAST_EXIT_PREFIX) }
        keepNewest(KEEP_NOTES) { it.startsWith(EXIT_PREFIX) }
        // A write the process died in the middle of; never renamed, never readable as a report.
        // Old ones only: another process of the app may be writing one this very moment.
        files.filter { it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > 60_000 }
            .forEach { it.delete() }
        // Queue and delivery markers of reports that are gone.
        listOf(File(logDir, "outbox"), File(logDir, "sent")).forEach { dir ->
            dir.listFiles().orEmpty().filter { !File(logDir, it.name).exists() }.forEach { it.delete() }
        }
    }
}
