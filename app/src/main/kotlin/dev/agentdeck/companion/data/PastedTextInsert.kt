package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileAttachment

/**
 * The desk's long-paste rule (`core/composer/PastedText`: more than three lines or 512 characters
 * is a paste, not typing) read off a text field's edit, since Android reports a paste as one more
 * `onValueChange`. The phone has no `[Pasted text #N]` token — the chip above the field is the
 * whole handle, like a picked text file's — so the paste leaves the field and rides as an upload.
 *
 * [split] only looks at one edit: typing, autocorrect and a dictation chunk are far below the
 * threshold, and draft restores never pass through the field's change callback.
 */
object PastedTextInsert {

    /** What the machine names the file the agent is told to read. */
    const val FILE_NAME = "pasted-text.txt"

    private const val MAX_INLINE_LINES = 3
    private const val MAX_INLINE_CHARS = 512

    /** [kept] is the field's text without the paste; [pasted] is what to upload. */
    data class Split(val kept: String, val pasted: String)

    /** The paste [after] added to [before], or null for an ordinary edit or one too big to upload as text. */
    fun split(before: String, after: String): Split? {
        val limit = minOf(before.length, after.length)
        var head = 0
        while (head < limit && before[head] == after[head]) head++
        var tail = 0
        while (tail < limit - head && before[before.length - 1 - tail] == after[after.length - 1 - tail]) tail++
        val pasted = after.substring(head, after.length - tail)
        if (!summarises(pasted)) return null
        if (pasted.toByteArray(Charsets.UTF_8).size > MobileAttachment.MAX_TEXT_BYTES) return null
        return Split(after.substring(0, head) + after.substring(after.length - tail), pasted)
    }

    fun summarises(text: String): Boolean = lines(text) > MAX_INLINE_LINES || text.length > MAX_INLINE_CHARS

    /** A trailing newline is not a line of its own, as on the desk. */
    fun lines(text: String): Int = if (text.isEmpty()) 0 else text.trimEnd('\n').count { it == '\n' } + 1

    /** "Pasted text (12 lines) · 4 KB". */
    fun label(text: String, bytes: Int): String {
        val lines = lines(text)
        return TextFileAttachment.label("Pasted text ($lines ${if (lines == 1) "line" else "lines"})", bytes)
    }
}
