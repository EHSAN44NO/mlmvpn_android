package com.mlmvpn.scanner.engines.game.booster.model

/**
 * The parts of a game's traffic, each of which can be open, filtered or sanctioned on its own.
 *
 * A game is not one thing to the network. Call of Duty can sign in fine and have its store
 * sanctioned; FC Mobile can have its whole sign-in sanctioned and play its match directly once
 * past it. So the booster checks, and fixes, each part separately -- and keeps [GAMEPLAY] on the
 * player's own line whenever the other parts can be fixed without it.
 *
 * Stored by name in the catalog and in profiles: never rename one.
 */
enum class TrafficClass {
    /** Account sign-in: the one part without which nothing else happens. */
    LOGIN,
    /** The game's own services: config, matchmaking, lobby. */
    API,
    /** Updates and asset downloads. */
    CDN,
    /** In-game store and payments. */
    STORE,
    /** Signing in with Google or Facebook. */
    SOCIAL,
    /** Voice chat. */
    VOICE,
    /** The match itself -- UDP to servers that answer no probe. Never checked by the doctor. */
    GAMEPLAY;

    /** Without these the game does not start at all; the others only lose a feature. */
    val isCore: Boolean get() = this == LOGIN || this == API

    companion object {
        /** The parts the doctor can check: everything but the match itself. */
        val ACCESS: List<TrafficClass> = entries.filter { it != GAMEPLAY }

        fun byName(name: String?): TrafficClass? = entries.firstOrNull { it.name == name }
    }
}
