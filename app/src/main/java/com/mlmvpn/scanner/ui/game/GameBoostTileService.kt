package com.mlmvpn.scanner.ui.game

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mlmvpn.scanner.MainActivity
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.game.booster.model.GameProfileStore
import com.mlmvpn.scanner.engines.game.booster.session.BoostPhase
import com.mlmvpn.scanner.engines.game.booster.session.GameBoostController
import com.mlmvpn.scanner.engines.game.booster.session.GameBoostService
import com.mlmvpn.scanner.utils.S

/**
 * «گیم بوست» in the quick-settings panel: one tap boosts the last game boosted, another stops it.
 *
 * When the boost needs something only a screen can do -- the VPN permission, stopping another
 * connection the player has on, or no game chosen yet -- the tap opens the booster instead of
 * guessing.
 */
class GameBoostTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val s = GameBoostController.state.value
        if (GameBoostController.isActive && s.outcome?.measureOnly != true) {
            GameBoostService.stop(this)
            refresh(active = false)
            return
        }
        val gameId = getSharedPreferences("game_booster_prefs", MODE_PRIVATE).getString("gb_last_game", null)
        val store = GameProfileStore(this)
        val game = gameId?.let { store.get(it) }
        val otherConnection = (MyVpnService.isRunning && MyVpnService.connectedNodeId?.startsWith("game_") != true) ||
            com.mlmvpn.core.tunnel.TunnelStatus.isActive()
        val needsScreen = game == null || store.installedPackage(game) == null || otherConnection ||
            (try { VpnService.prepare(this) } catch (e: Exception) { null }) != null
        if (needsScreen) {
            openBooster()
            return
        }
        GameBoostService.start(this, game!!.id, game.defaultRegion, thorough = false)
        refresh(active = true)
    }

    private fun openBooster() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(GameBoostService.EXTRA_OPEN_BOOSTER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7721, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh(active: Boolean = GameBoostController.isActive &&
        GameBoostController.state.value.phase != BoostPhase.FAILED &&
        GameBoostController.state.value.outcome?.measureOnly != true) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = S(R.string.gb_tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            val game = getSharedPreferences("game_booster_prefs", MODE_PRIVATE).getString("gb_last_game", null)
                ?.let { GameProfileStore(this).get(it)?.name }
            tile.subtitle = game ?: S(R.string.gb_tile_no_game)
        }
        tile.updateTile()
    }
}
