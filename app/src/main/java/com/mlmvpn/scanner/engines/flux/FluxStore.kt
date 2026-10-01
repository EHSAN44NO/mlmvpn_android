package com.mlmvpn.scanner.engines.flux

import android.util.Log
import com.mlmvpn.scanner.engines.flux.core.memory.FluxState
import com.mlmvpn.scanner.engines.flux.core.memory.FluxStateCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `filesDir/flux/state.json`: everything FLUX learned. The same discipline as MAE's store -- written
 * whole to a `.tmp` and renamed, off the caller's thread and at most every couple of seconds; a file
 * that does not parse is moved aside and FLUX relearns rather than crashes.
 *
 * Its own file, not a section of MAE's: FLUX works with MAE off, and MAE reads FLUX's routes from
 * here without ever writing to it.
 */
class FluxStore(dir: File) {

    private val root = File(dir, "flux").apply { mkdirs() }
    private val file = File(root, "state.json")
    private val tmp = File(root, "state.json.tmp")
    private val corrupt = File(root, "state.corrupt.json")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<FluxState> = _state
    val current: FluxState get() = _state.value

    @Synchronized
    fun update(change: (FluxState) -> FluxState): FluxState {
        val cur = _state.value
        val next = change(cur)
        if (next !== cur) {
            _state.value = next
            scheduleWrite()
        }
        return next
    }

    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "flux-store").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)

    private fun scheduleWrite() {
        if (pending.compareAndSet(false, true)) {
            writer.schedule({ pending.set(false); write(_state.value) }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun flush() = write(_state.value)

    private fun load(): FluxState {
        if (!file.exists()) return FluxState()
        return try {
            FluxStateCodec.decode(file.readText())
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "state unreadable (${e.javaClass.simpleName}); starting fresh") }
            runCatching { corrupt.delete(); file.renameTo(corrupt) }
            FluxState()
        }
    }

    private val fileLock = Any()

    private fun write(s: FluxState) {
        synchronized(fileLock) {
            try {
                tmp.writeText(FluxStateCodec.encode(s))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                Unit
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "state not written: ${e.javaClass.simpleName}") }
            }
        }
    }

    companion object {
        private const val TAG = "FluxStore"
        private const val WRITE_DELAY_MS = 2_000L
    }
}
