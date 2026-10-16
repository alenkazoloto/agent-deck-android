package dev.agentdeck.companion

import dev.agentdeck.companion.data.EmojiDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone's `:` completion reads the desk's table and rules (`EmojiCompletionTest` pins those);
 * these pin what the phone adds on top: where the popup anchors, what accepting leaves, and that
 * only a typed colon — never a paste — swaps.
 */
class EmojiDraftTest {

    @Test
    fun `a colon at a word start with two letters offers the desk's ranking`() {
        val active = requireNotNull(EmojiDraft.activeAt("thanks :thu", 11))
        assertEquals(EmojiDraft.Active(7, "thu"), active)
        assertEquals("thumbsup", EmojiDraft.suggestions(active).first().name)
    }

    @Test
    fun `prose colons open nothing`() {
        assertNull("a clock time", EmojiDraft.activeAt("at 10:30", 8))
        assertNull("a label", EmojiDraft.activeAt("Note:ab", 7))
        assertNull("a space closes it", EmojiDraft.activeAt(":cry ", 5))
    }

    @Test
    fun `accepting writes the glyph in place of the query and keeps what follows`() {
        val active = requireNotNull(EmojiDraft.activeAt("ok :tad later", 7))
        assertEquals("ok 🎉 later" to "ok 🎉".length, EmojiDraft.accept("ok :tad later", active, "🎉"))
    }

    @Test
    fun `typing the closing colon swaps a known shortcode`() {
        assertEquals("great 🎉" to "great 🎉".length, EmojiDraft.swapAfterTyping("great :tada", "great :tada:", 12))
        assertEquals("aliases too", "👍" to 2, EmojiDraft.swapAfterTyping(":thumbsup", ":thumbsup:", 10))
    }

    @Test
    fun `an unknown name, a paste or a mid-word colon is left as written`() {
        assertNull(EmojiDraft.swapAfterTyping("x :nosuchname", "x :nosuchname:", 14))
        assertNull("a paste is the reader's text", EmojiDraft.swapAfterTyping("", "great :tada:", 12))
        assertNull(EmojiDraft.swapAfterTyping("a:tada", "a:tada:", 7))
    }
}
