package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.TranscriptPages
import org.junit.Assert.*
import org.junit.Test

class TranscriptPagesTest {
    private fun page(vararg ids: String) = MobileTranscriptPage.fromJson(
        requireNotNull(MobileProtocol.parseObject("""{"key":"chat","title":"Chat","turns":[],"generatedAtMs":1}""")),
    ).copy(turns = ids.map { MobileTurn(it, "assistant", it, 1) }, revision = "r1", previousCursor = "older", hasMore = true)

    @Test fun `oversized expanded history does not replace a complete saved page`() {
        val large = page("a").copy(turns = listOf(MobileTurn("a", "assistant", "x".repeat(3 * 1024 * 1024), 0)))
        assertNull(dev.agentdeck.companion.data.TranscriptCacheEncoding.encode(large))
        val small = page("a")
        val encoded = requireNotNull(dev.agentdeck.companion.data.TranscriptCacheEncoding.encode(small))
        assertEquals(small, MobileTranscriptPage.fromJson(requireNotNull(MobileProtocol.parseObject(encoded))))
    }

    @Test fun `pending ancestry scan never claims an empty conversation`() {
        val pending = page().copy(historyPending = true)
        assertEquals(dev.agentdeck.companion.ui.ConversationBody.Unavailable,
            dev.agentdeck.companion.ui.conversationBody(pending, false))
        assertEquals(dev.agentdeck.companion.ui.ConversationBody.Loading,
            dev.agentdeck.companion.ui.conversationBody(pending, true))
    }

    @Test fun `prepend deduplicates boundaries and preserves newest actions and cost`() {
        val current = page("b", "c").copy(running = true, costUsd = 5.0)
        val earlier = page("a", "b").copy(previousCursor = null, hasMore = false)
        val merged = TranscriptPages.prepend(current, earlier)
        assertEquals(listOf("a", "b", "c"), merged.turns.map { it.id })
        assertTrue(merged.running)
        assertEquals(5.0, merged.costUsd, 0.0)
        assertFalse(merged.hasMore)
        assertNull(merged.previousCursor)
    }

    @Test fun `different key revision and repeated cursor fail closed`() {
        val current = page("b")
        listOf(page("a").copy(key = "other"), page("a").copy(revision = "r2"), page("a")).forEach {
            assertTrue(runCatching { TranscriptPages.prepend(current, it) }.isFailure)
        }
    }

    @Test fun `live refresh keeps downloaded history within the same revision`() {
        val current = page("a", "b", "c").copy(previousCursor = null, hasMore = false)
        val fresh = page("b", "c").copy(costUsd = 8.0)
        val merged = requireNotNull(TranscriptPages.refresh(current, fresh, false))
        assertEquals(current.turns, merged.turns)
        assertEquals(8.0, merged.costUsd, 0.0)
        assertNull(merged.previousCursor)
    }

    @Test fun `new output while reading earlier waits for deliberate latest`() {
        val current = page("a", "b", "c")
        val fresh = page("c", "d").copy(revision = "r2")
        assertNull(TranscriptPages.refresh(current, fresh, false))
        assertEquals(fresh, TranscriptPages.refresh(current, fresh, true))
    }
}
