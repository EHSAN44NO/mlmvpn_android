package com.mlmvpn.core.tunnel

import android.content.Context

/**
 * Which apps go through the tunnel, for the five-transport stack.
 *
 * A thin adapter now, not a store. The app answered this question twice: Settings > Advanced VPN
 * wrote `vpn_routing_mode` / `vpn_routing_apps` into `vpn_routing_prefs` with the names
 * ALL/ALLOW/BYPASS, and this class wrote `mode` / `packages` into `split_tunneling` with the names
 * ALL/INCLUDE/EXCLUDE. Same question, same three answers, two stores -- so a user who excluded
 * their banking app found it tunnelled again the moment they switched to a transport, and nothing
 * on either screen suggested the two lists were different lists.
 *
 * The app-wide store wins, because that is the one the user reaches from Settings. The names map
 * one to one: ALLOW is INCLUDE ("only these apps") and BYPASS is EXCLUDE ("everything but these").
 * A user who had only ever set the transport-side list keeps it -- see [migrateIfNeeded].
 */
class SplitTunnelSettings(context: Context) {

    private val app = context.applicationContext
    private val shared = app.getSharedPreferences(SHARED_PREFERENCES, Context.MODE_PRIVATE)
    private val legacy = app.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)

    enum class Mode(val label: String) {
        ALL("All apps"),
        INCLUDE("Only selected apps"),
        EXCLUDE("Exclude selected apps"),
    }

    init {
        migrateIfNeeded()
    }

    fun mode(): Mode = when (shared.getString(SHARED_MODE, MODE_ALL)) {
        MODE_ALLOW -> Mode.INCLUDE
        MODE_BYPASS -> Mode.EXCLUDE
        else -> Mode.ALL
    }

    fun packages(): Set<String> = shared.getStringSet(SHARED_APPS, emptySet()).orEmpty()

    fun save(mode: Mode, packages: Set<String>) {
        shared.edit()
            .putString(SHARED_MODE, mode.toShared())
            .putStringSet(SHARED_APPS, packages.toHashSet())
            .commit()
    }

    fun cleanup(installedPackages: Set<String>) {
        val current = packages()
        val filtered = current.filter { it in installedPackages }.toSet()
        if (filtered.size != current.size) {
            shared.edit().putStringSet(SHARED_APPS, filtered.toHashSet()).apply()
        }
    }

    /**
     * Carry a transport-only split list across to the shared one, exactly once.
     *
     * Only when the shared store has never been written: a user who has set the app-wide list is
     * telling us what they want, and quietly replacing it with an older transport-side list would
     * be the same class of bug this merge exists to fix, in the other direction.
     */
    private fun migrateIfNeeded() {
        if (shared.contains(SHARED_MODE)) return
        val legacyMode = legacy.getString(LEGACY_MODE, null) ?: return
        val mapped = runCatching { Mode.valueOf(legacyMode) }.getOrNull() ?: return
        if (mapped == Mode.ALL) return
        shared.edit()
            .putString(SHARED_MODE, mapped.toShared())
            .putStringSet(
                SHARED_APPS,
                legacy.getStringSet(LEGACY_PACKAGES, emptySet()).orEmpty().toHashSet(),
            )
            .apply()
    }

    private fun Mode.toShared(): String = when (this) {
        Mode.INCLUDE -> MODE_ALLOW
        Mode.EXCLUDE -> MODE_BYPASS
        Mode.ALL -> MODE_ALL
    }

    private companion object {
        /** The app-wide store, written by Settings > Advanced VPN. */
        const val SHARED_PREFERENCES = "vpn_routing_prefs"
        const val SHARED_MODE = "vpn_routing_mode"
        const val SHARED_APPS = "vpn_routing_apps"

        const val MODE_ALL = "ALL"
        const val MODE_ALLOW = "ALLOW"
        const val MODE_BYPASS = "BYPASS"

        /** Read once, for users who set the transport list before the two were merged. */
        const val LEGACY_PREFERENCES = "split_tunneling"
        const val LEGACY_MODE = "mode"
        const val LEGACY_PACKAGES = "packages"
    }
}
