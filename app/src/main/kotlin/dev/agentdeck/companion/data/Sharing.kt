package dev.agentdeck.companion.data

/**
 * Text that arrived from another app's share sheet, waiting for the user to say where it goes.
 *
 * [label] is what the banner shows and [text] is what lands in a composer — kept apart because
 * a shared web page is a title and a URL, and a banner that printed the whole payload would be
 * the payload rather than a sentence about it.
 */
data class SharedInput(val text: String, val label: String)

/**
 * What an `ACTION_SEND` becomes.
 *
 * Deliberately text-only, and the manifest's filters say so: `/v1/send` carries a prompt string
 * and nothing else, so an image shared into this app could only be dropped or lied about. A
 * file whose content *is* text is read and inlined by the caller — that is a share the machine
 * can act on, because the bytes travel rather than a `content://` URI the desktop cannot open.
 */
object Sharing {

    /**
     * Past this the share is clipped. A phone can hand over an entire article, and a prompt
     * that large costs real tokens on a machine the user is not looking at — so the ceiling is
     * stated in the text rather than silently applied.
     */
    const val MAX_CHARS = 16_000

    private const val LABEL_CHARS = 60

    /**
     * The prompt a share becomes, or null when there is nothing to send.
     *
     * [title] is `EXTRA_SUBJECT` or the shared file's name; it is dropped when the body already
     * contains it, which is the ordinary browser share — Chrome sends the page title as the
     * subject and "Title https://…" as the text.
     */
    fun compose(title: String?, body: String?): SharedInput? {
        val cleanBody = body?.trim().orEmpty()
        val cleanTitle = title?.trim().orEmpty()
        if (cleanBody.isEmpty() && cleanTitle.isEmpty()) return null
        val head = cleanTitle.takeIf { it.isNotEmpty() && !cleanBody.contains(it) }
        val text = listOfNotNull(head, cleanBody.ifEmpty { null }).joinToString("\n\n")
        return SharedInput(text = clip(text), label = label(head ?: cleanBody))
    }

    /**
     * The shared text merged into whatever the user had already typed there.
     *
     * Appended, never replacing: a draft is the one thing this app promises to keep, and a
     * share landing on a half-written question would delete it with no undo.
     */
    fun appendedTo(draft: String, shared: String): String =
        if (draft.isBlank()) shared else draft.trimEnd() + "\n\n" + shared

    private fun clip(text: String): String =
        if (text.length <= MAX_CHARS) text
        else text.take(MAX_CHARS).trimEnd() + "\n\n[shared text clipped at $MAX_CHARS characters]"

    /** One line, short enough for a banner that also has to hold two buttons. */
    private fun label(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= LABEL_CHARS) line else line.take(LABEL_CHARS).trimEnd() + "…"
    }
}
