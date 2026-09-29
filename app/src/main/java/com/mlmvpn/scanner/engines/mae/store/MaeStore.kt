package com.mlmvpn.scanner.engines.mae.store

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * `filesDir/mae/state.json`, the one file MAE keeps. Written whole to a `.tmp` and renamed into
 * place, so a crash mid-write leaves the previous file intact. A file that fails to parse is moved
 * aside as `state.corrupt.json` and MAE starts fresh: it relearns rather than crashes.
 *
 * Excluded from Android backup (res/xml/backup_rules.xml): what it learned is about the networks
 * this device used, and means nothing restored onto another.
 */
class MaeStore(
    dir: File,
    /** android.util.Log, guarded so the JVM unit tests (no Android runtime) can use the store. */
    private val warn: (String) -> Unit = { msg -> runCatching { Log.w(TAG, msg) } },
    /** Off for tests, which read the file straight after an update. */
    private val writeAsync: Boolean = true,
) {
    private val root = File(dir, "mae").apply { mkdirs() }
    private val file = File(root, "state.json")
    private val tmp = File(root, "state.json.tmp")
    private val corrupt = File(root, "state.corrupt.json")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<MaeState> = _state

    val current: MaeState get() = _state.value

    @Synchronized
    fun update(change: (MaeState) -> MaeState): MaeState {
        val cur = _state.value
        val next = change(cur)
        // Identity, not equality: comparing two whole states field by field costs more than the
        // change itself, and a change that made nothing new returns the same object.
        if (next !== cur) {
            _state.value = next
            scheduleWrite()
        }
        return next
    }

    /**
     * The file is written off the caller's thread and at most every [WRITE_DELAY_MS]: a check
     * changes the state many times a second, and the screen's own taps used to wait on a full
     * write each time. Always the latest state; [flush] writes now.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mae-store").apply { isDaemon = true }
    }
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scheduleWrite() {
        if (!writeAsync) { write(_state.value); return }
        if (pending.compareAndSet(false, true)) {
            writer.schedule({ pending.set(false); write(_state.value) }, WRITE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /** Writes the latest state now, on this thread. */
    fun flush() = write(_state.value)

    private fun load(): MaeState {
        if (!file.exists()) return MaeState()
        return try {
            MaeStateCodec.decode(file.readText())
        } catch (e: Exception) {
            warn("state unreadable (${e.javaClass.simpleName}); starting fresh")
            runCatching { corrupt.delete(); file.renameTo(corrupt) }
            MaeState()
        }
    }

    private val fileLock = Any()

    private fun write(s: MaeState) = synchronized(fileLock) {
        try {
            tmp.writeText(MaeStateCodec.encode(s))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            warn("state write failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "MaeStore"
        const val WRITE_DELAY_MS = 400L
    }
}
