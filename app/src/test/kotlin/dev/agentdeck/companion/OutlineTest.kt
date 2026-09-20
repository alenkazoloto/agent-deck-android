package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.ConversationOutline
import dev.agentdeck.companion.data.OutlineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a conversation's table of contents says.
 *
 * The rows are asserted as `(kind, label)` pairs rather than by index, because the sheet's
 * whole claim is *what a reader sees in which rank* — an assertion over indices would still
 * pass with prompts and answers swapped.
 */
class OutlineTest {

    private fun user(id: String, text: String) = MobileTurn(id, "user", text, timestampMs = 1)

    private fun agent(id: String, text: String = "", calls: List<MobileToolCall> = emptyList()) =
        MobileTurn(id, "assistant", text, timestampMs = 2, toolCalls = calls)

    private fun call(id: String, title: String, name: String = "Bash") =
        MobileToolCall(id = id, name = name, title = title, summary = "", status = "ok")

    private fun rows(turns: List<MobileTurn>) =
        ConversationOutline.of(turns).entries.map { it.kind to it.label }

    @Test
    fun `a prompt is a chapter, an answer a phase, a tool run an activity`() {
        assertEquals(
            listOf(
                OutlineKind.PROMPT to "fix the flaky test",
                OutlineKind.ACTIVITY to "Bash — ./gradlew test",
                OutlineKind.ANSWER to "Two of them shared a port.",
            ),
            rows(
                listOf(
                    user("u1", "fix the flaky test"),
                    agent("a1", "Two of them shared a port.", listOf(call("c1", "Bash — ./gradlew test"))),
                ),
            ),
        )
    }

    /** The activity precedes the answer: that is the order the work happened in. */
    @Test
    fun `several calls in one turn are one row naming the first and counting the rest`() {
        val outline = ConversationOutline.of(
            listOf(agent("a1", "done", listOf(call("c1", "Read Foo.kt"), call("c2", "Edit Foo.kt"), call("c3", "Bash test")))),
        )
        val activity = outline.entries.single { it.kind == OutlineKind.ACTIVITY }

        assertEquals("Read Foo.kt", activity.label)
        assertEquals("+2 more", activity.detail)
        assertEquals(3, outline.toolCalls)
    }

    @Test
    fun `a heading or a bullet is a landmark, not punctuation`() {
        assertEquals(
            listOf(OutlineKind.ANSWER to "The plan"),
            rows(listOf(agent("a1", "## The plan\n\n- step one"))),
        )
    }

    /** A turn with nothing to title it contributes nothing rather than an untitled row. */
    @Test
    fun `an empty or markup-only turn earns no row`() {
        assertEquals(emptyList<Pair<OutlineKind, String>>(), rows(listOf(user("u1", "   \n\n"), agent("a1", "###"))))
        assertNull(ConversationOutline.headline("\n \n"))
    }

    @Test
    fun `a system turn is not part of the outline`() {
        assertEquals(
            emptyList<Pair<OutlineKind, String>>(),
            rows(listOf(MobileTurn("s1", "system", "context low", timestampMs = 1))),
        )
    }

    /** One prompt is the conversation's title, already in the app bar. Two are a choice. */
    @Test
    fun `the sheet is offered only once there are two chapters`() {
        val one = listOf(user("u1", "start"), agent("a1", "ok"))
        val two = one + listOf(user("u2", "again"), agent("a2", "ok"))

        assertFalse(ConversationOutline.of(one).offered)
        assertFalse(ConversationOutline.offers(one))
        assertTrue(ConversationOutline.of(two).offered)
        assertTrue(ConversationOutline.offers(two))
    }

    /** The cheap predicate and the built outline must never disagree about the same turns. */
    @Test
    fun `offers agrees with the outline it stands in for`() {
        listOf(
            emptyList(),
            listOf(user("u1", "one")),
            listOf(user("u1", "one"), user("u2", "two")),
            listOf(user("u1", "  "), user("u2", "two"), user("u3", "three")),
        ).forEach { turns ->
            assertEquals(turns.size.toString(), ConversationOutline.of(turns).offered, ConversationOutline.offers(turns))
        }
    }

    /**
     * A prompt row carries its turn's *whole* text, not the headline.
     *
     * "Start a new chat from this prompt" starts from this field, and a prompt long enough to
     * be worth re-running is exactly the one whose first line is not all of it.
     */
    @Test
    fun `a prompt row carries the text a new chat would start from`() {
        val long = "Refactor the loader\n\nKeep the streaming path; do not load the whole file."
        val entry = ConversationOutline.of(listOf(user("u1", long))).entries.single()

        assertEquals("Refactor the loader", entry.label)
        assertEquals(long, entry.source)
    }

    @Test
    fun `an answer row carries no prompt to start from`() {
        assertNull(ConversationOutline.of(listOf(agent("a1", "done"))).entries.single().source)
    }

    /** The index is what `ConversationReading.showTurn` takes, so it indexes the page's turns. */
    @Test
    fun `a row points at the turn it came from`() {
        val turns = listOf(user("u1", "one"), agent("a1", "first"), user("u2", "two"), agent("a2", "second"))
        val outline = ConversationOutline.of(turns)

        outline.entries.forEach { entry ->
            assertEquals(entry.label, turns[entry.turnIndex].id, entry.turnId)
        }
    }

    @Test
    fun `the sheet is bounded however long the page is`() {
        val turns = (0 until 400).map { user("u$it", "prompt $it") }
        val outline = ConversationOutline.of(turns)

        assertEquals(ConversationOutline.MAX_ENTRIES, outline.entries.size)
        assertEquals("the counts describe the page, not the truncated list", 400, outline.prompts)
    }

    @Test
    fun `a long prompt is elided in the label and whole in the source`() {
        val long = "x".repeat(ConversationOutline.MAX_LABEL + 50)
        val entry = ConversationOutline.of(listOf(user("u1", long))).entries.single()

        assertEquals(ConversationOutline.MAX_LABEL, entry.label.length)
        assertEquals(long, entry.source)
    }
}
