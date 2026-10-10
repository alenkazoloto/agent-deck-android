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
 * agent's name, [TurnGrouping.endsBlock] which one carries the tail corner, and
 * [TurnGrouping.carriesTime] which one — of a whole run of them — writes the time.
 * Get either wrong and the column still paints — it just stops reading as a conversation.
 */
class TurnGroupingTest {

    private fun turn(id: String, role: String, atMs: Long, streaming: Boolean = false) =
        MobileTurn(id = id, role = role, text = id, timestampMs = atMs, streaming = streaming)

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

    @Test
    fun `turns traded inside the pause carry one time between them, at the end`() {
        // The shape `conversation-portrait` photographs: alternating speakers a minute apart.
        // Every one of these closes a block, and exactly one of them may state the time.
        val turns = listOf(
            turn("a", "user", 1_000),
            turn("b", "assistant", 61_000),
            turn("c", "user", 121_000),
        )

        assertTrue(turns.indices.all { TurnGrouping.endsBlock(turns, it) })
        assertFalse(TurnGrouping.carriesTime(turns, 0))
        assertFalse(TurnGrouping.carriesTime(turns, 1))
        assertTrue(TurnGrouping.carriesTime(turns, 2))
    }

    @Test
    fun `a pause ends the run that precedes it`() {
        val turns = listOf(
            turn("a", "user", 1_000),
            turn("b", "assistant", 1_000 + TurnGrouping.GAP_MS + 1),
        )

        assertTrue(TurnGrouping.carriesTime(turns, 0))
        assertTrue(TurnGrouping.carriesTime(turns, 1))
    }

    @Test
    fun `a turn still being written states no time, and its run keeps one`() {
        val turns = listOf(turn("a", "user", 1_000), turn("b", "assistant", 61_000, streaming = true))

        assertTrue(TurnGrouping.endsBlock(turns, 1))
        assertFalse(TurnGrouping.carriesTime(turns, 1))
        // Otherwise a live conversation shows no clock anywhere: the run's last finished turn
        // defers to a turn that will never state a time.
        assertTrue(TurnGrouping.carriesTime(turns, 0))
    }

    @Test
    fun `a live turn opens its own block, so the stamp it defers cannot land mid-block`() {
        val turns = listOf(
            turn("a", "assistant", 1_000),
            turn("b", "assistant", 31_000, streaming = true),
        )

        assertTrue(TurnGrouping.startsBlock(turns, 1))
        assertTrue(TurnGrouping.endsBlock(turns, 0))
        assertTrue(TurnGrouping.carriesTime(turns, 0))
    }

    @Test
    fun `an unstamped turn states no time, and does not swallow its neighbour's`() {
        val turns = listOf(turn("a", "user", 1_000), turn("b", "assistant", 0))

        assertFalse(TurnGrouping.carriesTime(turns, 1))
        assertTrue(TurnGrouping.carriesTime(turns, 0))
    }
}
