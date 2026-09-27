package com.mlmvpn.core.geph

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * «گف»'s own settings. Each one is a field of the engine's config, and each default is the
 * official app's -- a per-user lever never changes how Geph behaves for anyone who does not
 * touch it.
 */
object GephSettings {

    private const val PREFS = "geph"

    const val SOCKS_PORT = 20850
    const val HTTP_PORT = 20851
    const val PAC_PORT = 20852

    /** Whole device through the tunnel, or only programs pointed at the local proxy. */
    enum class Coverage { VPN, PROXY }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun exit(ctx: Context): GephExitChoice = GephExitChoice(
        p(ctx).getString("exit_country", null)?.takeIf { it.isNotBlank() },
        p(ctx).getString("exit_city", null)?.takeIf { it.isNotBlank() },
    )

    fun setExit(ctx: Context, exit: GephExitChoice) {
        p(ctx).edit()
            .putString("exit_country", exit.country.orEmpty())
            .putString("exit_city", if (exit.country.isNullOrBlank()) "" else exit.city.orEmpty())
            .apply()
    }

    /**
     * Race a direct, unobfuscated connection to the exit against the bridges (the bridges start
     * a second later). Faster wherever the exits themselves are not blocked; off by default, as
     * in every official app, because where they ARE blocked it only adds a failed dial.
     */
    fun allowDirect(ctx: Context) = p(ctx).getBoolean("allow_direct", false)
    fun setAllowDirect(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("allow_direct", v).apply()

    /** Private and link-local destinations (router, printer, NAS) straight out, not via Geph. */
    fun allowLan(ctx: Context) = p(ctx).getBoolean("allow_lan", true)
    fun setAllowLan(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("allow_lan", v).apply()

    /**
     * Answer DNS on the phone with placeholder addresses and let the EXIT resolve the real name.
     * Saves a round trip through the tunnel for every new hostname, which is most of what a slow
     * page load is made of. Off by default: the official apps only use it for China passthrough,
     * and an app that caches an address across a reconnect loses it.
     */
    fun spoofDns(ctx: Context) = p(ctx).getBoolean("spoof_dns", false)
    fun setSpoofDns(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("spoof_dns", v).apply()

    /** The exit's own blocklists, applied to DNS and to hostname connections. */
    fun blockAds(ctx: Context) = p(ctx).getBoolean("block_ads", false)
    fun setBlockAds(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("block_ads", v).apply()
    fun blockAdult(ctx: Context) = p(ctx).getBoolean("block_adult", false)
    fun setBlockAdult(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("block_adult", v).apply()

    fun coverage(ctx: Context): Coverage =
        runCatching { Coverage.valueOf(p(ctx).getString("coverage", Coverage.VPN.name)!!) }.getOrDefault(Coverage.VPN)
    fun setCoverage(ctx: Context, v: Coverage) = p(ctx).edit().putString("coverage", v.name).apply()

    /** In proxy mode: listen on every interface so other devices on the Wi-Fi can use it too. */
    fun listenAll(ctx: Context) = p(ctx).getBoolean("listen_all", false)
    fun setListenAll(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("listen_all", v).apply()

    /** In proxy mode: also serve a PAC file, for browsers and TVs that take a proxy-script URL. */
    fun pac(ctx: Context) = p(ctx).getBoolean("pac", false)
    fun setPac(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("pac", v).apply()

    /** The user's own port forwards: a local TCP port that reaches one host through Geph. */
    fun forwards(ctx: Context): List<GephForward> = runCatching {
        val arr = JSONArray(p(ctx).getString("forwards", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GephForward(o.optString("listen"), o.optString("connect"))
                .takeIf { it.listen.isNotBlank() && it.connect.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    fun setForwards(ctx: Context, list: List<GephForward>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("listen", it.listen).put("connect", it.connect)) }
        p(ctx).edit().putString("forwards", arr.toString()).apply()
    }

    /** A forward the engine will accept: `address:port` to listen on, `host:port` to reach. */
    fun validForward(listen: String, connect: String): Boolean {
        val l = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}):(\\d{1,5})$").matchEntire(listen.trim()) ?: return false
        if (l.groupValues[3].toInt() !in 1..65535) return false
        val c = Regex("^([A-Za-z0-9.-]+|\\[[0-9a-fA-F:]+]):(\\d{1,5})$").matchEntire(connect.trim()) ?: return false
        return c.groupValues[2].toInt() in 1..65535
    }

    /** The last "fastest server" measurement, so the result survives leaving the screen. */
    fun lastFastest(ctx: Context): JSONObject? =
        p(ctx).getString("last_fastest", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun setLastFastest(ctx: Context, v: JSONObject?) =
        p(ctx).edit().putString("last_fastest", v?.toString()).apply()
}
