package com.mlmvpn.scanner.data

import android.content.Context

/**
 * What the user last connected with, so one button can bring it back.
 *
 * The app has two VPN services and about eight ways to reach them -- the node list, Quick Connect,
 * VPN Gate, Aether, the anti-sanction relay, the game booster, the five transports. Nothing wrote
 * down which of those was actually used, which is fine while the user is inside the app looking at
 * the screen that started it, and useless anywhere else: a Quick Settings tile has no screen and
 * no context, so without this it can only offer a guess or open the app and give up.
 *
 * Recorded at the one point that means it worked -- the service reporting CONNECTED / RUNNING --
 * rather than when a connect is attempted. A record of something that failed to come up is worse
 * than no record: the tile would replay it and fail the same way, with the tile blinking on and
 * off as its only explanation.
 */
object LastEngine {

    private const val PREFS = "app_settings"
    private const val KEY_KIND = "last_engine_kind"
    private const val KEY_URI = "last_engine_uri"
    private const val KEY_NODE_ID = "last_engine_node_id"
    private const val KEY_TRANSPORT = "last_engine_transport"

    private const val KIND_XRAY = "xray"
    private const val KIND_TUNNEL = "tunnel"

    /** What to bring back, and with which service. */
    sealed class Record {
        /**
         * Anything that runs through [com.mlmvpn.scanner.MyVpnService].
         *
         * One case for all of them because the engine's identity is already in the URI -- a
         * SoftEther gateway, an Aether config and a VLESS node are told apart by their own
         * scheme and fields, not by a label this object would have to keep in step. [nodeId] is
         * carried alongside because it is what says whether this was a config from the user's own
         * list (in which case the right move is to race the list again, not replay one entry).
         */
        data class Xray(val uri: String, val nodeId: String?) : Record()

        /** One of the five transports, by [com.mlmvpn.scanner.ui.tunnel.Transport.id]. */
        data class Tunnel(val transportId: String) : Record()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * MAE is recorded by name only. Its config is compiled per network and carries MAE's sealed
     * secrets unsealed (the WARP key, the Worker's id, the user's configs): stored here it went
     * into Android backups and, replayed, brought one network's routes to another. The tile builds
     * a fresh one instead.
     */
    const val MAE_MARKER = "mae"

    /**
     * FLUX likewise, by name: its config is chosen per network from public nodes, and the tile
     * asks FLUX for this network's route instead of replaying another network's.
     */
    const val FLUX_MARKER = "flux"

    private fun isMae(nodeId: String?) = nodeId == MAE_MARKER || nodeId == FLUX_MARKER

    fun recordXray(context: Context, uri: String?, nodeId: String?) {
        if (uri.isNullOrBlank()) return
        prefs(context).edit()
            .putString(KEY_KIND, KIND_XRAY)
            .putString(KEY_URI, if (isMae(nodeId)) nodeId else uri)
            .putString(KEY_NODE_ID, nodeId)
            .apply()
    }

    fun recordTunnel(context: Context, transportId: String) {
        if (transportId.isBlank()) return
        prefs(context).edit()
            .putString(KEY_KIND, KIND_TUNNEL)
            .putString(KEY_TRANSPORT, transportId)
            .apply()
    }

    fun read(context: Context): Record? {
        val p = prefs(context)
        return when (p.getString(KEY_KIND, null)) {
            KIND_XRAY -> p.getString(KEY_URI, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { uri ->
                    val nodeId = p.getString(KEY_NODE_ID, null)
                    // A record from before the marker still holds a whole MAE config: dropped here.
                    if (isMae(nodeId) && uri != nodeId) p.edit().putString(KEY_URI, nodeId).apply()
                    Record.Xray(if (isMae(nodeId)) nodeId!! else uri, nodeId)
                }

            KIND_TUNNEL -> p.getString(KEY_TRANSPORT, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { Record.Tunnel(it) }

            else -> null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_KIND)
            .remove(KEY_URI)
            .remove(KEY_NODE_ID)
            .remove(KEY_TRANSPORT)
            .apply()
    }
}
