package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.ui.TurnGrouping
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a speaker's block of bubbles starts and stops.
 *
 * Asserted rather than eyeballed because both ends are load-bearing and neither is visible in a
 * green build: [TurnGrouping.startsBlock] decides which bubble carries the avatar and the
 * agent's name, [TurnGrouping.endsBlock] which one carries the tail corner and the timestamp.
 * Get either wrong and the column still paints — it just stops reading as a conversation.
 */
class TurnGroupingTest {

    private fun turn(id: String, role: String, atMs: Long) =
        MobileTurn(id = id, role = role, text = id, timestampMs = atMs)

    @Test
    fun `the first turn always opens a block`() {
        assertTrue(TurnGrouping.startsBlock(listOf(turn("a", "assistant", 1_000)), 0))
    }

    @Test
    fun `a change of speaker opens a block`() {
        val turns = listOf(turn("a", "assistant", 1_000), turn("b", "user", 2_000))

        assertTrue(TurnGrouping.startsBlock(turns, 1))
        assertTrue(TurnGrouping.endsBlock(turns, 0))
    }

    @Test
    fun `the same speaker moments later continues the block`() {
        val turns = listOf(turn("a", "assistant", 1_000), turn("b", "assistant", 61_000))

        assertFalse(TurnGrouping.startsBlock(turns, 1))
        assertFalse(TurnGrouping.endsBlock(turns, 0))
        assertTrue(TurnGrouping.endsBlock(turns, 1))
    }

    @Test
    fun `the same speaker after a long pause starts a new block`() {
        val turns = listOf(
            turn("a", "assistant", 1_000),
            turn("b", "assistant", 1_000 + TurnGrouping.GAP_MS + 1),
        )

        assertTrue(TurnGrouping.startsBlock(turns, 1))
    }

    @Test
    fun `a missing timestamp splits rather than silently merging`() {
        // An unknown gap is not a short one: a turn the plugin sent without a stamp must not
        // inherit the previous bubble's block and lose its own avatar and time.
        val turns = listOf(turn("a", "assistant", 1_000), turn("b", "assistant", 0))

        assertTrue(TurnGrouping.startsBlock(turns, 1))
    }

    @Test
    fun `the last turn always closes its block`() {
        val turns = listOf(turn("a", "assistant", 1_000), turn("b", "assistant", 2_000))

        assertTrue(TurnGrouping.endsBlock(turns, 1))
    }
}
