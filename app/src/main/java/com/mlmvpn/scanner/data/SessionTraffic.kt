package com.mlmvpn.scanner.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The running session's byte counters, in memory, for the live meter in the home header.
 *
 * Both tunnel services used to hand these to the screen through the `vpn_session_traffic`
 * preference file: written on every sample (each second on one service, every two seconds on the
 * other, all day, screen on or off) and read back by the screen once a second. Each of those
 * writes was a whole XML file going to flash. The services and the screen share a process, so the
 * numbers now travel through this flow instead, and the file is written rarely -- see the services.
 */
object SessionTraffic {

    data class Totals(val rx: Long = 0L, val tx: Long = 0L)

    private val _totals = MutableStateFlow(Totals())
    val totals: StateFlow<Totals> = _totals

    fun publish(rx: Long, tx: Long) {
        val next = Totals(rx.coerceAtLeast(0L), tx.coerceAtLeast(0L))
        if (next != _totals.value) _totals.value = next
    }

    fun reset() {
        _totals.value = Totals()
    }
}
