package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.ConversationSearch
import dev.agentdeck.companion.ui.ConversationFindState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {
    private fun turn(id: String, text: String) = MobileTurn(id, "assistant", text, 1)

    @Test fun `common character results are bounded`() = kotlinx.coroutines.runBlocking {
        val result = dev.agentdeck.companion.data.ConversationSearch.find(
            listOf(com.github.claudeagents.core.mobile.MobileTurn("many", "assistant", "a".repeat(100_000), 0)), "a")
        org.junit.Assert.assertEquals(dev.agentdeck.companion.data.ConversationSearch.MAX_MATCHES, result.size)
    }

    @Test fun `Russian and emoji preserve exact source offsets case insensitively`() = runBlocking {
        val text = "😀 Привет, мир! ПРИВЕТ снова."
        val matches = ConversationSearch.find(listOf(turn("a", text)), "привет")
        assertEquals(listOf("Привет", "ПРИВЕТ"), matches.map { text.substring(it.start, it.end) })
        assertEquals(3, matches.first().start)
        val emoji = ConversationSearch.find(listOf(turn("a", text)), "😀").single()
        assertEquals(2, emoji.end)
    }

    @Test fun `code and repeated occurrences search literal source in reading order`() = runBlocking {
        val code = "```kotlin\nval value = foo()\nfoo()\n```"
        val matches = ConversationSearch.find(listOf(turn("a", code), turn("b", "foo()")), "foo()")
        assertEquals(listOf("a", "a", "b"), matches.map { it.turnId })
        assertTrue(matches[0].start < matches[1].start)
        assertEquals(1, ConversationSearch.find(listOf(turn("a", "aaa")), "aa").size)
    }

    @Test fun `empty and absent queries have no matches`() = runBlocking {
        val turns = listOf(turn("a", "some words"))
        assertTrue(ConversationSearch.find(turns, "").isEmpty())
        assertTrue(ConversationSearch.find(turns, "elsewhere").isEmpty())
    }

    @Test fun `paging growth adds older downloaded matches without fetching anything`() = runBlocking {
        val current = listOf(turn("b", "needle now"))
        val initial = ConversationSearch.find(current, "needle")
        val expanded = ConversationSearch.find(listOf(turn("a", "needle earlier")) + current, "needle")
        assertEquals(listOf("a", "b"), expanded.map { it.turnId })
        assertEquals(initial.single(), expanded.last())
    }

    @Test fun `query replacement and closing invalidate the selected result immediately`() = runBlocking {
        val state = ConversationFindState()
        state.open = true
        state.matches = ConversationSearch.find(listOf(turn("a", "needle needle")), "needle")
        assertEquals(0, state.activeMatch!!.start)
        state.step(-1)
        assertEquals(7, state.activeMatch!!.start)
        state.step(1)
        assertEquals(0, state.activeMatch!!.start)
        state.changeQuery("replacement")
        assertNull(state.activeMatch)
        assertTrue(state.matches.isEmpty())
        state.open = false
        assertNull(state.activeMatch)
    }

    @Test fun `obsolete large search is cancellable and a replacement can finish`() = runBlocking {
        val old = async { ConversationSearch.find(listOf(turn("a", "a".repeat(2_000_000))), "missing") }
        old.cancel()
        try {
            old.await()
            throw AssertionError("Cancelled search must not deliver results")
        } catch (_: CancellationException) { }
        assertEquals(1, ConversationSearch.find(listOf(turn("b", "replacement")), "replacement").size)
        assertFalse(old.isActive)
    }
}
