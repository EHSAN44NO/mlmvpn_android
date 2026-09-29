package com.mlmvpn.scanner.engines.mae.egress

import android.content.Context
import android.util.Base64
import com.mlmvpn.core.warp.WarpIdRelay
import com.mlmvpn.scanner.data.SecureStore
import com.mlmvpn.scanner.engines.mae.route.WarpRoute
import com.mlmvpn.scanner.engines.mae.store.WarpIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MAE's own WARP identity, made once with the app's existing registration path
 * ([WarpIdRelay.registerStandalone]): Cloudflare's API directly where it answers, through the
 * user's relay Worker where it is filtered (MCI: it is), with WARP switched on. The private key
 * is sealed with the Keystore before it is stored.
 */
object MaeWarp {
    /** Retry a failed registration after this long, not on every discovery. */
    const val RETRY_MS = 6 * 3600_000L

    suspend fun register(context: Context): WarpIdentity = withContext(Dispatchers.IO) {
        val s = WarpIdRelay.registerStandalone(context)
        val t = parseToml(s.toml)
        val priv = t["wg_private_key"].orEmpty().ifBlank { error("no WireGuard key in the identity") }
        val peer = t["wg_peer_public_key"].orEmpty().ifBlank { error("no peer key in the identity") }
        val reserved = t["client_id"]?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { Base64.decode(id, Base64.DEFAULT).take(3).map { it.toInt() and 0xFF } }.getOrNull()
        } ?: listOf(0, 0, 0)
        WarpIdentity(
            privateKeySealed = SecureStore.seal(priv) ?: error("secure storage is unavailable on this device"),
            peerPublicKey = peer,
            v4 = t["ipv4"].orEmpty(),
            v6 = t["ipv6"].orEmpty(),
            reserved = reserved,
            via = s.via,
            at = System.currentTimeMillis(),
        )
    }

    /** The route for this network's current endpoint, or null when the key cannot be opened. */
    fun route(w: WarpIdentity, endpointIndex: Int): WarpRoute? {
        val priv = SecureStore.open(w.privateKeySealed) ?: return null
        val ep = WarpRoute.ENDPOINTS[Math.floorMod(endpointIndex, WarpRoute.ENDPOINTS.size)]
        return WarpRoute(priv, w.peerPublicKey, w.v4, w.v6, w.reserved, ep)
    }

    /** `key = "value"` lines as aether's identity file writes them (see WarpIdRelay.provisionFull). */
    fun parseToml(toml: String): Map<String, String> = toml.lineSequence().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) return@mapNotNull null
        val k = line.substring(0, i).trim()
        val v = line.substring(i + 1).trim().removeSurrounding("\"").replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        k to v
    }.toMap()
}
