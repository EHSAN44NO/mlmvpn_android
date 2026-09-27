package com.mlmvpn.scanner.data

import android.content.Context
import com.mlmvpn.scanner.models.VpnNode
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

data class CloudGroup(
    val id: String,
    val accountId: String,
    val date: String,
    val title: String,
    val nodes: List<VpnNode>
)

data class ScannerGroup(
    val id: String,
    val date: String,
    val title: String,
    val sourceGroupCount: Int,
    val ipCount: Int,
    val sourceGroupId: String? = null,
    val nodes: List<VpnNode>,
    /**
     * The Config Studio subscriber this group was combined FOR, when it was combined for one.
     *
     * Null on every ordinary scanner group, which is what keeps the two kinds apart on a screen
     * that shows them in one list. A group carrying these three is the only one that can offer
     * «بروزرسانی ساب», because updating a subscription needs to know whose it is and which
     * Cloudflare account holds it — and guessing either from the group's title is how somebody
     * else's link gets rewritten.
     */
    val studioInstallationId: String? = null,
    val studioUserId: String? = null,
    val studioUsername: String? = null,
    /** The configs these were combined from, so the retarget knows what to point at the new IPs. */
    val studioConfigIds: List<String> = emptyList(),
) {
    /** True when this group belongs to a Config Studio subscriber rather than to a cloud group. */
    val isStudio: Boolean
        get() = !studioInstallationId.isNullOrBlank() && !studioUserId.isNullOrBlank()
}

data class IpArchive(
    val id: String,
    val date: String,
    val ips: List<String>
)

class GroupManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: GroupManager? = null

        operator fun invoke(context: Context): GroupManager {
            return instance ?: synchronized(this) {
                instance ?: GroupManager(context.applicationContext).also { instance = it }
            }
        }

        /** The one archive this app keeps; everything older is folded into it on load. */
        const val UNIFIED_ARCHIVE = "unified_archive"
    }

    /**
     * Guards the three lists.
     *
     * This is a process-wide singleton whose lists are plain `mutableListOf`, and they are touched
     * from at least three threads: the IO coroutine in `init`, a scan's teardown on
     * `Dispatchers.Default`, and every button on the main thread. A `clear()`/`addAll()` pair racing
     * a `for` loop over the same list is a ConcurrentModificationException, and an index captured
     * on one thread and used on another writes to whatever moved into that slot.
     */
    private val lock = Any()

    private val prefs = context.getSharedPreferences("group_manager_prefs", Context.MODE_PRIVATE)

    val cloudGroups = mutableListOf<CloudGroup>()
    val scannerGroups = mutableListOf<ScannerGroup>()
    val ipArchives = mutableListOf<IpArchive>()

    private val _cloudGroupsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<CloudGroup>>(emptyList())
    val cloudGroupsFlow: kotlinx.coroutines.flow.StateFlow<List<CloudGroup>> = _cloudGroupsFlow

    private val _scannerGroupsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ScannerGroup>>(emptyList())
    val scannerGroupsFlow: kotlinx.coroutines.flow.StateFlow<List<ScannerGroup>> = _scannerGroupsFlow

    private val _ipArchivesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<IpArchive>>(emptyList())
    val ipArchivesFlow: kotlinx.coroutines.flow.StateFlow<List<IpArchive>> = _ipArchivesFlow

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadCloudGroups()
            loadScannerGroups()
            loadIpArchives()
            _cloudGroupsFlow.value = cloudGroups.toList()
            _scannerGroupsFlow.value = scannerGroups.toList()
            _ipArchivesFlow.value = ipArchives.toList()
        }
    }

    fun loadCloudGroups() = synchronized(lock) {
        val jsonStr = prefs.getString("cloud_groups", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<CloudGroup>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nodesArr = obj.getJSONArray("nodes")
                val nodesList = mutableListOf<VpnNode>()
                for (j in 0 until nodesArr.length()) {
                    val nObj = nodesArr.getJSONObject(j)
                    nodesList.add(
                        VpnNode(
                            id = nObj.getString("id"),
                            name = nObj.getString("name"),
                            uri = nObj.getString("uri"),
                            type = nObj.getString("type"),
                            ping = nObj.optString("ping", "Test"),
                            addedAt = nObj.optLong("addedAt", System.currentTimeMillis()),
                            engineType = nObj.optString("engineType", "BPB")
                        )
                    )
                }
                list.add(
                    CloudGroup(
                        id = obj.getString("id"),
                        accountId = obj.getString("accountId"),
                        date = obj.getString("date"),
                        title = obj.optString("title", "Cloud Group"),
                        nodes = nodesList
                    )
                )
            }
            cloudGroups.clear()
            cloudGroups.addAll(list)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveCloudGroups() = synchronized(lock) {
        val arr = JSONArray()
        for (g in cloudGroups) {
            val obj = JSONObject()
            obj.put("id", g.id)
            obj.put("accountId", g.accountId)
            obj.put("date", g.date)
            obj.put("title", g.title)
            val nodesArr = JSONArray()
            for (n in g.nodes) {
                val nObj = JSONObject()
                nObj.put("id", n.id)
                nObj.put("name", n.name)
                nObj.put("uri", n.uri)
                nObj.put("type", n.type)
                nObj.put("ping", n.ping)
                nObj.put("addedAt", n.addedAt)
                nObj.put("engineType", n.engineType)
                nodesArr.put(nObj)
            }
            obj.put("nodes", nodesArr)
            arr.put(obj)
        }
        prefs.edit().putString("cloud_groups", arr.toString()).apply()
        _cloudGroupsFlow.value = cloudGroups.toList()
    }

    /**
     * Re-read the combined groups from disk.
     *
     * Public because the combine coach has to resume: after an interruption it needs the group it
     * already saved, which the in-memory list may not hold if the process was rebuilt.
     */
    fun loadScannerGroupsPublic() = loadScannerGroups()

    private fun loadScannerGroups() = synchronized(lock) {
        val jsonStr = prefs.getString("scanner_groups", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ScannerGroup>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nodesArr = obj.getJSONArray("nodes")
                val nodesList = mutableListOf<VpnNode>()
                for (j in 0 until nodesArr.length()) {
                    val nObj = nodesArr.getJSONObject(j)
                    nodesList.add(
                        VpnNode(
                            id = nObj.getString("id"),
                            name = nObj.getString("name"),
                            uri = nObj.getString("uri"),
                            type = nObj.getString("type"),
                            ping = nObj.optString("ping", "Test"),
                            addedAt = nObj.optLong("addedAt", System.currentTimeMillis()),
                            engineType = nObj.optString("engineType", "BPB")
                        )
                    )
                }
                list.add(
                    ScannerGroup(
                        id = obj.getString("id"),
                        date = obj.getString("date"),
                        title = obj.optString("title", "Scanner Group"),
                        sourceGroupCount = obj.getInt("sourceGroupCount"),
                        ipCount = obj.getInt("ipCount"),
                        sourceGroupId = obj.optString("sourceGroupId", null).takeIf { it != "null" },
                        nodes = nodesList,
                        studioInstallationId = obj.optString("studioInstallationId", "").takeIf { it.isNotBlank() },
                        studioUserId = obj.optString("studioUserId", "").takeIf { it.isNotBlank() },
                        studioUsername = obj.optString("studioUsername", "").takeIf { it.isNotBlank() },
                        studioConfigIds = obj.optJSONArray("studioConfigIds")?.let { ids ->
                            (0 until ids.length()).map { ids.getString(it) }
                        } ?: emptyList(),
                    )
                )
            }
            scannerGroups.clear()
            scannerGroups.addAll(list)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveScannerGroups() = synchronized(lock) {
        val arr = JSONArray()
        for (g in scannerGroups) {
            val obj = JSONObject()
            obj.put("id", g.id)
            obj.put("date", g.date)
            obj.put("title", g.title)
            obj.put("sourceGroupCount", g.sourceGroupCount)
            obj.put("ipCount", g.ipCount)
            g.sourceGroupId?.let { obj.put("sourceGroupId", it) }
            g.studioInstallationId?.let { obj.put("studioInstallationId", it) }
            g.studioUserId?.let { obj.put("studioUserId", it) }
            g.studioUsername?.let { obj.put("studioUsername", it) }
            if (g.studioConfigIds.isNotEmpty()) {
                obj.put("studioConfigIds", JSONArray().also { a -> g.studioConfigIds.forEach(a::put) })
            }
            val nodesArr = JSONArray()
            for (n in g.nodes) {
                val nObj = JSONObject()
                nObj.put("id", n.id)
                nObj.put("name", n.name)
                nObj.put("uri", n.uri)
                nObj.put("type", n.type)
                nObj.put("ping", n.ping)
                nObj.put("addedAt", n.addedAt)
                nObj.put("engineType", n.engineType)
                nodesArr.put(nObj)
            }
            obj.put("nodes", nodesArr)
            arr.put(obj)
        }
        prefs.edit().putString("scanner_groups", arr.toString()).apply()
        _scannerGroupsFlow.value = scannerGroups.toList()
    }

    /**
     * Load the archive, folding any historical multi-archive file into the single unified one.
     *
     * The stored `date` is the one written when the archive last CHANGED, and it is read back
     * rather than regenerated. It used to be stamped with `Date()` on every load, so the card
     * always read "last updated: now" no matter how old the addresses were -- a timestamp that
     * could never be wrong because it never said anything.
     */
    fun loadIpArchives() = synchronized(lock) {
        val jsonStr = prefs.getString("ip_archives", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<IpArchive>()
            val allIps = LinkedHashSet<String>()
            var storedDate = ""

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ipsArr = obj.getJSONArray("ips")
                for (j in 0 until ipsArr.length()) {
                    allIps.add(ipsArr.getString(j))
                }
                if (storedDate.isBlank()) storedDate = obj.optString("date", "")
            }

            if (allIps.isNotEmpty()) {
                list.add(
                    IpArchive(
                        id = UNIFIED_ARCHIVE,
                        date = storedDate.ifBlank { stampNow() },
                        ips = allIps.toList()
                    )
                )
            }

            ipArchives.clear()
            ipArchives.addAll(list)

            // If the original json had multiple archives, save the unified one back immediately
            if (arr.length() > 1) {
                writeIpArchives()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveIpArchives() = synchronized(lock) { writeIpArchives() }

    /**
     * Set the unified archive's contents, stamped with the time it actually changed.
     *
     * The one write path for the archive, so a caller cannot do read-modify-write across a lock
     * boundary. Both callers used to: the scan's teardown ran on a background thread and the
     * delete button on the main one, over the same plain `mutableListOf`, and an index captured
     * before the other side's `clear()` was how a save landed on the wrong element.
     */
    fun replaceUnifiedArchive(ips: List<String>) = synchronized(lock) {
        val distinct = ips.distinct()
        ipArchives.removeAll { it.id == UNIFIED_ARCHIVE }
        if (distinct.isNotEmpty()) {
            ipArchives.add(IpArchive(id = UNIFIED_ARCHIVE, date = stampNow(), ips = distinct))
        }
        writeIpArchives()
    }

    /**
     * Add addresses to the unified archive, returning (new, duplicates).
     *
     * Load, merge and write in one critical section. The caller used to do the three steps itself
     * from a scan's teardown thread while the delete button did its own from the main thread, so
     * a merge could be computed against a list that had already been replaced underneath it.
     */
    fun mergeIntoUnifiedArchive(ips: List<String>): Pair<Int, Int> = synchronized(lock) {
        loadIpArchives()
        val existing = ipArchives.find { it.id == UNIFIED_ARCHIVE }?.ips ?: emptyList()
        val incoming = ips.distinct()
        val duplicates = incoming.count { it in existing }
        replaceUnifiedArchive(existing + incoming)
        (incoming.size - duplicates) to duplicates
    }

    /** Drop the whole archive. */
    fun clearIpArchives() = synchronized(lock) {
        ipArchives.clear()
        writeIpArchives()
    }

    private fun stampNow(): String =
        "Last updated: " + java.text.SimpleDateFormat(
            "yyyy/MM/dd HH:mm", java.util.Locale.US
        ).format(java.util.Date())

    /** Caller holds [lock]. */
    private fun writeIpArchives() {
        val arr = JSONArray()
        for (a in ipArchives) {
            val obj = JSONObject()
            obj.put("id", a.id)
            obj.put("date", a.date)
            val ipsArr = JSONArray()
            for (ip in a.ips) {
                ipsArr.put(ip)
            }
            obj.put("ips", ipsArr)
            arr.put(obj)
        }
        prefs.edit().putString("ip_archives", arr.toString()).apply()
        _ipArchivesFlow.value = ipArchives.toList()
    }
}
