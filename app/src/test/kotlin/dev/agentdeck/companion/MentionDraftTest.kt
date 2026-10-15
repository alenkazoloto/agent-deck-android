package dev.agentdeck.companion

import dev.agentdeck.companion.data.MentionDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the `@` popup opens, what it filters on, and what accepting a row leaves behind.
 *
 * The cases that matter are the ones where it must *not* open: an email address and a mid-word
 * `@` are the two a soft keyboard produces by accident, and a popup over either is the desktop
 * composer's own reported bug restated on a phone.
 */
class MentionDraftTest {

    @Test
    fun `an at at the start of a word opens the popup`() {
        assertEquals(MentionDraft.Active(0, "src"), MentionDraft.activeAt("@src", 4))
        assertEquals(MentionDraft.Active(4, "Con"), MentionDraft.activeAt("fix @Con", 8))
        assertEquals("", MentionDraft.activeAt("fix @", 5)?.query)
    }

    @Test
    fun `an at inside a word does not`() {
        assertNull("an email address must not pop the file list", MentionDraft.activeAt("foo@bar", 7))
        assertNull(MentionDraft.activeAt("a@b.com", 7))
    }

    @Test
    fun `whitespace and the caret end the mention`() {
        assertNull("a space after the token closes it", MentionDraft.activeAt("@src ", 5))
        assertNull("a caret before the anchor is not in the mention", MentionDraft.activeAt("fix @Con", 3))
        assertNull(MentionDraft.activeAt("", 0))
    }

    @Test
    fun `accepting replaces the typed token and leaves the caret past a trailing space`() {
        val active = requireNotNull(MentionDraft.activeAt("fix @Con", 8))

        val (text, caret) = MentionDraft.accept("fix @Con", active, "ui/Conversation.kt")

        assertEquals("fix @ui/Conversation.kt ", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun `accepting keeps whatever follows the caret`() {
        val active = requireNotNull(MentionDraft.activeAt("fix @Con later", 8))

        val (text, caret) = MentionDraft.accept("fix @Con later", active, "ui/Conversation.kt")

        assertEquals("fix @ui/Conversation.kt  later", text)
        assertEquals("fix @ui/Conversation.kt ".length, caret)
    }
}
