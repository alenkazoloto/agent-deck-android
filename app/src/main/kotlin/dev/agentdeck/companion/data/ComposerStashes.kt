package dev.agentdeck.companion.data

import com.github.claudeagents.core.composer.ComposerStashQueue
import com.github.claudeagents.core.composer.StashedPrompt

/**
 * The desk's `chat:stash` on the phone: put a typed message aside, and pick it back later.
 *
 * Each conversation's queue lives in the drafts map under [key] rather than in a store of its
 * own. The drafts file is already per machine, survives process death, is dropped with the
 * pairing and is rewritten in one piece — so the text leaving the composer and the entry it
 * becomes are one write, and there is no instant at which a crash leaves the prompt in neither.
 */
object ComposerStashes {

    private const val PREFIX = "stash:"

    /** The drafts-map key holding [draftKey]'s queue. */
    fun key(draftKey: String) = "$PREFIX$draftKey"

    /** Newest first. */
    fun entries(drafts: Map<String, String>, draftKey: String): List<StashedPrompt> =
        ComposerStashQueue.decode(drafts[key(draftKey)])

    /**
     * Parks [draftKey]'s draft and empties the composer. Null when there is nothing to park —
     * blank text is not a message, and a row for it would restore nothing.
     */
    fun stash(drafts: Map<String, String>, draftKey: String, nowMs: Long): Map<String, String>? {
        val text = drafts[draftKey].orEmpty()
        if (text.isBlank()) return null
        val pushed = ComposerStashQueue.push(entries(drafts, draftKey), text, nowMs)
        return drafts + (key(draftKey) to ComposerStashQueue.encode(pushed.entries)) + (draftKey to "")
    }

    /**
     * Puts entry [id] back into the composer. A draft already there is parked first, as the
     * desk's menu swaps rather than overwrites: reaching for an older prompt must not cost the
     * one on screen. Null when the entry is gone (restored or discarded elsewhere).
     */
    fun restore(drafts: Map<String, String>, draftKey: String, id: String, nowMs: Long): Map<String, String>? {
        val (left, entry) = ComposerStashQueue.take(entries(drafts, draftKey), id)
        entry ?: return null
        val current = drafts[draftKey].orEmpty()
        val queue = if (current.isBlank()) left else ComposerStashQueue.push(left, current, nowMs).entries
        return drafts + (key(draftKey) to ComposerStashQueue.encode(queue)) + (draftKey to entry.text)
    }

    /** Drops entry [id] without restoring it. Null when it is already gone. */
    fun discard(drafts: Map<String, String>, draftKey: String, id: String): Map<String, String>? {
        val (left, entry) = ComposerStashQueue.take(entries(drafts, draftKey), id)
        entry ?: return null
        return drafts + (key(draftKey) to ComposerStashQueue.encode(left))
    }
}
