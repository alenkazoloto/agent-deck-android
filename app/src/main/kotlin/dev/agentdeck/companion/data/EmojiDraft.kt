package dev.agentdeck.companion.data

import com.github.claudeagents.core.composer.EmojiCompletion

/**
 * The composer's `:` emoji completion as text arithmetic over the desk's own table and rules
 * ([EmojiCompletion], shared source): the same `:thu` offers the same rows in the same order, and
 * the same closing colon of `:tada:` becomes 🎉, whichever screen the prompt was typed on.
 */
object EmojiDraft {

    /** The `:` completion the caret sits in; [anchor] is the `:`. */
    data class Active(val anchor: Int, val query: String)

    /** Null unless the caret is in a `:shortcode` that started a word, as on the desk. */
    fun activeAt(text: String, caret: Int): Active? {
        if (caret < 1 || caret > text.length) return null
        // The nearest `:` back to whitespace, as the desk's `MentionCompletion.resumeAnchor`;
        // `filterAt` then refuses it unless it started a word.
        val anchor = text.lastIndexOf(':', caret - 1).takeIf { a ->
            a >= 0 && (a + 1 until caret).none { text[it].isWhitespace() }
        } ?: return null
        val query = EmojiCompletion.filterAt(text, anchor, caret) ?: return null
        return Active(anchor, query)
    }

    fun suggestions(active: Active): List<EmojiCompletion.Suggestion> = EmojiCompletion.suggestions(active.query)

    /** The glyph replaces the `:query`, with no trailing space — the desk's accept exactly. */
    fun accept(text: String, active: Active, emoji: String): Pair<String, Int> {
        val end = active.anchor + 1 + active.query.length
        return text.take(active.anchor) + emoji + text.drop(end) to active.anchor + emoji.length
    }

    /**
     * The `:name:` swap owed by an edit from [before] to [after] with the caret at [caret], or
     * null. Only a single typed character counts: a paste or a restored draft that happens to
     * contain `:tada:` is the reader's text and stays as written, as it does on the desk.
     */
    fun swapAfterTyping(before: String, after: String, caret: Int): Pair<String, Int>? {
        if (after.length != before.length + 1 || caret < 1 || caret > after.length) return null
        if (after.removeRange(caret - 1, caret) != before) return null
        val swap = EmojiCompletion.inlineSwapAt(after, caret) ?: return null
        return after.replaceRange(swap.from, swap.to, swap.emoji) to swap.from + swap.emoji.length
    }
}
