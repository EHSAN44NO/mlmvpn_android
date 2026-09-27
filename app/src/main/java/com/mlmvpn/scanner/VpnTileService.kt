package com.mlmvpn.scanner

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.mlmvpn.core.tunnel.CoreConfig
import com.mlmvpn.core.tunnel.TunnelStatus
import com.mlmvpn.core.tunnel.TunnelVpnService
import com.mlmvpn.scanner.data.EngineLadder
import com.mlmvpn.scanner.data.LastEngine
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.rstaspoof.SniSession
import com.mlmvpn.scanner.ui.httpDelay
import com.mlmvpn.scanner.ui.tunnel.Transport
import com.mlmvpn.scanner.ui.tunnel.TunnelExclusion
import com.mlmvpn.scanner.utils.LocalPort
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.VpnConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * The app's one Quick Settings tile.
 *
 * There were two, and neither could be right. One only knew about the Xray family and one only
 * about the five transports, so from the shade the user was asked to remember which of the app's
 * two VPN services had been carrying their traffic and pick the matching tile -- and picking the
 * wrong one did nothing they could see, because each tile reported the state of a service the
 * other one was running. One of them was also still labelled "NetDoctor", the name of the project
 * the transport stack came from, so the pair read as two unrelated apps.
 *
 * One tile, and it works out the answer itself:
 *
 *  - **Something is up** -> stop it, whichever service holds it.
 *  - **A transport was last used** -> bring that transport back.
 *  - **A special engine was last used** (Aether, VPN Gate, the anti-sanction relay, the game
 *    booster, a Quick Connect server) -> replay exactly that, because the engine's identity is
 *    in the URI and re-racing a list would connect to something else entirely.
 *  - **A config from the user's own list was last used** -> race the most recent N of them and
 *    take the fastest. N is Settings > Advanced VPN > Quick-connect servers, default 20. This is
 *    the one case where replaying the same entry is the wrong move: the list is the user's own
 *    and the point of the tile is to land on whichever of them is working right now.
 *  - **Nothing was ever connected and there are no configs** -- a fresh install -> walk the
 *    transports, Psiphon first, until one is actually carrying traffic. See [EngineLadder].
 *  - **Nothing was ever connected but configs exist** -> race them, as the best available guess.
 *
 * See [LastEngine] for how "last used" is recorded -- on CONNECTED, never on an attempt.
 */
class VpnTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return

        // --- Disconnect, whichever service holds the tunnel -------------------------------
        //
        // A ladder in progress counts as "something is happening", so a second tap stops it. It
        // walks for the better part of a minute on a blocked network, and a button that ignores
        // taps for that long is indistinguishable from one that is broken.
        if (EngineLadder.running.value) {
            EngineLadder.cancel(applicationContext)
            setTile(Tile.STATE_INACTIVE)
            return
        }
        if (TunnelStatus.isActive()) {
            startService(
                Intent(this, TunnelVpnService::class.java)
                    .setAction(TunnelVpnService.ACTION_DISCONNECT)
            )
            setTile(Tile.STATE_INACTIVE)
            return
        }
        if (MyVpnService.isRunning) {
            startService(Intent(this, MyVpnService::class.java).apply { action = "STOP" })
            MyVpnService.isRunning = false
            MyVpnService.connectedNodeId = null
            setTile(Tile.STATE_INACTIVE)
            return
        }

        // --- Connect: first-time VPN consent must be granted in-app -----------------------
        //
        // A TileService cannot host the consent dialog, so there is nothing to do here but open
        // the app and let it ask. Silently returning left the user tapping a tile that visibly
        // did nothing.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }

        reconnectLast()
    }

    /** Bring back whatever the user was last actually connected with. */
    private fun reconnectLast() {
        busy = true
        setTile(Tile.STATE_UNAVAILABLE, S(R.string.tile_connecting))
        scope.launch {
            try {
                when (val last = LastEngine.read(applicationContext)) {
                    is LastEngine.Record.Tunnel -> connectTransport(last.transportId)

                    is LastEngine.Record.Xray -> {
                        // An SNI config is in the user's list too, but racing the list would
                        // connect to something else entirely -- and the SNI configs all point at
                        // the same local port, so a race between them measures one socket eight
                        // times. It is replayed exactly; MyVpnService brings the SNI front up
                        // itself when it sees where the config points.
                        val fromUserList = !SniSession.isSniUri(last.uri) &&
                            last.nodeId != null &&
                            withContext(Dispatchers.IO) {
                                NodeManager(applicationContext).nodes.any { it.id == last.nodeId }
                            }
                        if (fromUserList) raceUserConfigs() else replayXray(last.uri, last.nodeId)
                    }

                    // Nothing has ever connected on this device. Rather than opening the app and
                    // giving up -- which is what this used to do and what read as a dead button --
                    // walk the transports until one is actually carrying traffic. See EngineLadder.
                    null -> if (withContext(Dispatchers.IO) { NodeManager(applicationContext).nodes.isEmpty() }) {
                        EngineLadder.start(applicationContext)
                    } else {
                        raceUserConfigs()
                    }
                }
            } catch (e: Exception) {
                toast(S(R.string.quick_connect_error))
            } finally {
                busy = false
                refreshTile()
            }
        }
    }

    /** One of the five transports, by id. */
    private suspend fun connectTransport(transportId: String) {
        val transport = Transport.entries.firstOrNull { it.id == transportId }
        if (transport == null) {
            // The id no longer maps to a transport -- a build that dropped one. Fall back to the
            // configs rather than doing nothing, which is what the user would see otherwise.
            raceUserConfigs()
            return
        }
        withContext(Dispatchers.IO) {
            // Only one VpnService may hold the device tun; the second establish() takes it from
            // the first rather than failing. See TunnelExclusion.
            TunnelExclusion.releaseForTunnelStack(applicationContext)
            val config = CoreConfig.json(applicationContext, transport.value)
            startForegroundService(
                Intent(this@VpnTileService, TunnelVpnService::class.java)
                    .setAction(TunnelVpnService.ACTION_CONNECT)
                    .putExtra(TunnelVpnService.EXTRA_CONFIG, config)
            )
        }
    }

    /** Replay one specific engine, exactly as it was. */
    private suspend fun replayXray(uri: String, nodeId: String?) {
        withContext(Dispatchers.IO) { TunnelExclusion.releaseForXray(applicationContext) }
        startXray(uri, nodeId)
    }

    /**
     * Test the most recent N configs and connect to the fastest.
     *
     * N is the Quick-connect server count from Advanced VPN settings. All N are probed at once,
     * because the point of a tile is that it finishes while the shade is still open -- a serial
     * walk of twenty servers would not.
     */
    private suspend fun raceUserConfigs() {
        val count = PreferenceManager.getDefaultSharedPreferences(this)
            .getInt("quick_tile_count", 20)
            .coerceIn(1, 200)
        val nodes = withContext(Dispatchers.IO) {
            NodeManager(applicationContext).nodes
                .sortedByDescending { it.addedAt }
                .take(count)
        }
        if (nodes.isEmpty()) {
            toast(S(R.string.no_server_available_add_a_config_first))
            return
        }

        val scored = nodes.map { node ->
            scope.async(Dispatchers.IO) {
                val cfg = VpnConfig.parseUri(node.uri)
                val ms = if (cfg != null && cfg.address.isNotBlank()) {
                    try { httpDelay(cfg.address, cfg.port) } catch (e: Exception) { -1 }
                } else {
                    -1
                }
                node to ms
            }
        }.awaitAll()

        // If nothing answered, the newest is still a better guess than refusing to act: the probe
        // is a plain reachability check and a blocked network fails all of them equally.
        val chosen = scored.filter { it.second in 1..8000 }.minByOrNull { it.second }?.first
            ?: nodes.first()
        startXray(chosen.uri, chosen.id)
    }

    private suspend fun startXray(uri: String, nodeId: String?) {
        val intent = Intent(this, MyVpnService::class.java).apply {
            putExtra("NODE_URI", uri)
            putExtra("NODE_ID", nodeId)
            // The app-wide settings, the same ones every other start path reads.
            putExtra("PROXY_MODE", NetworkSettings.proxyMode(this@VpnTileService))
            putExtra("LOCAL_PORT", LocalPort.getString(this@VpnTileService))
        }
        withContext(Dispatchers.Main) {
            // MyVpnService is a plain VpnService started via startService() (it never calls
            // startForeground); startForegroundService() would ANR with "did not then call
            // startForeground()".
            startService(intent)
            MyVpnService.isRunning = true
            MyVpnService.connectedNodeId = nodeId
        }
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launch)
        }
    }

    private fun refreshTile() {
        if (EngineLadder.running.value) {
            setTile(Tile.STATE_UNAVAILABLE, S(R.string.tile_connecting))
            return
        }
        val up = MyVpnService.isRunning || TunnelStatus.isActive()
        setTile(if (up) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }

    /**
     * The name stays put; the state goes in the subtitle.
     *
     * The label used to be swapped for "Connected" while the tunnel was up, so the tile lost its
     * own name exactly when the user most needed to identify it -- and on a shade holding a dozen
     * tiles, "Connected" identifies nothing. Android already draws an active tile as filled, and
     * the subtitle line exists for precisely this.
     */
    private fun setTile(state: Int, subtitle: String? = null) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = S(R.string.tile_label_vpn)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle ?: S(
                if (state == Tile.STATE_ACTIVE) R.string.vpn_connected else R.string.vpn_disconnected
            )
        }
        try { tile.updateTile() } catch (e: Exception) { /* ignore */ }
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@VpnTileService, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
