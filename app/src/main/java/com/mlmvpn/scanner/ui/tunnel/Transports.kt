package com.mlmvpn.scanner.ui.tunnel

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mlmvpn.scanner.ui.home.HomeDestinations
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

/**
 * The five transports of the Cloudflare/censorship tunnel stack, one per home icon.
 *
 * They were a single screen with a tab strip across the top, which made two claims that are
 * both false: that picking one is a mode of one feature, and that they are variants of each
 * other. They are not. MASQUE and WireGuard talk to Cloudflare's edge with completely
 * different handshakes; WARP-on-WARP stacks one inside the other; Psiphon and Tor are separate
 * networks with their own servers, their own countries and their own reasons to fail. A user
 * picking between them is picking a different product, so each gets an icon, a screen and a
 * settings page of its own -- and a tab strip that hid four of the five behind the fifth is
 * gone.
 *
 * [value] is the string the Rust core and [com.mlmvpn.core.tunnel.CoreConfig] speak. It is not
 * cosmetic: `gool` is the core's own name for WARP-on-WARP, and renaming it here would produce
 * a config the core silently falls back to MASQUE on.
 */
enum class Transport(
    val id: String,
    val value: String,
    val label: String,
    val labelFa: String,
    val icon: ImageVector,
    val tint: Color,
    /**
     * The desktop's artwork for this transport (the same drawable as its home tile), shown in the
     * connect dial instead of [icon]. Null for the ones the desktop draws as a glyph.
     */
    val artRes: Int? = null,
    /** The desktop's glyph, white, for a transport without artwork. Falls back to [icon]. */
    val glyphRes: Int? = null,
) {
    MASQUE(
        id = "masque",
        value = "masque",
        label = "MASQUE",
        labelFa = S(R.string.masque),
        icon = Icons.Default.Bolt,
        tint = HomeDestinations.Blue,
        artRes = R.drawable.ic_app_masque,
    ),
    WIREGUARD(
        id = "wireguard",
        value = "wireguard",
        label = "WireGuard",
        labelFa = S(R.string.wireguard_2),
        icon = Icons.Default.VpnKey,
        tint = HomeDestinations.Green,
        artRes = R.drawable.ic_app_wireguard,
    ),
    GOOL(
        id = "warp_on_warp",
        value = "gool",
        label = "WARP-on-WARP",
        labelFa = S(R.string.warp_in_warp),
        icon = Icons.Default.Layers,
        tint = HomeDestinations.Orange,
        glyphRes = R.drawable.ic_glyph_layers,
    ),
    PSIPHON(
        id = "psiphon",
        value = "psiphon",
        label = "Psiphon",
        labelFa = S(R.string.psiphon),
        icon = Icons.Default.Public,
        tint = HomeDestinations.Indigo,
    ),
    TOR(
        id = "tor",
        value = "tor",
        label = "Tor",
        labelFa = S(R.string.tor),
        icon = Icons.Default.Shield,
        tint = HomeDestinations.Purple,
        artRes = R.drawable.ic_app_tor,
    ),
    /**
     * «گف»: Geph's own network and its own engine -- the official Geph Android build, run the way
     * the official app runs it. Not a Cloudflare transport and not built on the tunnel core; it has
     * its own servers in a dozen countries and a broker that no single address can block.
     */
    GEPH(
        id = "geph",
        value = "geph",
        label = "Geph",
        labelFa = S(R.string.geph),
        // No artwork on the dial: the user asked for a plain button here.
        icon = Icons.Default.Power,
        tint = HomeDestinations.Blue,
    ),
    /**
     * «وارپ»: plain Cloudflare WARP on an engine of its own -- its own identity (made with WARP
     * switched on), its own endpoint hunt, its own real-traffic check (see CfWarpEngine). Not the
     * tunnel core: when that is in trouble, this still tries. The value is deliberately not
     * "warp" -- WarpIdRelay reads any protocol containing "warp" as WARP-on-WARP.
     */
    CFWG(
        id = "warp",
        value = "cfwg",
        label = "WARP",
        labelFa = S(R.string.cfwarp),
        icon = Icons.Default.Public,
        tint = HomeDestinations.Teal,
        artRes = R.drawable.ic_app_warp,
    );

    /**
     * One line under the title saying what this transport IS, in the terms that decide whether
     * to reach for it: what it looks like on the wire, and what it costs.
     */
    val taglineFa: String
        get() = when (this) {
            MASQUE -> S(R.string.traffic_goes_over_http_3_on_quic)
            WIREGUARD -> S(R.string.a_direct_warp_tunnel_the_fastest_option)
            GOOL -> S(R.string.a_wireguard_tunnel_inside_a_masque_tunnel)
            PSIPHON -> S(R.string.psiphon_s_own_network_with_a_three)
            TOR -> S(R.string.three_layers_of_encryption_over_all_of)
            GEPH -> S(R.string.geph_tagline)
            CFWG -> S(R.string.cfwarp_tagline)
        }

    /** The one sentence worth reading before deciding this is the transport to try. */
    val adviceFa: String
        get() = when (this) {
            MASQUE ->
                S(R.string.try_this_one_first_if_http_3) +
                    S(R.string.on_its_own_which_gets_through_even)
            WIREGUARD ->
                S(R.string.wherever_it_works_it_is_the_fastest) +
                    S(R.string.masque_answers)
            GOOL ->
                S(R.string.reach_for_it_when_neither_layer_gets) +
                    S(R.string.mean_a_slower_connection_not_slower_traffic)
            PSIPHON ->
                S(R.string.it_has_its_own_core_and_has) +
                    S(R.string.the_carrier_has_targeted_cloudflare_s_addresses)
            TOR ->
                S(R.string.the_slowest_option_and_the_most_anonymous) +
                    S(R.string.turn_on_tor_inside_warp)
            GEPH -> S(R.string.geph_advice)
            CFWG -> S(R.string.cfwarp_advice)
        }

    /**
     * Whether this device can run the transport at all.
     *
     * Tor is two executables cross-compiled for ARM only. Saying so up front is better than a
     * connect that dies on a file-not-found the user cannot act on.
     */
    val supportedHere: Boolean
        get() = (this != TOR && this != GEPH && this != CFWG) ||
            android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm") }

    /**
     * Whether this transport runs on the tunnel core (libtunnelcore). Geph brings its own engine,
     * so a core that failed to load on this phone is no reason to refuse it.
     */
    val needsTunnelCore: Boolean
        get() = this != GEPH && this != CFWG

    companion object {
        fun byId(id: String): Transport? = entries.firstOrNull { it.id == id }

        /** Every home-screen id this feature owns, for the routing table in AppScreen. */
        val IDS: List<String> = entries.map { it.id }

        /**
         * The transport to reach for first, on a fresh install or after a wipe.
         *
         * On a censored network the transport that works is a property of the carrier rather
         * than of the day, so the one that carried a tunnel yesterday is the best guess for
         * today. MASQUE is the fallback because it is the one with a second transport of its
         * own to fall back to.
         */
        fun lastWorking(context: Context): Transport {
            val stored = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(LAST_WORKING_PREF, null)
            return entries.firstOrNull { it.name == stored } ?: MASQUE
        }

        fun rememberWorking(context: Context, transport: Transport) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit()
                .putString(LAST_WORKING_PREF, transport.name)
                .apply()
        }

        private const val LAST_WORKING_PREF = "last_working_transport"
    }
}
