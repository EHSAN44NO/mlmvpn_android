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
        val next = change(_state.value)
        if (next != _state.value) {
            _state.value = next
            write(next)
        }
        return next
    }

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

    private fun write(s: MaeState) {
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

    private companion object { const val TAG = "MaeStore" }
}
