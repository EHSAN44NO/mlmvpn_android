package com.mlmvpn.scanner.ui.home

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** One cell of the home board: a destination, or a folder of them. */
sealed class BoardItem {
    abstract val id: String

    data class App(val app: HomeApp) : BoardItem() {
        override val id: String get() = app.id
    }

    /**
     * A named group of destinations, opened as a panel above the board.
     *
     * [title] is null while the folder carries its factory name -- see [HomeDestinations.folderTitleRes].
     */
    data class Folder(val key: String, val title: String?, val apps: List<HomeApp>) : BoardItem() {
        override val id: String get() = BoardLayoutRules.folderRef(key)
    }
}

/**
 * Where the user's icon arrangement lives.
 *
 * Only ids are stored -- the order of the top level, with `@key` for a folder, and each folder's
 * name and members -- never the icons or labels themselves, which belong to [HomeDestinations] and
 * change with every release. [BoardLayoutRules.reconcile] brings a saved arrangement up to date with
 * what this release has, so shipping a feature adds an icon instead of resetting anyone's board and
 * removing one leaves no gap.
 */
object HomeLayoutStore {

    private const val KEY = "home_icon_order"
    private const val KEY_FOLDERS = "home_folders"
    /** Set once the tools have been moved into their folder on a board arranged before folders. */
    private const val KEY_FOLDERS_MIGRATED = "home_folders_v1"

    /** 4 columns x 6 rows. Past this the grid would need horizontal paging, which it has not got. */
    const val CAPACITY = 24

    fun load(context: Context): List<BoardItem> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedOrder = prefs.getString(KEY, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        val savedFolders = prefs.getString(KEY_FOLDERS, null)?.let(::parseFolders)
        val migrated = prefs.getBoolean(KEY_FOLDERS_MIGRATED, false)

        val layout = BoardLayoutRules.reconcile(
            savedOrder = savedOrder,
            savedFolders = savedFolders,
            migrated = migrated,
            known = HomeDestinations.GRID_DEFAULT.map { it.id },
            boardDefault = HomeDestinations.BOARD_DEFAULT,
            defaultFolders = HomeDestinations.DEFAULT_FOLDERS,
        )
        // The one-time move into the folder is written back straight away, so it happens exactly
        // once even for someone who never touches the board again.
        if (savedOrder != null && !migrated) write(context, layout)
        return toItems(layout)
    }

    fun save(context: Context, items: List<BoardItem>) = write(context, toLayout(items))

    fun reset(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY)
            .remove(KEY_FOLDERS)
            .remove(KEY_FOLDERS_MIGRATED)
            .apply()
    }

    fun toLayout(items: List<BoardItem>): BoardLayout {
        val folders = LinkedHashMap<String, FolderRecord>()
        items.forEach { item ->
            if (item is BoardItem.Folder) folders[item.key] = FolderRecord(item.title, item.apps.map { it.id })
        }
        return BoardLayout(items.map { it.id }, folders)
    }

    fun toItems(layout: BoardLayout): List<BoardItem> = layout.order.mapNotNull { id ->
        if (BoardLayoutRules.isFolderRef(id)) {
            val key = BoardLayoutRules.folderKey(id)
            val record = layout.folders[key] ?: return@mapNotNull null
            val apps = record.apps.mapNotNull { HomeDestinations.gridById(it) }
            if (apps.isEmpty()) null else BoardItem.Folder(key, record.title, apps)
        } else {
            HomeDestinations.gridById(id)?.let { BoardItem.App(it) }
        }
    }

    private fun write(context: Context, layout: BoardLayout) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY, layout.order.joinToString(","))
            .putString(KEY_FOLDERS, foldersJson(layout.folders))
            .putBoolean(KEY_FOLDERS_MIGRATED, true)
            .apply()
    }

    private fun foldersJson(folders: Map<String, FolderRecord>): String {
        val root = JSONObject()
        folders.forEach { (key, record) ->
            root.put(key, JSONObject().apply {
                record.title?.let { put("title", it) }
                put("apps", JSONArray(record.apps))
            })
        }
        return root.toString()
    }

    /** A damaged value is treated as "no folders saved" rather than taking the board down. */
    private fun parseFolders(raw: String): Map<String, FolderRecord>? = runCatching {
        val root = JSONObject(raw)
        val out = LinkedHashMap<String, FolderRecord>()
        root.keys().forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            val arr = obj.optJSONArray("apps") ?: return@forEach
            val apps = (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            val title = if (obj.has("title") && !obj.isNull("title")) obj.optString("title") else null
            out[key] = FolderRecord(title, apps)
        }
        out
    }.getOrNull()
}
