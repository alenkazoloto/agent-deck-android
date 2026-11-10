package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.TurnRevealPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a Prompt cache row's instant lands in the turns held: the desk's "first message at or after", never an older one. */
class TurnRevealPlanTest {

    private fun turn(id: String, at: Long) = MobileTurn(id, "assistant", id, timestampMs = 9_999, recordAtMs = at)

    private val turns = listOf(turn("a", 100), turn("b", 200), turn("c", 300))

    @Test
    fun `lands on the first turn written at or after the instant`() {
        assertEquals(TurnRevealPlan.Show(1), TurnRevealPlan.of(turns, 150, earlierAvailable = false))
        assertEquals("an exact hit is that turn, not the next", TurnRevealPlan.Show(1), TurnRevealPlan.of(turns, 200, earlierAvailable = false))
    }

    @Test
    fun `never lands on an older turn`() {
        assertEquals("newer than everything held is not a turn to show", TurnRevealPlan.Gone, TurnRevealPlan.of(turns, 301, earlierAvailable = false))
        assertEquals(TurnRevealPlan.Gone, TurnRevealPlan.of(turns, 301, earlierAvailable = true))
    }

    @Test
    fun `the oldest turn held may hide an earlier match, so earlier history is asked for first`() {
        assertEquals(TurnRevealPlan.LoadEarlier, TurnRevealPlan.of(turns, 50, earlierAvailable = true))
        assertEquals("with nothing above it, the oldest turn is the answer", TurnRevealPlan.Show(0), TurnRevealPlan.of(turns, 50, earlierAvailable = false))
        assertEquals("a turn with an older one above it is settled", TurnRevealPlan.Show(1), TurnRevealPlan.of(turns, 150, earlierAvailable = true))
    }

    @Test
    fun `turns the machine gave no record time cannot be landed on`() {
        val unstamped = listOf(turn("a", 0), turn("b", 0))
        assertEquals(TurnRevealPlan.Gone, TurnRevealPlan.of(unstamped, 100, earlierAvailable = true))
        assertEquals(TurnRevealPlan.Gone, TurnRevealPlan.of(emptyList(), 100, earlierAvailable = true))
    }

    @Test
    fun `a tool-only turn sharing the previous message's time does not steal the landing`() {
        val shared = listOf(turn("say", 100), turn("tools", 100), turn("next", 200))
        assertEquals(TurnRevealPlan.Show(0), TurnRevealPlan.of(shared, 100, earlierAvailable = false))
    }
}
