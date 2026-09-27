package com.mlmvpn.core.tunnel

import android.content.Context

/**
 * What a user has picked before, and what they have starred.
 *
 * Kept per picker - Psiphon's countries and Tor's are different lists with
 * different reasons for choosing one - so the key is passed in rather than
 * fixed. Stored in the same preferences file as everything else the tunnel
 * remembers, because a recents list that does not survive the app being killed
 * is not a recents list.
 *
 * Codes are held as one comma-separated string. A [Set] would be the obvious
 * type for the favourites and the wrong one for the recents, where the order is
 * the whole content, so both use the same list and the favourites simply ignore
 * it.
 */
class RegionMemory(context: Context, private val key: String) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun recent(): List<String> = read(RECENT)

    fun favourites(): Set<String> = read(FAVOURITE).toSet()

    /**
     * Records a pick, newest first, and returns the new list.
     *
     * Removed before being prepended so picking something already in the list
     * moves it to the front instead of appearing twice.
     */
    fun remember(code: String): List<String> {
        val next = (listOf(code) + read(RECENT).filterNot { it == code }).take(MAX_RECENT)
        write(RECENT, next)
        return next
    }

    fun toggleFavourite(code: String): Set<String> {
        val current = read(FAVOURITE)
        val next = if (code in current) current - code else current + code
        write(FAVOURITE, next)
        return next.toSet()
    }

    private fun read(kind: String) = prefs.getString("$key$kind", "")
        .orEmpty()
        .split(",")
        .filter { it.isNotBlank() }

    private fun write(kind: String, values: List<String>) {
        prefs.edit().putString("$key$kind", values.joinToString(",")).apply()
    }

    private companion object {
        const val RECENT = "_recent"
        const val FAVOURITE = "_favourite"

        // Enough to cover the handful anyone actually cycles between, short
        // enough that the recents tab never needs scrolling - which is the only
        // reason to look at it rather than at the full list.
        const val MAX_RECENT = 8
    }
}
