package com.mlmvpn.scanner.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * What the store has installed, and where.
 *
 *     filesDir/store/items/<id>/current/…   the version in use
 *     filesDir/store/items/<id>/prev/…      the one before it — «برگشت» swaps them back
 *     filesDir/store/installed.json         version, source and time per item
 *     filesDir/store/history.json           «بروزرسانی‌های اخیر»
 *
 * An item with no `current` folder is on what the app shipped. Rolling back past the first store
 * install simply deletes `current`, which puts the shipped copy back in use — the app's own files
 * are never touched, so there is always something to fall back to.
 */
object StoreFiles {

    data class Installed(
        val version: String,
        val from: String,
        val at: Long,
        /** Version of the `prev` folder, or null when going back means "what the app shipped". */
        val prevVersion: String?,
        val quarantined: String? = null,
    )

    private fun root(context: Context) = File(context.applicationContext.filesDir, "store")
    fun itemDir(context: Context, id: String) = File(root(context), "items/$id")
    fun currentDir(context: Context, id: String) = File(itemDir(context, id), "current")
    private fun prevDir(context: Context, id: String) = File(itemDir(context, id), "prev")
    private fun stateFile(context: Context) = File(root(context), "installed.json")
    private fun historyFile(context: Context) = File(root(context), "history.json")

    private val lock = Any()

    private fun readState(context: Context): JSONObject =
        runCatching { JSONObject(stateFile(context).readText()) }.getOrElse { JSONObject() }

    private fun writeState(context: Context, j: JSONObject) {
        stateFile(context).apply { parentFile?.mkdirs() }.writeText(j.toString())
    }

    fun installed(context: Context, id: String): Installed? = synchronized(lock) {
        val o = readState(context).optJSONObject(id) ?: return null
        if (!currentDir(context, id).exists()) return null
        Installed(
            version = o.optString("version"),
            from = o.optString("from"),
            at = o.optLong("at"),
            prevVersion = o.optString("prevVersion").ifBlank { null },
            quarantined = o.optString("quarantined").ifBlank { null },
        )
    }

    /** The store's copy of one file, when it is installed and not set aside. */
    fun activeFile(context: Context, id: String, name: String): File? {
        val inst = installed(context, id) ?: return null
        if (inst.quarantined != null) return null
        return File(currentDir(context, id), name).takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Make [staged] (a folder holding the new files) the version in use.
     *
     * The old `current` becomes `prev` — one step of history, which is what «برگشت» needs and
     * all it needs. Rename, not copy: on one filesystem it is atomic, so a process killed halfway
     * leaves either the old version or the new one, never half of each.
     */
    fun activate(context: Context, id: String, staged: File, version: String, from: String) = synchronized(lock) {
        val cur = currentDir(context, id)
        val prev = prevDir(context, id)
        val state = readState(context)
        val old = state.optJSONObject(id)
        val hadCurrent = cur.exists() && old != null
        prev.deleteRecursively()
        if (hadCurrent) cur.renameTo(prev) else cur.deleteRecursively()
        cur.parentFile?.mkdirs()
        if (!staged.renameTo(cur)) {
            staged.copyRecursively(cur, overwrite = true)
            staged.deleteRecursively()
        }
        state.put(id, JSONObject().apply {
            put("version", version)
            put("from", from)
            put("at", System.currentTimeMillis())
            if (hadCurrent) put("prevVersion", old!!.optString("version"))
        })
        writeState(context, state)
    }

    /** Back one step. Returns the version now in use, or null for "what the app shipped". */
    fun rollback(context: Context, id: String): String? = synchronized(lock) {
        val cur = currentDir(context, id)
        val prev = prevDir(context, id)
        val state = readState(context)
        val o = state.optJSONObject(id)
        cur.deleteRecursively()
        return if (prev.exists() && o != null && o.optString("prevVersion").isNotBlank()) {
            prev.renameTo(cur)
            val v = o.optString("prevVersion")
            state.put(id, JSONObject().apply {
                put("version", v); put("from", "rollback"); put("at", System.currentTimeMillis())
            })
            writeState(context, state)
            v
        } else {
            prev.deleteRecursively()
            state.remove(id)
            writeState(context, state)
            null
        }
    }

    /** Set a store copy aside without deleting it: a JNI engine that did not survive loading. */
    fun quarantine(context: Context, id: String, reason: String) = synchronized(lock) {
        val state = readState(context)
        val o = state.optJSONObject(id) ?: return@synchronized
        o.put("quarantined", reason)
        writeState(context, state)
    }

    fun canRollback(context: Context, id: String): Boolean = installed(context, id) != null

    // ── assets the app reads through the store ─────────────────────────────────────────────────

    /**
     * Open an asset, preferring the store's newer copy.
     *
     * Every place that reads a worker's code or a data file the store can update goes through
     * this instead of `context.assets.open`, so an update reaches the next deploy and the next
     * connection without either of them knowing the store exists.
     */
    fun open(context: Context, asset: String): InputStream {
        val id = StoreCatalog.itemIdForAsset(asset)
        if (id != null) {
            activeFile(context, id, asset)?.let { return it.inputStream() }
        }
        return context.assets.open(asset)
    }

    fun readText(context: Context, asset: String): String =
        open(context, asset).bufferedReader().use { it.readText() }

    // ── history ─────────────────────────────────────────────────────────────────────────────────

    fun addHistory(context: Context, h: StoreHistory) = synchronized(lock) {
        val arr = runCatching { JSONArray(historyFile(context).readText()) }.getOrElse { JSONArray() }
        val out = JSONArray().put(JSONObject().apply {
            put("id", h.id); put("title", h.title); put("from", h.from ?: ""); put("to", h.to)
            put("at", h.at); put("notes", h.notes.take(2000))
        })
        for (i in 0 until minOf(arr.length(), 39)) out.put(arr.get(i))
        historyFile(context).apply { parentFile?.mkdirs() }.writeText(out.toString())
    }

    fun history(context: Context): List<StoreHistory> {
        val arr = runCatching { JSONArray(historyFile(context).readText()) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            StoreHistory(it.optString("id"), it.optString("title"), it.optString("from").ifBlank { null },
                it.optString("to"), it.optLong("at"), it.optString("notes"))
        }
    }
}
