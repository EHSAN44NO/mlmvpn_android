package com.mlmvpn.scanner.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home board's folder rules. Everything here runs on EVERY launch against whatever a user saved
 * in an older release, so each case is an upgrade someone will actually go through.
 */
class BoardLayoutRulesTest {

    private val tools = listOf("fixed_ip", "sublink", "lan", "usage", "tutorial")
    private val known = listOf(
        "masque", "wireguard", "psiphon", "tor", "quick", "fixed_ip", "sublink", "lan",
        "store", "usage", "tutorial", "emergency_2",
    )
    private val boardDefault = listOf("masque", "wireguard", "psiphon", "tor", "quick", "store", "@tools", "emergency_2")
    private val defaults = mapOf("tools" to tools)

    private fun reconcile(order: List<String>?, folders: Map<String, FolderRecord>?, migrated: Boolean) =
        BoardLayoutRules.reconcile(order, folders, migrated, known, boardDefault, defaults)

    private fun everyId(layout: BoardLayout): List<String> =
        layout.order.flatMap { id ->
            if (BoardLayoutRules.isFolderRef(id)) layout.folders.getValue(BoardLayoutRules.folderKey(id)).apps
            else listOf(id)
        }

    @Test
    fun freshInstallGetsTheToolsFolder() {
        val l = reconcile(null, null, migrated = false)
        assertEquals(boardDefault, l.order)
        assertEquals(tools, l.folders.getValue("tools").apps)
        assertNull(l.folders.getValue("tools").title)
        assertEquals(known.sorted(), everyId(l).sorted())
    }

    @Test
    fun anOldBoardMovesItsToolsIntoTheFolderOnce() {
        // Arranged before folders existed: the tools sit on the board, one of them first.
        val saved = listOf("usage", "masque", "fixed_ip", "tor", "sublink", "lan", "tutorial", "store")
        val l = reconcile(saved, null, migrated = false)
        // The folder takes the place of the first tool; the tools leave the top level.
        assertEquals("@tools", l.order.first())
        tools.forEach { assertFalse(it, it in l.order) }
        assertEquals(tools, l.folders.getValue("tools").apps)
        // Nothing lost, nothing doubled.
        assertEquals(known.sorted(), everyId(l).sorted())
    }

    @Test
    fun theMoveDoesNotRepeatAfterTheUserTakesSomethingOut() {
        // Migrated already; the user then dragged "lan" back onto the board.
        val saved = listOf("masque", "lan", "@tools", "tor")
        val folders = mapOf("tools" to FolderRecord(null, listOf("fixed_ip", "sublink", "usage", "tutorial")))
        val l = reconcile(saved, folders, migrated = true)
        assertTrue("lan" in l.order)
        assertFalse("lan" in l.folders.getValue("tools").apps)
    }

    @Test
    fun aRemovedDestinationLeavesNoGapAnywhere() {
        val saved = listOf("masque", "emergency_vercel", "@tools")
        val folders = mapOf("tools" to FolderRecord(null, listOf("fixed_ip", "gone_tool")))
        val l = reconcile(saved, folders, migrated = true)
        assertFalse("emergency_vercel" in l.order)
        assertFalse("gone_tool" in l.folders.getValue("tools").apps)
    }

    @Test
    fun aNewToolGoesIntoTheFolderTheUserStillHas() {
        // Saved when the folder held only two tools; the release added the rest.
        val saved = listOf("masque", "wireguard", "psiphon", "tor", "quick", "store", "@tools", "emergency_2")
        val folders = mapOf("tools" to FolderRecord("Mine", listOf("usage", "fixed_ip")))
        val l = reconcile(saved, folders, migrated = true)
        val inFolder = l.folders.getValue("tools").apps
        assertEquals(listOf("usage", "fixed_ip"), inFolder.take(2))
        assertTrue(inFolder.containsAll(tools))
        assertEquals("Mine", l.folders.getValue("tools").title)
    }

    @Test
    fun anEmptyFolderIsNoFolder() {
        val saved = listOf("masque", "@tools")
        val folders = mapOf("tools" to FolderRecord(null, emptyList()), "f1" to FolderRecord(null, listOf("nope")))
        val l = reconcile(saved, folders, migrated = true)
        assertFalse("@tools" in l.order)
        assertFalse("@f1" in l.order)
        // The tools the deleted folder would have held come back onto the board.
        assertTrue(l.order.containsAll(tools))
    }

    @Test
    fun oneDestinationLivesInOnePlace() {
        val saved = listOf("masque", "lan", "@a", "@b")
        val folders = mapOf(
            "a" to FolderRecord(null, listOf("lan", "tor")),
            "b" to FolderRecord(null, listOf("tor", "quick")),
        )
        val l = reconcile(saved, folders, migrated = true)
        val all = everyId(l)
        assertEquals(all.size, all.toSet().size)
        assertEquals(listOf("lan", "tor"), l.folders.getValue("a").apps)
        assertEquals(listOf("quick"), l.folders.getValue("b").apps)
    }

    @Test
    fun droppingAnAppOnAnAppMakesAFolderInItsPlace() {
        val base = BoardLayout(listOf("masque", "tor", "quick"), emptyMap())
        val l = BoardLayoutRules.merge(base, draggedId = "quick", targetId = "masque", newKey = "fnew")
        assertNotNull(l)
        assertEquals(listOf("@fnew", "tor"), l!!.order)
        assertEquals(listOf("masque", "quick"), l.folders.getValue("fnew").apps)
    }

    @Test
    fun droppingAnAppOnAFolderPutsItInside() {
        val base = BoardLayout(listOf("masque", "@tools"), mapOf("tools" to FolderRecord(null, listOf("lan"))))
        val l = BoardLayoutRules.merge(base, "masque", "@tools", "unused")!!
        assertEquals(listOf("@tools"), l.order)
        assertEquals(listOf("lan", "masque"), l.folders.getValue("tools").apps)
    }

    @Test
    fun aFolderIsNeverPutInsideAnother() {
        val base = BoardLayout(listOf("@a", "@b"), mapOf("a" to FolderRecord(null, listOf("x")), "b" to FolderRecord(null, listOf("y"))))
        assertNull(BoardLayoutRules.merge(base, "@a", "@b", "k"))
    }

    @Test
    fun movingTheLastAppOutDissolvesTheFolderInPlace() {
        val base = BoardLayout(listOf("masque", "@tools", "tor"), mapOf("tools" to FolderRecord(null, listOf("lan"))))
        val l = BoardLayoutRules.moveOut(base, "tools", "lan")!!
        assertEquals(listOf("masque", "lan", "tor"), l.order)
        assertTrue(l.folders.isEmpty())
    }

    @Test
    fun movingOneAppOutPutsItBesideTheFolder() {
        val base = BoardLayout(listOf("masque", "@tools", "tor"), mapOf("tools" to FolderRecord(null, listOf("lan", "usage"))))
        val l = BoardLayoutRules.moveOut(base, "tools", "lan")!!
        assertEquals(listOf("masque", "@tools", "lan", "tor"), l.order)
        assertEquals(listOf("usage"), l.folders.getValue("tools").apps)
    }

    @Test
    fun theFactoryNameIsNotStoredAsText() {
        val base = BoardLayout(listOf("@tools"), mapOf("tools" to FolderRecord("Old", listOf("lan"))))
        assertNull(BoardLayoutRules.rename(base, "tools", " Tools ", factoryTitle = "Tools")!!.folders.getValue("tools").title)
        assertNull(BoardLayoutRules.rename(base, "tools", "   ", factoryTitle = "Tools")!!.folders.getValue("tools").title)
        assertEquals("Work", BoardLayoutRules.rename(base, "tools", "Work", factoryTitle = "Tools")!!.folders.getValue("tools").title)
    }
}
