package com.mlmvpn.scanner.data.studio

import android.content.Context
import com.mlmvpn.scanner.models.VpnNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One Config Studio subscriber, handed to the scanner as a combine source.
 *
 * The scanner is where combining already lives — its sweep, its archive, its per-IP measurement and
 * its group list are all built and all understood by whoever uses this app. So «ترکیب» in Config
 * Studio does not carry a scanner of its own; it **arms this**, opens the scanner, and the scanner's
 * own combine sheet offers the person alongside the cloud groups it already offers.
 *
 * ## Why it is a singleton with a file behind it
 *
 * A scan runs for minutes with the screen on, which is exactly the window in which Android kills a
 * backgrounded process. Everything needed to finish the combine — who it is for, which account
 * holds them, which configs to point afterwards — has to survive that, or the operator comes back
 * to a finished scan and a combine sheet that has forgotten what it was for. `CombineCoach` learned
 * this the same way and persists for the same reason.
 *
 * ## Why the sources are stored rather than re-fetched
 *
 * They are read once, in Config Studio, where there is a network and an API key in hand. The
 * scanner has neither: it is a screen that works offline against addresses it already found, and
 * making its combine sheet depend on a live call to a Cloudflare account would make it fail in
 * exactly the conditions it exists for.
 */
object StudioCombineHandoff {

    private const val PREFS = "studio_combine_handoff"
    private const val KEY_JSON = "armed"

    /**
     * Who the combine is for, and what to combine.
     *
     * [configIds] is carried beside [nodes] rather than derived from them: the retarget afterwards
     * addresses configs by id, and a name parsed back out of a combined node's title would be a
     * string the operator can edit.
     */
    data class Target(
        val installationId: String,
        val userId: String,
        val username: String,
        val configIds: List<String>,
        val nodes: List<VpnNode>,
    ) {
        val isUsable: Boolean get() = nodes.isNotEmpty()
    }

    private val _armed = MutableStateFlow<Target?>(null)
    val armed: StateFlow<Target?> = _armed.asStateFlow()

    /** Read the stored handoff back, once, on the first screen that asks. */
    fun restore(context: Context) {
        if (_armed.value != null) return
        val raw = prefs(context).getString(KEY_JSON, null) ?: return
        _armed.value = try {
            parse(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    fun arm(context: Context, target: Target) {
        _armed.value = target
        prefs(context).edit().putString(KEY_JSON, encode(target).toString()).apply()
    }

    /**
     * Forget it.
     *
     * Called when the combine is done and when the operator leaves Config Studio's picker without
     * choosing — not when the scanner screen closes. A scan that was started for somebody and then
     * backgrounded must still be for them when the app comes back.
     */
    fun clear(context: Context) {
        _armed.value = null
        prefs(context).edit().remove(KEY_JSON).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(t: Target): JSONObject = JSONObject().apply {
        put("installationId", t.installationId)
        put("userId", t.userId)
        put("username", t.username)
        put("configIds", JSONArray().also { a -> t.configIds.forEach(a::put) })
        put("nodes", JSONArray().also { arr ->
            t.nodes.forEach { n ->
                arr.put(JSONObject().apply {
                    put("id", n.id)
                    put("name", n.name)
                    put("uri", n.uri)
                    put("type", n.type)
                    put("engineType", n.engineType)
                })
            }
        })
    }

    private fun parse(o: JSONObject): Target? {
        val installationId = o.optString("installationId").takeIf { it.isNotBlank() } ?: return null
        val userId = o.optString("userId").takeIf { it.isNotBlank() } ?: return null
        val ids = o.optJSONArray("configIds")?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        }.orEmpty()
        val nodes = o.optJSONArray("nodes")?.let { a ->
            (0 until a.length()).map { i ->
                val n = a.getJSONObject(i)
                VpnNode(
                    id = n.optString("id"),
                    name = n.optString("name"),
                    uri = n.optString("uri"),
                    type = n.optString("type", "VLESS"),
                    engineType = n.optString("engineType", "MLM"),
                )
            }
        }.orEmpty()
        return Target(installationId, userId, o.optString("username"), ids, nodes)
    }
}
