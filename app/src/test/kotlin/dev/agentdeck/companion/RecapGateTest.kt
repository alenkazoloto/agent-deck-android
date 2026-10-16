package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileRecap
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.ReadingPositions
import dev.agentdeck.companion.data.RecapGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reader's half of the desk's "where you left off": measured on the machine's clock from the
 * phone's last read of the conversation, and re-armed by sends rather than by time.
 */
class RecapGateTest {

    private val now = 10_000_000L

    private fun page(
        recap: MobileRecap? = MobileRecap("You asked: \"x\".", now - 3_600_000, 4),
        running: Boolean = false,
    ) = MobileTranscriptPage(
        key = "k", title = "t", turns = emptyList(), hasMore = false, costUsd = 0.0, costKnown = false,
        contextPct = null, model = null, liveLine = null, running = running, generatedAtMs = now, recap = recap,
    )

    @Test
    fun `a conversation last read hours ago is offered`() {
        assertNotNull(RecapGate.offer(page(), cachedAtMs = now - 3 * 3_600_000, shownAt = null, draft = ""))
    }

    @Test
    fun `one read a moment ago is not being returned to`() {
        assertNull(RecapGate.offer(page(), cachedAtMs = now - 60_000, shownAt = null, draft = ""))
    }

    @Test
    fun `with no copy the conversation's own last activity is the clock`() {
        assertNotNull(RecapGate.offer(page(), cachedAtMs = null, shownAt = null, draft = ""))
        val fresh = page(MobileRecap("x", now - 30_000, 4))
        assertNull(RecapGate.offer(fresh, cachedAtMs = null, shownAt = null, draft = ""))
    }

    @Test
    fun `a running chat, a typed draft or no line from the machine offers nothing`() {
        assertNull(RecapGate.offer(page(running = true), null, null, ""))
        assertNull(RecapGate.offer(page(), null, null, "half a thought"))
        assertNull(RecapGate.offer(page(recap = null), null, null, ""))
    }

    @Test
    fun `an offer is spent until two more sends follow it`() {
        assertNull(RecapGate.offer(page(), null, shownAt = 3, draft = ""))
        assertNotNull(RecapGate.offer(page(MobileRecap("x", now - 3_600_000, 5)), null, shownAt = 3, draft = ""))
    }

    @Test
    fun `what was offered survives a restart and is bounded`() {
        var positions = ReadingPositions()
        (1..ReadingPositions.MAX_CONVERSATIONS + 5).forEach { positions = positions.recapOffered("chat-$it", it) }
        val restored = ReadingPositions.fromJson(positions.toJson())

        assertEquals(ReadingPositions.MAX_CONVERSATIONS, restored.recaps.size)
        assertEquals(ReadingPositions.MAX_CONVERSATIONS + 5, restored.recaps["chat-${ReadingPositions.MAX_CONVERSATIONS + 5}"])
        assertNull(restored.recaps["chat-1"])
    }
}
