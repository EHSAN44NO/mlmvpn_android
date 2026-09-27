package com.mlmvpn.scanner.ui.home

/**
 * A folder as it is stored: its name and its members' destination ids, in order.
 *
 * [title] is null while the folder still carries its factory name, so switching the app's language
 * renames it too. Only a name the user typed is stored as text.
 */
data class FolderRecord(val title: String?, val apps: List<String>)

/**
 * The home board as ids: [order] holds destination ids and `@key` references to [folders].
 *
 * Plain strings on purpose -- the rules below are the part that has to be right on every upgrade,
 * and keeping them free of Android and Compose types is what lets a JVM test pin them down.
 */
data class BoardLayout(val order: List<String>, val folders: Map<String, FolderRecord>)

/**
 * How a saved arrangement is brought up to date with the destinations this release has.
 *
 * The same contract [HomeLayoutStore] always had -- nothing a user arranged is thrown away, ids that
 * no longer exist leave no gap, and a feature shipped today appears at the end of their board
 * instead of resetting it -- extended to folders, the way the Windows app keeps them (`apps.js ›
 * DEFAULT_FOLDERS`, `mv-folders`): a destination lives in exactly one place, an empty folder is no
 * folder, and a new destination that belongs to a factory folder the user still has goes into it.
 */
object BoardLayoutRules {

    const val FOLDER_PREFIX = "@"

    /** Keys the store accepts: short, ASCII, and never able to collide with a destination id. */
    private val KEY_RE = Regex("^[a-z0-9_-]{1,40}$", RegexOption.IGNORE_CASE)

    fun isFolderRef(id: String): Boolean = id.startsWith(FOLDER_PREFIX)
    fun folderRef(key: String): String = FOLDER_PREFIX + key
    fun folderKey(ref: String): String = ref.removePrefix(FOLDER_PREFIX)

    /**
     * @param savedOrder the stored top level, or null when the user never arranged anything.
     * @param savedFolders the stored folders, or null when none were ever written -- which is
     *   different from an empty map: null on an old layout is what triggers the one-time move of
     *   the factory folders' members off the board and into their folder.
     * @param migrated whether that one-time move already ran for this install.
     * @param known every destination this release has, in factory order.
     * @param boardDefault a fresh board's top level, with `@key` references.
     * @param defaultFolders the folders a fresh board starts with.
     */
    fun reconcile(
        savedOrder: List<String>?,
        savedFolders: Map<String, FolderRecord>?,
        migrated: Boolean,
        known: List<String>,
        boardDefault: List<String>,
        defaultFolders: Map<String, List<String>>,
    ): BoardLayout {
        val knownSet = known.toSet()
        val order: MutableList<String>
        val folders = LinkedHashMap<String, FolderRecord>()

        if (savedOrder == null) {
            order = boardDefault.toMutableList()
            defaultFolders.forEach { (key, apps) -> folders[key] = FolderRecord(null, apps) }
        } else {
            order = savedOrder.toMutableList()
            savedFolders?.let { folders.putAll(it) }
            if (!migrated && savedFolders == null) {
                // A board arranged before folders existed. Its tools move into their folder once,
                // and the folder takes the place of the first of them -- where the user already
                // looked for that group.
                defaultFolders.forEach { (key, apps) ->
                    if (folders.containsKey(key)) return@forEach
                    val at = order.indexOfFirst { it in apps }
                    order.removeAll { it in apps }
                    val ref = folderRef(key)
                    if (ref !in order) order.add(if (at < 0) order.size else minOf(at, order.size), ref)
                    folders[key] = FolderRecord(null, apps)
                }
            }
        }

        // One place per destination, only destinations that still exist, and no empty folders.
        val claimed = HashSet<String>()
        val cleanFolders = LinkedHashMap<String, FolderRecord>()
        folders.forEach { (key, record) ->
            if (!KEY_RE.matches(key)) return@forEach
            val apps = record.apps.filter { it in knownSet && claimed.add(it) }
            if (apps.isNotEmpty()) cleanFolders[key] = record.copy(apps = apps)
        }

        val top = ArrayList<String>()
        val seenTop = HashSet<String>()
        for (id in order) {
            val keep = if (isFolderRef(id)) {
                cleanFolders.containsKey(folderKey(id))
            } else {
                id in knownSet && id !in claimed
            }
            if (keep && seenTop.add(id)) top.add(id)
        }
        // A folder the order lost track of is still the user's: it goes at the end, not away.
        cleanFolders.keys.forEach { key ->
            val ref = folderRef(key)
            if (seenTop.add(ref)) top.add(ref)
        }

        // Destinations this arrangement has never seen: into their factory folder when the user
        // still has it, otherwise onto the end of the board.
        for (id in known) {
            if (id in claimed || id in seenTop) continue
            val home = defaultFolders.entries
                .firstOrNull { (key, apps) -> id in apps && cleanFolders.containsKey(key) }
                ?.key
            if (home != null) {
                val record = cleanFolders.getValue(home)
                cleanFolders[home] = record.copy(apps = record.apps + id)
                claimed.add(id)
            } else {
                top.add(id)
                seenTop.add(id)
            }
        }

        return BoardLayout(top, cleanFolders)
    }

    /**
     * Drops [draggedId] onto [targetId]: into the target folder, or, onto a plain destination, into
     * a new folder called [newKey] that takes the target's place. A folder is never put inside a
     * folder -- the board has one level, as on iOS and on the Windows desktop.
     *
     * Returns null when the drop does not make sense (unknown ids, a folder being dropped).
     */
    fun merge(layout: BoardLayout, draggedId: String, targetId: String, newKey: String): BoardLayout? {
        if (draggedId == targetId || isFolderRef(draggedId)) return null
        if (draggedId !in layout.order || targetId !in layout.order) return null
        val folders = LinkedHashMap(layout.folders)
        val order = layout.order.toMutableList()
        if (isFolderRef(targetId)) {
            val key = folderKey(targetId)
            val record = folders[key] ?: return null
            folders[key] = record.copy(apps = record.apps + draggedId)
            order.remove(draggedId)
        } else {
            if (!KEY_RE.matches(newKey) || folders.containsKey(newKey)) return null
            val at = order.indexOf(targetId)
            order[at] = folderRef(newKey)
            order.remove(draggedId)
            folders[newKey] = FolderRecord(null, listOf(targetId, draggedId))
        }
        return BoardLayout(order, folders)
    }

    /**
     * Takes [appId] out of folder [key] and puts it on the board right after the folder. A folder
     * left with nothing in it disappears, and the destination takes the place it had.
     */
    fun moveOut(layout: BoardLayout, key: String, appId: String): BoardLayout? {
        val record = layout.folders[key] ?: return null
        if (appId !in record.apps) return null
        val folders = LinkedHashMap(layout.folders)
        val order = layout.order.toMutableList()
        val ref = folderRef(key)
        val at = order.indexOf(ref).let { if (it < 0) order.size - 1 else it }
        val rest = record.apps - appId
        if (rest.isEmpty()) {
            folders.remove(key)
            if (at in order.indices && order[at] == ref) order[at] = appId else order.add(appId)
        } else {
            folders[key] = record.copy(apps = rest)
            order.add(minOf(at + 1, order.size), appId)
        }
        return BoardLayout(order, folders)
    }

    /** Renames folder [key]; a blank name, or the factory one, goes back to following the language. */
    fun rename(layout: BoardLayout, key: String, title: String?, factoryTitle: String): BoardLayout? {
        val record = layout.folders[key] ?: return null
        val clean = title?.trim()?.take(40)?.takeIf { it.isNotEmpty() && it != factoryTitle }
        return layout.copy(folders = LinkedHashMap(layout.folders).apply { put(key, record.copy(title = clean)) })
    }

    /** Puts folder [key]'s members in the order given; anything not a member is ignored. */
    fun reorderInside(layout: BoardLayout, key: String, apps: List<String>): BoardLayout? {
        val record = layout.folders[key] ?: return null
        val kept = apps.filter { it in record.apps }.distinct()
        val rest = record.apps.filter { it !in kept }
        return layout.copy(
            folders = LinkedHashMap(layout.folders).apply { put(key, record.copy(apps = kept + rest)) },
        )
    }
}
