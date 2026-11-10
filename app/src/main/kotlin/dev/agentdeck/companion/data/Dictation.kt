package dev.agentdeck.companion.data

/**
 * Speaking a prompt instead of typing it.
 *
 * Dictation **adds to** the draft rather than replacing it: on a phone the usual shape is a
 * few typed words and then a long spoken paragraph, and a recognizer that overwrote what was
 * already there would destroy exactly the input the draft rules exist to protect. Every
 * result therefore lands through [append], and the draft it produces is stored under the
 * same key a typed one is, so an interrupted dictation survives leaving the screen.
 */
object Dictation {

    /**
     * [spoken] added to [draft], with one space between them and never two.
     *
     * The recognizer returns an unpunctuated, untrimmed phrase; joining it blindly gave
     * "look at this .Then run the tests". A draft that already ends in whitespace or an
     * opening bracket keeps its own spacing.
     */
    fun append(draft: String, spoken: String): String {
        val addition = spoken.trim()
        if (addition.isEmpty()) return draft
        if (draft.isEmpty()) return addition
        val separator = if (draft.last().isWhitespace() || draft.last() in OPENERS) "" else " "
        return draft + separator + addition
    }

    private const val OPENERS = "([{<\"'"
}
