package com.mlmvpn.scanner.quick

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The servers the user keeps on the connect screen.
 *
 * The pool behind Quick Connect is thousands of entries and mostly dead at any moment; the
 * short list under the connect button is the opposite -- a handful the user has actually seen
 * work, in the order they were fastest. This is that list, and it survives restarts, because
 * a user who swept a country once should not have to sweep it again to get back to the server
 * they were using.
 *
 * A row is a plain snapshot rather than a reference into the catalog: the catalog is rebuilt
 * from the network every half hour and a saved server has to keep working across that.
 */
data class SavedServer(
    val id: String,
    val uri: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val country: String?,
    val flag: String?,
    val countryName: String?,
    /** Last measured real delay in ms; <= 0 means the last test failed. */
    var delay: Int,
    /** When [delay] was measured. 0 for a row that has never been re-tested here. */
    var testedAt: Long,
    /** True until the user has seen it once, so the list can mark what just arrived. */
    var isNew: Boolean = true,
) {
    /**
     * Tested, and it failed.
     *
     * Deliberately `== FAILED` rather than `<= 0`: a row being measured right now also holds a
     * non-positive delay, and treating that as dead put every in-flight row into the "down"
     * count and into whatever "delete the down ones" would have removed. Worse, a measurement
     * that never reported back left the row wearing that verdict permanently -- which is what
     * "some configs are never tested" looked like from the outside.
     */
    val isDead: Boolean get() = testedAt > 0 && delay == FAILED

    /** A measurement is in flight for this row. */
    val isTesting: Boolean get() = delay == TESTING

    companion object {
        /** The measurement ran and the server did not answer. */
        const val FAILED = -1

        /** A measurement is in flight; not a verdict. */
        const val TESTING = -2
    }
}

object QuickSavedStore {

    private const val FILE_NAME = "quick-saved.json"

    private var cache: MutableList<SavedServer>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun all(context: Context): List<SavedServer> {
        cache?.let { return it }
        val list = ArrayList<SavedServer>()
        try {
            val f = file(context)
            if (f.exists()) {
                val arr = JSONObject(f.readText()).optJSONArray("servers") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        SavedServer(
                            id = o.optString("id"),
                            uri = o.optString("uri"),
                            name = o.optString("name"),
                            host = o.optString("host"),
                            port = o.optInt("port"),
                            protocol = o.optString("protocol"),
                            country = o.optString("country").ifBlank { null },
                            flag = o.optString("flag").ifBlank { null },
                            countryName = o.optString("countryName").ifBlank { null },
                            delay = o.optInt("delay", -1),
                            testedAt = o.optLong("testedAt", 0L),
                            isNew = o.optBoolean("isNew", false),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            list.clear()
        }
        cache = list
        return list
    }

    @Synchronized
    private fun save(context: Context) {
        try {
            val arr = JSONArray()
            for (s in cache ?: return) {
                arr.put(
                    JSONObject()
                        .put("id", s.id).put("uri", s.uri).put("name", s.name)
                        .put("host", s.host).put("port", s.port).put("protocol", s.protocol)
                        .put("country", s.country ?: "").put("flag", s.flag ?: "")
                        .put("countryName", s.countryName ?: "")
                        .put("delay", s.delay).put("testedAt", s.testedAt).put("isNew", s.isNew)
                )
            }
            file(context).writeText(JSONObject().put("servers", arr).toString())
        } catch (e: Exception) {
            // Failing to persist the list must not fail the connection it was built for.
        }
    }

    /**
     * Add proven servers, keeping the ones already here.
     *
     * A server that is already saved has its delay refreshed rather than being added twice --
     * re-sweeping a country the user has swept before should improve the list, not duplicate it.
     * Returns how many rows were genuinely new.
     */
    @Synchronized
    fun addAll(context: Context, nodes: List<QuickNode>): Int {
        val list = all(context) as MutableList
        val byId = list.associateBy { it.id }
        var added = 0
        val now = System.currentTimeMillis()
        for (n in nodes) {
            // Pool servers are the app's own list and never touch the filesystem. Two reasons:
            // they must not be exportable at all, and `android:allowBackup="true"` copies
            // everything under filesDir into the user's Google Drive. They live in memory for
            // the session and are fetched fresh next time.
            if (n.source == MlmPoolClient.SOURCE) continue
            val existing = byId[n.id]
            if (existing != null) {
                existing.delay = n.delay
                existing.testedAt = now
            } else {
                list.add(
                    SavedServer(
                        id = n.id, uri = n.uri, name = n.name, host = n.host, port = n.port,
                        protocol = n.protocol, country = n.country, flag = n.flag,
                        countryName = n.countryName, delay = n.delay, testedAt = now, isNew = true,
                    )
                )
                added++
            }
        }
        list.sortBy { if (it.delay > 0) it.delay else Int.MAX_VALUE }
        save(context)
        return added
    }

    /**
     * Move a saved row into a measured country.
     *
     * Without this the correction only reached the catalog, so a server the trace had just
     * proved exits in Singapore kept showing a Canadian flag on the connect screen -- the one
     * list the user is actually looking at. The name carries the country prefix too, so it is
     * rewritten with it; the tag half is stable and does not move.
     */
    @Synchronized
    fun applyMeasuredCountry(context: Context, id: String, rawCode: String): Boolean {
        val row = (all(context) as MutableList).firstOrNull { it.id == id } ?: return false
        val measured = GeoLabel.countryFromCode(rawCode) ?: return false
        if (row.country == measured.code) return false
        val index = (all(context) as MutableList).indexOf(row)
        val tag = row.name.substringAfterLast('-', "")
        (all(context) as MutableList)[index] = row.copy(
            country = measured.code,
            flag = measured.flag,
            countryName = measured.name,
            name = if (tag.isNotBlank()) "${measured.code}-$tag" else row.name,
        )
        save(context)
        return true
    }

    @Synchronized
    fun updateResult(context: Context, id: String, delay: Int) {
        val row = (all(context) as MutableList).firstOrNull { it.id == id } ?: return
        row.delay = delay
        row.testedAt = System.currentTimeMillis()
        save(context)
    }

    /**
     * Put rows that never got their result back to how they were.
     *
     * A row is marked [SavedServer.TESTING] before its measurement starts. If the run ends
     * early -- stopped, or cancelled -- the rows it never reached would keep that marker
     * forever and read as configs the app refuses to test. Clearing to "not tested" is honest:
     * nothing was learned about them.
     */
    @Synchronized
    fun clearTestingMarkers(context: Context, ids: Collection<String>? = null) {
        val list = all(context) as MutableList
        var touched = false
        for (i in list.indices) {
            val row = list[i]
            if (!row.isTesting) continue
            if (ids != null && row.id !in ids) continue
            row.delay = SavedServer.FAILED
            row.testedAt = 0L          // 0 == never measured, so isDead stays false
            touched = true
        }
        if (touched) save(context)
    }

    @Synchronized
    fun resort(context: Context) {
        (all(context) as MutableList).sortBy { if (it.delay > 0) it.delay else Int.MAX_VALUE }
        save(context)
    }

    /** Clear the "new" marks once the user has seen the list. */
    @Synchronized
    fun markSeen(context: Context) {
        val list = all(context) as MutableList
        if (list.none { it.isNew }) return
        list.forEach { it.isNew = false }
        save(context)
    }

    /**
     * Remove rows and, unless [keepInPool] is set, blocklist them so no future refresh of the
     * catalog brings them back. Deleting a dead server is the user saying it is worthless, not
     * that they want to see it again in fifteen minutes.
     */
    @Synchronized
    fun remove(context: Context, ids: Collection<String>, keepInPool: Boolean = false) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        (all(context) as MutableList).removeAll { it.id in set }
        save(context)
        if (!keepInPool) QuickBlocklist.add(context, set)
    }

    @Synchronized
    fun removeDead(context: Context, keepInPool: Boolean = false) {
        remove(context, all(context).filter { it.isDead }.map { it.id }, keepInPool)
    }

    @Synchronized
    fun clear(context: Context, keepInPool: Boolean = true) {
        remove(context, all(context).map { it.id }, keepInPool)
    }

    // ---- who started the tunnel ------------------------------------------------------------

    private const val PREFS = "quick_connect"
    private const val KEY_LAST = "last_connected_id"

    /**
     * Remember that Quick Connect is what raised the current tunnel.
     *
     * The saved list used to answer this on its own: every server this screen could connect to
     * was in it, so "the running node id is in the list" meant "we started it". Pool servers
     * broke that, deliberately -- they are never written to disk -- and a pool connection
     * therefore looked like a stranger's, which is why the home screen lit the V2Ray lamp
     * instead of this one and why the connect button had to be told about them separately.
     *
     * An id is not a config: it identifies a row, carries no address, key or host, and cannot
     * be turned back into anything connectable. Storing it breaks none of the rules that keep
     * the pool's configs off the filesystem.
     */
    fun markConnected(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, id).apply()
    }

    fun lastConnectedId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST, null)?.takeIf { it.isNotBlank() }
}
