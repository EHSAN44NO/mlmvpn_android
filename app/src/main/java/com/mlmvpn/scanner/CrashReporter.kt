package com.mlmvpn.scanner

import com.mlmvpn.scanner.utils.SecretRedactor
import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash capture for stress-testing.
 *
 * Two different kinds of crash have shown up on this app, and they need different handling:
 *
 *   - **JVM crashes** — an uncaught Kotlin/Java exception. The platform prints these, but the
 *     process dies immediately afterwards, so if logcat wasn't attached at that moment the
 *     stack is gone. Here every one is also written to a file under `files/crashlogs/`, which
 *     survives the process death and can be pulled later.
 *   - **Native crashes** (SIGSEGV, SIGABRT, fdsan, FORTIFY) — these never reach a Java handler
 *     at all. Nothing in-process can catch them; the backtrace only exists in logcat and in
 *     the tombstone. What this class contributes there is a *marker*: the last thing the app
 *     was doing is logged under a single tag, so the logcat line right before the `F/libc`
 *     abort names the screen and action instead of leaving it to guesswork.
 *
 * ## Where a report goes after this class
 *
 * Nowhere, until the user says so. [upload] is called from one dialog on the home screen, offered
 * once per crash on the launch that follows it, and it posts to the pool Worker's `/crash`, which
 * files it as an issue in the **private** `mlmvpn/crashes` repository and stores a row in D1.
 *
 * There is no silent upload and adding one would not be a bug fix -- it is a different decision
 * about somebody else's data.
 *
 * The whole path across the app, the Worker, D1, KV and that second repository is written down in
 * `docs/CRASH-REPORTS.md`, including how to test it end to end against a connected phone. Read
 * that before changing anything here that the Worker also depends on -- [signatureOf] in
 * particular, which decides what counts as "the same bug" and is recomputed server-side to match.
 */
object CrashReporter {

    const val TAG = "MLMCrash"

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** A Java/Kotlin stack, written by the uncaught-exception handler: the report proper. */
    private const val CRASH_PREFIX = "crash-"
    /** How earlier runs ended, from ApplicationExitInfo: native crashes and ANRs only show here. */
    private const val LAST_EXIT_PREFIX = "lastexit-"
    /** A VM shutdown note from the shutdown hook, written by every deliberate exit. */
    private const val EXIT_PREFIX = "exit-"

    /** Crash stacks kept on the phone, newest first. */
    private const val KEEP_CRASHES = 20
    /** Exit histories and shutdown notes kept, each. */
    private const val KEEP_NOTES = 5
    /** At most this many unsent stacks go up in one send. */
    private const val MAX_UPLOAD = 3
    /** An ANR's thread dump or a native tombstone can run to megabytes; a report needs its top. */
    private const val MAX_TRACE_CHARS = 24_000
    private lateinit var logDir: File
    private var appVersion: String = "?"

    /** Rolling record of what the app was last doing, printed into every crash report. */
    private val breadcrumbs = ArrayDeque<String>()
    private const val MAX_BREADCRUMBS = 40

    fun install(app: Application) {
        logDir = File(app.filesDir, "crashlogs").apply { mkdirs() }
        appVersion = try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (t: Throwable) { "?" }
        prune()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildReport(thread, error)
                // Logcat first: if writing the file fails for any reason, the report is still
                // in the buffer the stress test is being watched through.
                Log.e(TAG, text)
                writeReport(text)
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

        reportPreviousExit(app)

        Log.i(TAG, "crash reporter installed; reports in ${logDir.absolutePath}")
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
     * So: the name carries a counter and the thread, formatting is serialised, and the bytes go
     * to a temporary file that is renamed into place only once it is complete. A half-written
     * report is worse than none, because it looks like a report.
     */
    @Synchronized
    private fun writeReport(raw: String) {
        val text = SecretRedactor.redact(raw)
        val name = "$CRASH_PREFIX${stamp.format(Date())}-${reportSequence.incrementAndGet()}" +
            "-${Thread.currentThread().name.take(24).replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt"
        val target = File(logDir, name)
        val temp = File(logDir, "$name.part")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // A rename can only fail here for something like a full disk. Falling back to a
            // direct write is still better than losing the report.
            target.writeText(text)
            temp.delete()
        }
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

    /**
     * How many things on disk are worth sending: crash stacks, and the records of runs that ended
     * badly (a native crash or an ANR leaves nothing else). Not the shutdown notes every
     * deliberate restart writes -- Settings counted those as crashes.
     */
    fun reportCount(): Int =
        reports().count { it.name.startsWith(CRASH_PREFIX) || it.name.startsWith(LAST_EXIT_PREFIX) }

    /**
     * One text blob of the most recent reports, ready to be shared.
     *
     * Newest first and capped, because this is going into a message box: the newest report is
     * the one being asked about, and no one pastes half a megabyte into Telegram.
     *
     * The stacks come first and the app's own notes after them, one of each. It used to be simply
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
     */
    fun signatureOf(text: String): String {
        val cause = text.lineSequence()
            .firstOrNull { it.startsWith("error") || it.contains("Exception") || it.contains("Error") }
            ?.trim()
            .orEmpty()
        val frame = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("at com.mlmvpn") }
            .orEmpty()
        return listOf(cause, frame).filter { it.isNotBlank() }.joinToString("  |  ").take(300)
    }

    /**
     * Send the newest report to the pool worker, silently, and say whether it landed.
     *
     * Automatic rather than "please describe what happened": the stack, the breadcrumb trail of
     * screens, the device and the app version already say more than any user could type, and
     * asking for prose is what makes people close the dialog.
     */
    suspend fun upload(context: android.content.Context): Boolean {
        val crashes = reports().filter { it.name.startsWith(CRASH_PREFIX) }
        val offeredUpTo = context.getSharedPreferences("crash_diag", android.content.Context.MODE_PRIVATE)
            .getLong("offered_up_to", 0L)
        // Every crash since the last offer, not only the newest: two in a row are as often two
        // bugs as one, and the dialog asked about "a crash" without saying which. The server
        // keeps one row per install and signature, so a repeat of the same bug costs nothing.
        val pending = crashes.filter { it.lastModified() > offeredUpTo }.take(MAX_UPLOAD)
            .ifEmpty { crashes.take(1) }
        var sent = 0
        var failed = 0
        for (file in pending) {
            val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val ok = com.mlmvpn.scanner.quick.MlmPoolClient.reportCrash(
                context,
                // Reports written before redaction existed are cleaned here too.
                summary = SecretRedactor.redact(signatureOf(text)),
                body = SecretRedactor.redact(text),
            )
            if (ok) sent++ else failed++
        }
        return sent > 0 && failed == 0
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
     * The newest report the user has not been offered yet, or null.
     *
     * Tracked by timestamp rather than by a "shown" flag per file so that a burst of reports from
     * one crash prompts once, and so that clearing the marker cannot resurrect old prompts.
     */
    fun unreportedCrash(context: android.content.Context): File? {
        val newest = reports().firstOrNull { it.name.startsWith(CRASH_PREFIX) } ?: return null
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
     * Asks the system how the previous run of this process actually died and writes it down.
     *
     * This is the piece that was missing: with no adb on hand, `reason=1 status=255` was all we
     * had, and it is ambiguous -- it fits a native exit() AND the app's own restartAppProcess.
     * ApplicationExitInfo also carries the process NAME (so we learn whether it was the main
     * process or a child), the importance at the time of death (foreground vs cached, i.e. was
     * this the system trimming a background app?) and a system description string.
     */
    private fun reportPreviousExit(app: Application) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        // The history is the package's -- every process's at once -- so one process reads it: the
        // main one. The tunnel's process starting up used to read the same list and write it again.
        if (Application.getProcessName() != app.packageName) return
        try {
            val am = app.getSystemService(Application.ACTIVITY_SERVICE) as android.app.ActivityManager
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
            if (fresh.none { noteworthy(it) && !ours(it) }) return
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
                    // Present for ANR/native-crash exits; this is the actual stack when there is one.
                    try {
                        e.traceInputStream?.use { appendLine("trace      :\n" + readableTrace(e.reason, it.readBytes())) }
                    } catch (_: Throwable) {}
                    appendLine("---")
                }
            }
            Log.e(TAG, text)
            File(logDir, "$LAST_EXIT_PREFIX${stamp.format(Date())}.txt").writeText(SecretRedactor.redact(text))
        } catch (t: Throwable) {
            Log.w(TAG, "could not read exit reasons: ${t.message}")
        }
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
            val runs = StringBuilder()
            val run = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xff
                if (c in 0x20..0x7e || c == 0x09) run.append(c.toChar()) else {
                    if (run.length >= 6) runs.append(run).append('\n')
                    run.setLength(0)
                }
            }
            if (run.length >= 6) runs.append(run)
            runs.toString()
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
     * Record what the user is doing. Cheap enough to call on every screen change or connect —
     * it is a string append, not I/O.
     */
    fun note(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  ${SecretRedactor.redact(message)}"
        synchronized(breadcrumbs) {
            breadcrumbs.addLast(line)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
        // Echoed to logcat under the same tag so it is visible in the run-up to a *native*
        // crash too, where the file below is never written.
        Log.i(TAG, "• $line")
    }

    private fun buildReport(thread: Thread, error: Throwable): String = buildString {
        appendLine("=== MLMVPN crash ===")
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
    }
}
