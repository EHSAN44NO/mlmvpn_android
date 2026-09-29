package com.mlmvpn.scanner.ui.arena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaMoodsTest {

    private fun ArenaMoods.feed(order: List<String>, progress: Float = 0.3f, phase: Any = "LAT", retired: Set<String> = emptySet(),
                                gaps: Map<String, Double> = order.withIndex().associate { it.value to (100.0 - it.index * 10) }) =
        update(order, gaps, retired, progress, phase, 0L).associate { it.panelId to it.emoji }

    @Test
    fun overtakes_and_lead_changes() {
        val m = ArenaMoods()
        assertTrue(m.feed(listOf("A", "B", "C")).isEmpty())          // first look: nothing to compare
        val moods = m.feed(listOf("B", "A", "C"))
        assertEquals("🚀", moods["B"])                                // takes the lead for the first time
        assertEquals("😱", moods["A"])                                // loses the lead
        val back = m.feed(listOf("A", "B", "C"))
        assertEquals("😤", back["A"])                                 // retakes a lead it once had
    }

    @Test
    fun bottom_and_middle_moves() {
        val m = ArenaMoods()
        m.feed(listOf("A", "B", "C", "D"))
        val moods = m.feed(listOf("A", "D", "B", "C"))
        assertEquals("🤩", moods["D"])                                // off the bottom
        assertEquals("😰", moods["C"])                                // dropped to last
        assertEquals("😠", moods["B"])                                // overtaken in the middle
    }

    @Test
    fun final_stretch_once_and_retirement() {
        val m = ArenaMoods()
        m.feed(listOf("A", "B", "C"))
        val fin = m.feed(listOf("A", "B", "C"), progress = 0.8f, gaps = mapOf("A" to 90.0, "B" to 87.0, "C" to 40.0))
        assertEquals("🏆", fin["A"])
        assertEquals("🔥", fin["B"])                                  // within a few points: pushing
        assertEquals("😓", fin["C"])
        assertTrue(m.feed(listOf("A", "B", "C"), progress = 0.9f).isEmpty())   // said once, not every frame
        assertEquals("😵", m.feed(listOf("A", "B"), progress = 0.9f, retired = setOf("C"))["C"])
    }
}
