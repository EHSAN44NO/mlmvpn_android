package com.mlmvpn.scanner.lan

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.NetworkSettings
import com.mlmvpn.scanner.utils.S

/**
 * A Quick Settings tile for Local Network sharing.
 *
 * The app deliberately has exactly one VPN tile — see the note beside `.VpnTileService` in the
 * manifest — because the two it used to have did the SAME job through different services, so the
 * user had to guess which stack was running and picking wrong appeared to do nothing.
 *
 * This is a second tile and does not reintroduce that problem, because it does a different job:
 * it does not start or stop a tunnel, it decides whether the tunnel already running is published
 * to the rest of the network. Both tiles can be on the shade at once and neither can be mistaken
 * for the other.
 *
 * The subtitle carries the address, which is the one thing a user reaches for this feature to
 * find. Subtitles are Android 10+; below that the label alone still says on or off.
 */
@RequiresApi(Build.VERSION_CODES.N)
class LanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val next = !NetworkSettings.allowLan(this)
        NetworkSettings.setAllowLan(this, next)
        if (!next) {
            // Tear the server down here rather than waiting for the screen's poll: the user is in
            // the shade, the app may not be in the foreground at all, and "off" has to mean the
            // listener is actually gone.
            LanSetupServer.shutdown()
            LanNotification.clear(this)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val status = LanShare.status(this)
        val on = NetworkSettings.allowLan(this)

        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = S(R.string.home_lan)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // What the user actually wants from the shade, in order: the address when there is
            // one, otherwise the reason there is not.
            tile.subtitle = when {
                !on -> S(R.string.off_2)
                status.ready -> status.proxyEndpoint
                status.blocker == LanBlocker.NO_TUNNEL -> S(R.string.lan_head_no_tunnel)
                status.blocker == LanBlocker.NO_LOCAL_NETWORK -> S(R.string.lan_head_no_network)
                else -> S(R.string.lan_head_engine)
            }
        }
        runCatching { tile.updateTile() }
    }
}
