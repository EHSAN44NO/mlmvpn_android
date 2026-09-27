package com.mlmvpn.scanner.quick

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * A sweep that outlives the screen that started it.
 *
 * The browse screen used to run its sweep in `rememberCoroutineScope()`, which is tied to
 * composition — so pressing back mid-run cancelled the whole thing and threw away every result
 * it had proven. A sweep of a large country is minutes of work; losing it because the user
 * looked at something else is the kind of behaviour that trains people never to leave a screen
 * alone, and "check every server" is precisely the case where they should be able to.
 *
 * So the run lives here instead, on a scope of its own, and the screen only observes it. Back
 * leaves the screen; coming back re-attaches to whatever is still running, with the results it
 * has accumulated so far. Stopping is now something the user asks for explicitly, not something
 * navigation does behind their back.
 *
 * There is deliberately one session, not a queue: two concurrent sweeps would compete for the
 * same TCP and Xray budget and both would be slower than either alone.
 */
object QuickScanSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _progress = MutableStateFlow(QuickScanner.Progress(QuickScanner.Stage.IDLE))
    val progress: StateFlow<QuickScanner.Progress> = _progress.asStateFlow()

    private val _results = MutableStateFlow<List<QuickNode>>(emptyList())
    val results: StateFlow<List<QuickNode>> = _results.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Human-readable description of what is running, so a returning screen can say so. */
    private val _label = MutableStateFlow("")
    val label: StateFlow<String> = _label.asStateFlow()

    val isRunning: Boolean get() = _running.value

    private fun addResult(node: QuickNode) {
        _results.value = (_results.value + node).sortedBy { it.delay }
    }

    private fun begin(label: String): Boolean {
        if (_running.value) return false
        _running.value = true
        _error.value = null
        _label.value = label
        _results.value = emptyList()
        _progress.value = QuickScanner.Progress(QuickScanner.Stage.TCP)
        return true
    }

    private fun end() {
        _running.value = false
        job = null
    }

    /** Sweep one country (or everything), stopping at [want] proven servers. */
    fun startScan(context: Context, country: String?, want: Int, label: String) {
        if (!begin(label)) return
        job = scope.launch {
            try {
                val candidates = QuickConnectRepository.nodesFor(context, country ?: "all")
                QuickScanner.scan(
                    context = context,
                    candidates = candidates,
                    want = want,
                    onProgress = { _progress.value = it },
                    onFound = { addResult(it) },
                )
            } catch (e: Exception) {
                _error.value = e.message ?: S(R.string.the_search_failed)
                _progress.value = QuickScanner.Progress(QuickScanner.Stage.IDLE)
            } finally {
                end()
            }
        }
    }

    /** "N servers from M countries", every result proven and the countries evenly represented. */
    fun startBalanced(context: Context, countryCount: Int, want: Int, label: String) {
        if (!begin(label)) return
        job = scope.launch {
            try {
                val pools = QuickConnectRepository.nodesByTopCountries(context, countryCount)
                QuickScanner.scanBalanced(
                    context = context,
                    byCountry = pools,
                    want = want,
                    onProgress = { _progress.value = it },
                    onFound = { addResult(it) },
                )
            } catch (e: Exception) {
                _error.value = e.message ?: S(R.string.the_search_failed)
                _progress.value = QuickScanner.Progress(QuickScanner.Stage.IDLE)
            } finally {
                end()
            }
        }
    }

    /** Ask the running sweep to finish early, keeping what it has proven. */
    fun stop() {
        QuickScanner.stop()
    }

    /** Forget the results of the last run — the screen's own "clear", not a cancellation. */
    fun reset() {
        if (_running.value) return
        _results.value = emptyList()
        _progress.value = QuickScanner.Progress(QuickScanner.Stage.IDLE)
        _error.value = null
        _label.value = ""
    }

    /** Drop rows the user deleted, so the visible results match the stores. */
    fun removeResults(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        _results.value = _results.value.filterNot { it.id in set }
    }
}
