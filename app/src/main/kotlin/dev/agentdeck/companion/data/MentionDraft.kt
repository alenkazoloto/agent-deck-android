package dev.agentdeck.companion.data

/**
 * The `@`-mention token under the caret, as pure text arithmetic.
 *
 * The desktop composer answers the same question in `core/composer/MentionCompletion`, and that
 * object is not shared here on purpose: it carries the IDE composer's whole vocabulary — quoted
 * mentions, CJK sentence marks, the `#`-issue and emoji triggers that share its boundary rule —
 * and none of it is reachable from a phone keyboard. What *is* shared is the rule that decides
 * when a trigger starts a word, restated here in the two cases a soft keyboard produces: the
 * start of the field, and after whitespace. A phone that popped the file list in the middle of
 * `foo@bar.com` would be the same bug the desktop's whitelist exists to prevent.
 */
object MentionDraft {

    /** Below this the popup does not query: one letter after `@` matches most of a repository. */
    const val MIN_QUERY = 1

    /** What the caret is completing, or null when no mention is open. */
    data class Active(val anchor: Int, val query: String) {
        /** The span an accept replaces — the `@` through the caret. */
        val start: Int get() = anchor
    }

    /**
     * The mention the caret sits in, or null.
     *
     * A space ends it, so `@src/main Foo` completes nothing: a path with a space in it is the
     * one case this deliberately cannot express, because the alternative is a popup that never
     * closes while the reader keeps typing prose.
     */
    fun activeAt(text: String, caret: Int): Active? {
        if (caret <= 0 || caret > text.length) return null
        var i = caret - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '@') {
                if (!startsWord(text, i)) return null
                return Active(i, text.substring(i + 1, caret))
            }
            if (c.isWhitespace()) return null
            i--
        }
        return null
    }

    /** A trigger starts a word at the start of the text or right after whitespace. */
    fun startsWord(text: String, offset: Int): Boolean =
        offset == 0 || (offset in 1..text.length && text[offset - 1].isWhitespace())

    /** The text and caret after accepting [path] for [active] — a trailing space, as the desktop does. */
    fun accept(text: String, active: Active, path: String): Pair<String, Int> {
        val caret = active.anchor + 1 + active.query.length
        val inserted = "@$path "
        return text.take(active.anchor) + inserted + text.drop(caret) to (active.anchor + inserted.length)
    }
}
