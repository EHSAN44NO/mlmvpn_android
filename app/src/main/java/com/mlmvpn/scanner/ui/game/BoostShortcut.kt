package com.mlmvpn.scanner.ui.game

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.mlmvpn.scanner.MainActivity
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.engines.game.booster.model.GameProfile
import com.mlmvpn.scanner.engines.game.booster.session.GameBoostService
import com.mlmvpn.scanner.utils.S

/**
 * «بوست و اجرا»: a home-screen icon per game that boosts it and then opens it.
 *
 * Most players start their game from the home screen and never pass through the booster, so a
 * boost that has to be remembered is a boost that does not happen. The icon opens the booster on
 * that game, runs the boost -- a few seconds when this network is remembered -- and launches the
 * game once the route is in place (or once the boost has failed: the player came to play).
 *
 * The launch is done by the booster's screen, which is in front at that moment, not by the
 * service: Android does not let a background service open another app.
 */
object BoostShortcut {

    /** The game id the booster screen should boost and then launch. */
    const val EXTRA_BOOST_AND_PLAY = "gb_boost_and_play"

    fun supported(context: Context): Boolean =
        try { ShortcutManagerCompat.isRequestPinShortcutSupported(context) } catch (e: Exception) { false }

    /** Ask the launcher to pin the icon; true if the request went out (the launcher asks the user). */
    fun request(context: Context, game: GameProfile, pkg: String): Boolean {
        if (!supported(context)) return false
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(GameBoostService.EXTRA_OPEN_BOOSTER, true)
            .putExtra(EXTRA_BOOST_AND_PLAY, game.id)
            // A fresh task: the app composes from scratch and reads the request once.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val icon = try {
            IconCompat.createWithBitmap(context.packageManager.getApplicationIcon(pkg).toBitmap(192, 192))
        } catch (e: Exception) {
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }
        val info = ShortcutInfoCompat.Builder(context, "gb_${game.id}")
            .setShortLabel(S(R.string.gb_shortcut_label, game.name).take(24))
            .setLongLabel(S(R.string.gb_shortcut_label, game.name))
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return try { ShortcutManagerCompat.requestPinShortcut(context, info, null) } catch (e: Exception) { false }
    }

    /**
     * The game id a boost-and-play icon asked for, read once: it is removed from the Activity's
     * intent so a rotation or a return to the screen does not boost and launch again.
     */
    fun consume(context: Context): String? {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull() ?: return null
        val id = activity.intent?.getStringExtra(EXTRA_BOOST_AND_PLAY) ?: return null
        activity.intent?.removeExtra(EXTRA_BOOST_AND_PLAY)
        return id
    }
}
