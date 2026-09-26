package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileMemoryFile
import com.github.claudeagents.core.mobile.MobileMemorySaved

/**
 * The reader's unsaved text for machine files edited on the phone (a memory file, a skill's `SKILL.md`), by a caller-chosen key, with
 * the revision each was made from. An edit that differs from the machine's text is kept through Back, closing the sheet, a refused save
 * and a failed link; only the machine taking it, or the reader choosing the machine's text, drops it ([drop]). One implementation of the
 * rule for both editors, so the two cannot disagree about when typing is lost (`MemoryFlow`, `SkillFileFlow`).
 */
internal class FileDrafts {
    private class Draft(val text: String, val savedText: String, val revision: String)

    private val drafts = HashMap<String, Draft>()

    fun has(key: String): Boolean = drafts.containsKey(key)

    fun drop(key: String) {
        drafts.remove(key)
    }

    fun clear() = drafts.clear()

    /** [text] typed into [open]: kept unless it is what the machine holds. */
    fun edit(key: String, open: OpenMemoryFile, text: String): OpenMemoryFile {
        if (text == open.savedText) drafts.remove(key) else drafts[key] = Draft(text, open.savedText, open.revision)
        return open.copy(text = text, saved = false, error = null)
    }

    /**
     * The editor for [file] as the machine just read it. A kept draft made from an older revision comes back *with* the machine's
     * version as a [OpenMemoryFile.conflict]: the reader sees that the file changed before pressing Save, and Save would say the same.
     */
    fun opened(key: String, file: MobileMemoryFile): OpenMemoryFile {
        val draft = drafts[key]
        val fresh = OpenMemoryFile(file.id, file.label, file.writable, file.deletable, file.existed, file.content, file.revision, file.content)
        if (draft == null) return fresh
        val moved = draft.revision != file.revision
        return fresh.copy(
            text = draft.text,
            savedText = if (moved) draft.savedText else file.content,
            revision = if (moved) draft.revision else file.revision,
            conflict = file.takeIf { moved },
        )
    }

    /** [open] after the machine answered a save of [sent]. */
    fun applied(key: String, open: OpenMemoryFile, sent: String, answer: MobileMemorySaved): OpenMemoryFile = when (answer.status) {
        MobileMemorySaved.SAVED -> {
            // Only the text that was sent is now on the machine; keystrokes made while it travelled stay dirty.
            drafts.remove(key)
            val kept = open.text != sent
            if (kept) drafts[key] = Draft(open.text, sent, answer.revision)
            open.copy(savedText = sent, revision = answer.revision, existed = true, conflict = null, error = null, saved = !kept)
        }
        MobileMemorySaved.CONFLICT -> open.copy(conflict = answer.current, error = if (answer.current == null) UNREADABLE_CONFLICT else null)
        else -> open.copy(error = answer.message ?: UNREADABLE_CONFLICT)
    }

    /** Keep mine: the reader's text is to be saved over the machine's version they have now seen. */
    fun keepMine(open: OpenMemoryFile): OpenMemoryFile {
        val theirs = open.conflict ?: return open
        return open.copy(conflict = null, revision = theirs.revision, savedText = theirs.content)
    }

    /** Take the machine's version; the reader's text for this file is dropped. */
    fun loadTheirs(key: String, open: OpenMemoryFile): OpenMemoryFile {
        val theirs = open.conflict ?: return open
        drafts.remove(key)
        return open.copy(conflict = null, text = theirs.content, savedText = theirs.content, revision = theirs.revision)
    }

    /** Drops the reader's edits and shows the text the machine holds. */
    fun discard(key: String, open: OpenMemoryFile): OpenMemoryFile {
        drafts.remove(key)
        return open.copy(text = open.savedText, saved = false, error = null)
    }

    private companion object {
        const val UNREADABLE_CONFLICT = "The machine did not accept that save. Reload the file and try again."
    }
}
