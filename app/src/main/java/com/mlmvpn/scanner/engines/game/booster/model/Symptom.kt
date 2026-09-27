package com.mlmvpn.scanner.engines.game.booster.model

/**
 * «چه مشکلی داری؟» -- what the player says is wrong, for when the one button was not enough.
 *
 * Each runs the thorough boost with what that problem needs checked again from scratch: a sign-in
 * problem forgets the cached verdicts about the game's hosts and the anti-sanction DNS, a lag
 * problem measures longer and watches the session for the cause of each spike. Stored by name.
 */
enum class Symptom {
    /** وصل نمی‌شه / وارد نمی‌شم */
    CANT_CONNECT,
    /** پینگ بالاست */
    HIGH_PING,
    /** لگ و پرش */
    LAG,
    /** تیر ثبت نمی‌شه */
    HIT_REG,
    /** قطع و وصل می‌شه */
    DISCONNECTS,
    /** آپدیت یا دانلود نمی‌شه */
    UPDATE;

    /** Whether the cached verdicts about the game's own hosts must be thrown away first. */
    val recheckAccess: Boolean get() = this == CANT_CONNECT || this == UPDATE

    companion object {
        fun byName(name: String?): Symptom? = entries.firstOrNull { it.name == name }
    }
}
