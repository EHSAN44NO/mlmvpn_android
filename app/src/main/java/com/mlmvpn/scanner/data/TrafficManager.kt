package com.mlmvpn.scanner.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TrafficManager(context: Context) {
    private val prefs = context.getSharedPreferences("vpn_traffic_stats", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy_MM_dd", Locale.US)

    data class TrafficData(val rxBytes: Long, val txBytes: Long)

    private fun getTodayDateString(): String {
        return dateFormat.format(Date())
    }

    private fun getDateString(offsetDays: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, offsetDays)
        return dateFormat.format(calendar.time)
    }

    /**
     * Counts bytes towards today. Kept in memory and written to disk at most once a minute.
     *
     * Both tunnel services call this on every traffic sample -- every one or two seconds for as
     * long as anything moves, which on a phone left connected is all day -- and each call used to
     * rewrite this whole preference file. The total is the same; only the disk writes are batched.
     * [flush] writes now, and the services call it when a session ends.
     */
    fun addTraffic(rxDelta: Long, txDelta: Long) {
        if (rxDelta <= 0 && txDelta <= 0) return
        if (Pending.add(getTodayDateString(), rxDelta.coerceAtLeast(0L), txDelta.coerceAtLeast(0L))) flush()
    }

    /** Writes whatever is still only in memory. */
    fun flush() = synchronized(Pending) {
        // Under the same lock as the counts, so two services flushing at once cannot both read
        // the old total and each write it back plus only their own share.
        val drained = Pending.drain()
        if (drained.isEmpty()) return@synchronized
        val edit = prefs.edit()
        drained.forEach { (date, counts) ->
            edit.putLong("rx_$date", prefs.getLong("rx_$date", 0L) + counts[0])
            edit.putLong("tx_$date", prefs.getLong("tx_$date", 0L) + counts[1])
        }
        edit.apply()
    }

    fun getTodayTraffic(): TrafficData {
        val today = getTodayDateString()
        return day(today)
    }

    fun getTrafficForDays(days: Int): List<TrafficData> {
        val list = mutableListOf<TrafficData>()
        for (i in (days - 1) downTo 0) {
            list.add(day(getDateString(-i)))
        }
        return list
    }

    /** What is on disk for [date] plus what is still waiting to be written. */
    private fun day(date: String): TrafficData {
        val waiting = Pending.peek(date)
        return TrafficData(
            rxBytes = prefs.getLong("rx_$date", 0L) + waiting[0],
            txBytes = prefs.getLong("tx_$date", 0L) + waiting[1]
        )
    }

    /** The counts not yet written, per day, shared by every instance in the process. */
    private object Pending {
        private const val FLUSH_EVERY_MS = 60_000L
        private val byDate = HashMap<String, LongArray>()
        private var lastFlushAt = 0L

        /** Adds, and says whether it is time to write. */
        @Synchronized
        fun add(date: String, rx: Long, tx: Long): Boolean {
            val counts = byDate.getOrPut(date) { LongArray(2) }
            counts[0] += rx
            counts[1] += tx
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastFlushAt == 0L) lastFlushAt = now
            return now - lastFlushAt >= FLUSH_EVERY_MS
        }

        @Synchronized
        fun drain(): Map<String, LongArray> {
            lastFlushAt = android.os.SystemClock.elapsedRealtime()
            if (byDate.isEmpty()) return emptyMap()
            val out = HashMap(byDate)
            byDate.clear()
            return out
        }

        @Synchronized
        fun peek(date: String): LongArray = byDate[date]?.copyOf() ?: LongArray(2)
    }

    fun getWeeklyTraffic(): TrafficData {
        val list = getTrafficForDays(7)
        return TrafficData(
            rxBytes = list.sumOf { it.rxBytes },
            txBytes = list.sumOf { it.txBytes }
        )
    }

    fun getMonthlyTraffic(): TrafficData {
        val list = getTrafficForDays(30)
        return TrafficData(
            rxBytes = list.sumOf { it.rxBytes },
            txBytes = list.sumOf { it.txBytes }
        )
    }
}
