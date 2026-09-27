package com.mlmvpn.scanner.lan

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

// =================================================================================================
// Ten things that have to be true, checked one at a time, each with what to do about it.
//
// "It doesn't work" has a lot of causes here and they are not distinguishable from the outside:
// a tunnel that is down, an engine with no listener, a switch that is off, a phone with no local
// address, a port nothing is bound to, and a Wi-Fi network that isolates its clients all present
// to the user as a laptop that will not load a page.
//
// The single most valuable row is [Check.SETUP_PAGE_FETCHED], because it splits the problem in
// half. If a device has loaded the setup page, the network path between the two machines works and
// the fault is in that device's proxy settings. If nothing has, the two machines cannot reach each
// other at all and no amount of fiddling with proxy fields will help -- that is client isolation
// on a public Wi-Fi, and the fix is the phone's own hotspot. Nothing else on the phone can tell
// those apart.
//
// Every check is cheap except the two socket probes, which is why the whole thing runs on demand
// rather than in the status poll.
// =================================================================================================

object LanDoctor {

    enum class Verdict { PASS, FAIL, WARN, SKIP }

    /**
     * What the user can do about a failed row.
     *
     * The screen maps these to buttons. A check with [Fix.NONE] is informational -- it explains
     * rather than blocks, and offering a button that does nothing is worse than offering none.
     */
    enum class Fix { NONE, CONNECT, PROXY_MODE, ENABLE_LAN, HOTSPOT, BATTERY }

    enum class Check {
        TUNNEL,
        ENGINE,
        LAN_ENABLED,
        LOCAL_ADDRESS,
        MEDIUM,
        PROXY_PORT,
        SETUP_SERVER,
        SETUP_PAGE_FETCHED,
        PROXY_CONNECTIONS,
        BATTERY,
    }

    data class Row(
        val check: Check,
        val verdict: Verdict,
        /** The finding, already in the user's language and naming the actual value where there is one. */
        val detail: String,
        val fix: Fix = Fix.NONE,
    )

    /**
     * Run every check.
     *
     * Suspending because two rows open sockets. They are short (800 ms) and local, but they are
     * still blocking I/O and this is called from a button on the main thread.
     */
    suspend fun run(context: Context, status: LanStatus): List<Row> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Row>()

        rows += if (status.vpnUp) {
            Row(Check.TUNNEL, Verdict.PASS, S(R.string.lan_doc_tunnel_ok))
        } else {
            Row(Check.TUNNEL, Verdict.FAIL, S(R.string.lan_doc_tunnel_bad), Fix.CONNECT)
        }

        rows += when (status.engine) {
            EngineShare.FULL ->
                Row(Check.ENGINE, Verdict.PASS, S(R.string.lan_doc_engine_full))
            EngineShare.NO_AUTH ->
                Row(Check.ENGINE, Verdict.PASS, S(R.string.lan_doc_engine_noauth))
            EngineShare.NEEDS_PROXY_MODE ->
                Row(Check.ENGINE, Verdict.FAIL, S(R.string.lan_doc_engine_tun), Fix.PROXY_MODE)
            EngineShare.IMPOSSIBLE ->
                Row(Check.ENGINE, Verdict.FAIL, S(R.string.lan_doc_engine_impossible), Fix.CONNECT)
            EngineShare.NO_TUNNEL ->
                Row(Check.ENGINE, Verdict.SKIP, S(R.string.lan_doc_skipped))
        }

        rows += if (status.lanEnabled) {
            Row(Check.LAN_ENABLED, Verdict.PASS, S(R.string.lan_doc_switch_ok))
        } else {
            Row(Check.LAN_ENABLED, Verdict.FAIL, S(R.string.lan_doc_switch_bad), Fix.ENABLE_LAN)
        }

        val address = status.address
        rows += if (address != null) {
            Row(Check.LOCAL_ADDRESS, Verdict.PASS, address)
        } else {
            // The interface survey is included verbatim: two earlier builds got this answer wrong
            // and a screenshot of the verdict alone was not enough to say why.
            Row(
                Check.LOCAL_ADDRESS,
                Verdict.FAIL,
                S(R.string.lan_doc_address_bad) + "\n" +
                    com.mlmvpn.core.tunnel.CoreConfig.describeLocalNetworks(context),
                Fix.HOTSPOT,
            )
        }

        rows += when {
            address == null -> Row(Check.MEDIUM, Verdict.SKIP, S(R.string.lan_doc_skipped))
            status.medium.isOwnNetwork ->
                Row(Check.MEDIUM, Verdict.PASS, S(R.string.lan_doc_medium_own))
            else -> Row(Check.MEDIUM, Verdict.WARN, S(R.string.lan_doc_medium_public))
        }

        rows += if (address == null) {
            Row(Check.PROXY_PORT, Verdict.SKIP, S(R.string.lan_doc_skipped))
        } else if (canConnect(address, status.sharePort)) {
            Row(Check.PROXY_PORT, Verdict.PASS, S(R.string.lan_doc_port_ok, status.sharePort))
        } else {
            // Reached only when the engine claims a listener and the socket still refuses, which
            // means the bind went to loopback -- the exact failure LAN sharing is about.
            Row(Check.PROXY_PORT, Verdict.FAIL, S(R.string.lan_doc_port_bad, status.sharePort))
        }

        rows += if (address == null) {
            Row(Check.SETUP_SERVER, Verdict.SKIP, S(R.string.lan_doc_skipped))
        } else if (LanSetupServer.isRunning && canConnect(address, status.setupPort)) {
            Row(Check.SETUP_SERVER, Verdict.PASS, S(R.string.lan_doc_setup_ok, status.setupPort))
        } else {
            Row(Check.SETUP_SERVER, Verdict.FAIL, S(R.string.lan_doc_setup_bad, status.setupPort))
        }

        val fetched = LanSetupServer.seenClients()
        rows += if (fetched.isNotEmpty()) {
            Row(Check.SETUP_PAGE_FETCHED, Verdict.PASS, fetched.joinToString(", "))
        } else {
            Row(
                Check.SETUP_PAGE_FETCHED,
                Verdict.WARN,
                if (status.medium.isOwnNetwork) {
                    S(R.string.lan_doc_page_none_own)
                } else {
                    // The one diagnosis nothing else on the phone can produce.
                    S(R.string.lan_doc_page_none_public)
                },
                if (status.medium.isOwnNetwork) Fix.NONE else Fix.HOTSPOT,
            )
        }

        val connected = status.clients.count { it.connections > 0 }
        rows += if (connected > 0) {
            Row(Check.PROXY_CONNECTIONS, Verdict.PASS, S(R.string.lan_doc_conn_ok, connected))
        } else {
            Row(Check.PROXY_CONNECTIONS, Verdict.WARN, S(R.string.lan_doc_conn_none))
        }

        rows += if (batteryUnrestricted(context)) {
            Row(Check.BATTERY, Verdict.PASS, S(R.string.lan_doc_battery_ok))
        } else {
            Row(Check.BATTERY, Verdict.WARN, S(R.string.lan_doc_battery_bad), Fix.BATTERY)
        }

        rows
    }

    /** A real TCP connect, because a bound-but-refusing port and an open one look alike otherwise. */
    private fun canConnect(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 800) }
        true
    }.getOrDefault(false)

    private fun batteryUnrestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true)
    }

    /** Title for a row, so the screen keeps no second copy of the check list. */
    fun titleRes(check: Check): Int = when (check) {
        Check.TUNNEL -> R.string.lan_doc_t_tunnel
        Check.ENGINE -> R.string.lan_doc_t_engine
        Check.LAN_ENABLED -> R.string.lan_doc_t_switch
        Check.LOCAL_ADDRESS -> R.string.lan_doc_t_address
        Check.MEDIUM -> R.string.lan_doc_t_medium
        Check.PROXY_PORT -> R.string.lan_doc_t_port
        Check.SETUP_SERVER -> R.string.lan_doc_t_setup
        Check.SETUP_PAGE_FETCHED -> R.string.lan_doc_t_page
        Check.PROXY_CONNECTIONS -> R.string.lan_doc_t_conn
        Check.BATTERY -> R.string.lan_doc_t_battery
    }

    fun fixLabelRes(fix: Fix): Int? = when (fix) {
        Fix.NONE -> null
        Fix.CONNECT -> R.string.lan_action_connect
        Fix.PROXY_MODE -> R.string.lan_action_proxy_mode
        Fix.ENABLE_LAN -> R.string.lan_action_enable
        Fix.HOTSPOT -> R.string.lan_action_hotspot
        Fix.BATTERY -> R.string.lan_doc_fix_battery
    }

    /**
     * The whole report as text, for a user who wants to send it to support.
     *
     * Deliberately includes only local addresses, port numbers and verdicts. The tunnel's exit
     * IP, the config, and the account are all absent -- this is meant to be pasted into a chat.
     */
    fun asText(rows: List<Row>, status: LanStatus): String = buildString {
        appendLine("MLMVPN local network report")
        appendLine("engine=${status.engine} medium=${status.medium} port=${status.socksPort}")
        appendLine()
        rows.forEach { row ->
            val mark = when (row.verdict) {
                Verdict.PASS -> "[ok]"
                Verdict.FAIL -> "[!!]"
                Verdict.WARN -> "[??]"
                Verdict.SKIP -> "[--]"
            }
            appendLine("$mark ${S(titleRes(row.check))}: ${row.detail.replace("\n", " | ")}")
        }
    }
}
