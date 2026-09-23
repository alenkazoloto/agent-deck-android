package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.ui.ConversationBody
import dev.agentdeck.companion.ui.conversationBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation flow as the phone runs it: the bridge's bytes → `fromJson` → what the
 * screen paints.
 *
 * [CODEX_PAGE] and [CLAUDE_PAGE] are **captured from the real bridge** — the response bodies
 * `MobileTranscriptRouteTest` gets back over its TLS socket, pasted verbatim. That is what
 * makes this a flow test rather than another round trip through the app's own encoder: the
 * app never re-encodes a page, so a test that encodes one before decoding it proves only that
 * the app agrees with itself.
 *
 * What earned it: every Codex conversation reached this app as `turns: []`, and the screen
 * dutifully painted "Nothing in this conversation yet" over all of them. The decode was
 * perfect and the render rule was right; nothing was left to notice that the *content* had
 * gone missing. So these assert content and the resulting screen state.
 */
class ConversationFlowTest {

    private fun decode(wire: String): MobileTranscriptPage =
        MobileTranscriptPage.fromJson(
            MobileProtocol.parseObject(wire) ?: error("the bridge's own body did not parse"),
        )

    @Test
    fun `a codex page from the bridge paints its turns`() {
        val page = decode(CODEX_PAGE)

        assertEquals(listOf("user", "assistant"), page.turns.map { it.role })
        assertEquals("run the tests", page.turns.first().text)
        assertTrue(
            "a decoded conversation with content must never reach the empty state",
            conversationBody(page, loading = false) is ConversationBody.Turns,
        )
    }

    @Test
    fun `a claude page from the bridge paints its turns and its live tool call`() {
        val page = decode(CLAUDE_PAGE)

        assertEquals(2, page.turns.size)
        assertTrue(page.running)
        val call = page.turns[1].toolCalls.single()
        // Title and summary are rendered verbatim: the plugin decides them so the desktop and
        // the phone cannot name the same tool call differently.
        assertEquals("Bash — run the tests", call.title)
        assertEquals("command: ./gradlew test, description: run the tests", call.summary)
        assertEquals(MobileToolCall.RUNNING, call.status)
        assertEquals("Bash — run the tests", page.liveLine)
    }

    /** The header line above the turns. */
    @Test
    fun `the page carries the identity the header paints`() {
        val page = decode(CLAUDE_PAGE)
        assertEquals("Fix the flaky test", page.title)
        assertEquals("claude-opus-5", page.model)
        assertTrue(page.key.startsWith("v2:"))
    }

    // ---- what the screen shows ---------------------------------------------------------

    @Test
    fun `a page that really is empty is the empty state`() {
        val page = decode("""{"v":1,"key":"k","title":"t","turns":[],"hasMore":false,"generatedAtMs":1}""")
        assertEquals(ConversationBody.Empty, conversationBody(page, loading = false))
    }

    /**
     * The discrimination the screen did not have. A refused request leaves no page at all,
     * and saying "Nothing in this conversation yet" about it asserts something the app does
     * not know — the machine never answered.
     */
    @Test
    fun `no page and nothing in flight is unavailable, not empty`() {
        assertEquals(ConversationBody.Unavailable, conversationBody(null, loading = false))
    }

    @Test
    fun `the first load is loading, not empty`() {
        assertEquals(ConversationBody.Loading, conversationBody(null, loading = true))
    }

    /** A reload must never claim the conversation is empty while its answer is in flight. */
    @Test
    fun `a reload over an empty page stays loading`() {
        val page = decode("""{"v":1,"key":"k","title":"t","turns":[],"hasMore":false,"generatedAtMs":1}""")
        assertEquals(ConversationBody.Loading, conversationBody(page, loading = true))
    }

    /** A quiet refresh keeps the turns on screen rather than flashing a spinner over them. */
    @Test
    fun `turns already on screen survive a refresh`() {
        val page = decode(CODEX_PAGE)
        assertTrue(conversationBody(page, loading = true) is ConversationBody.Turns)
    }

    private companion object {
        /** Verbatim from `MobileTranscriptRouteTest`'s Codex case, over the real socket. */
        const val CODEX_PAGE =
            """{"v":1,"key":"v2:Q09ERVg:ZGVmYXVsdA:MDE5ZmI5MmEtMjkzOS03ZDUwLTkwODUtZDFiYmJhYWIxYzI1""" +
                """:L1VzZXJzL21lL3Byb2plY3Q","title":"Fix the flaky test","turns":[{"id":"t0",""" +
                """"role":"user","text":"run the tests","timestampMs":1700000100000},{"id":"t1",""" +
                """"role":"assistant","text":"Running them now.","timestampMs":1700000100000,""" +
                """"toolCalls":[{"id":"u2","name":"Bash","title":"Bash",""" +
                """"summary":"command: ./gradlew test","status":"ok"}]}],"hasMore":false,""" +
                """"costUsd":0.0,"costKnown":false,"model":"claude-opus-5","running":false,""" +
                """"generatedAtMs":1785519712689}"""

        /** Verbatim from the same suite's Claude case, mid-run with an unfinished Bash call. */
        const val CLAUDE_PAGE =
            """{"v":1,"key":"v2:Q0xBVURF:ZGVmYXVsdA:MTExMTExMTEtMTExMS00MTExLTgxMTEtMTExMTExMTExMTEx""" +
                """:L1VzZXJzL21lL3Byb2plY3Q","title":"Fix the flaky test","turns":[{"id":"t0",""" +
                """"role":"user","text":"fix the flaky test","timestampMs":1700000100000},{"id":"t1",""" +
                """"role":"assistant","text":"Looking at it now.","timestampMs":1700000100000,""" +
                """"toolCalls":[{"id":"tool-1","name":"Bash","title":"Bash — run the tests",""" +
                """"summary":"command: ./gradlew test, description: run the tests",""" +
                """"status":"running"}]}],"hasMore":false,"costUsd":0.0,"costKnown":false,""" +
                """"model":"claude-opus-5","liveLine":"Bash — run the tests","running":true,""" +
                """"generatedAtMs":1785519712655}"""
    }
}
