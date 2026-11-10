package dev.agentdeck.companion.data

import com.github.claudeagents.core.sessions.ChatGoal

/**
 * The goal strip's Clear: the desk bar's action, which puts `/goal clear` in the input for the
 * reader to send rather than sending it — the send is a real turn, so the reader owns it.
 *
 * The desk overwrites its input; a phone draft is not as cheap to retype, so a message already in
 * the composer is parked on the stash first (the same queue the stash button feeds) and comes back
 * from its menu.
 */
object GoalClear {

    /** The drafts map with [draftKey]'s composer holding the clear command; null when it already does. */
    fun prefill(drafts: Map<String, String>, draftKey: String, nowMs: Long): Map<String, String>? {
        val current = drafts[draftKey].orEmpty()
        if (current.trim() == ChatGoal.CLEAR_COMMAND) return null
        val parked = ComposerStashes.stash(drafts, draftKey, nowMs) ?: drafts
        return parked + (draftKey to ChatGoal.CLEAR_COMMAND)
    }
}
