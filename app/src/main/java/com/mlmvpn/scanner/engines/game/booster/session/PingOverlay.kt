package com.mlmvpn.scanner.engines.game.booster.session

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * The floating ping: a small reading of ping, jitter and loss over the game while it plays.
 *
 * Built from plain Views (no Compose runtime in a window of its own) and made unable to take a
 * touch -- every tap goes to the game underneath. On Android 12+ a window from another app that
 * touches pass through must be at most 80 % opaque, or the system blocks those touches instead;
 * so the window's alpha is exactly [MAX_ALPHA].
 *
 * Needs "display over other apps", which the player grants once from the Advanced page; without
 * it [show] quietly does nothing.
 */
class PingOverlay(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    fun show() = main.post {
        if (view != null || !canDraw(app)) return@post
        try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(app).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.WHITE)
                val pad = dp(6)
                setPadding(pad * 2, pad, pad * 2, pad)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(170, 0, 0, 0))
                }
                text = "…"
            }
            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(56)
                alpha = MAX_ALPHA
            }
            wm.addView(tv, params)
            view = tv
        } catch (e: Exception) {
            Log.w("GameBoost", "overlay refused: ${e.message}")
        }
    }

    fun update(live: LiveReading?) = main.post {
        val tv = view ?: return@post
        if (live == null) return@post
        val ping = live.p50?.toString() ?: "—"
        val jitter = live.jitter?.let { Math.round(it).toString() } ?: "—"
        val loss = live.loss?.let { Math.round(it).toString() } ?: "—"
        tv.text = "● $ping ms  J $jitter  L $loss%"
        tv.setTextColor(when (live.status) {
            LiveStatus.GOOD -> Color.rgb(120, 230, 140)
            LiveStatus.DEGRADED, LiveStatus.UNSTABLE -> Color.rgb(255, 196, 80)
            LiveStatus.LOSSY, LiveStatus.DOWN -> Color.rgb(255, 110, 100)
        })
    }

    fun hide() = main.post {
        val tv = view ?: return@post
        view = null
        try { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(tv) } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int = (v * app.resources.displayMetrics.density).toInt()

    companion object {
        const val MAX_ALPHA = 0.8f

        fun canDraw(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
    }
}
