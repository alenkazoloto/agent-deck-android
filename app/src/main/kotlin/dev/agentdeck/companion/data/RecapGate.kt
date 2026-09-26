package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRecap
import com.github.claudeagents.core.mobile.MobileTranscriptPage

/** A recap line offered for the conversation [key]; the strip draws only while that conversation is open. */
data class RecapOffer(val key: String, val recap: MobileRecap)

/**
 * Whether opening a conversation earns the desk's "where you left off" line.
 *
 * The machine already answered what only it can (the setting, the send floor, a run still going —
 * see `MobileRecapLine`); what is left is the reader's side, which the desk measures from its last
 * look and a phone has to measure from its own. Both clocks below are the *machine's* — the cached
 * copy's `generatedAtMs` and the file's last activity — so a phone whose clock is off cannot
 * arm or silence the line.
 *
 * The 180 s window and the two-sends re-arm are the desk's (`SessionRecap.AWAY_THRESHOLD_MS`,
 * `MIN_NEW_USER_MESSAGES`); that class is not shared with the app, so they are repeated here.
 */
object RecapGate {

    const val AWAY_MS = 180_000L
    const val MIN_NEW_USER_MESSAGES = 2

    /**
     * The line to offer, or null. [cachedAtMs] is when the copy this phone had on opening was read
     * — its last look at the conversation — and null when it had none, in which case the
     * conversation's own last activity stands in, as it does on the desk. [shownAt] is the send
     * count the last offer was made at, so a conversation that has not moved is not offered twice.
     */
    fun offer(page: MobileTranscriptPage, cachedAtMs: Long?, shownAt: Int?, draft: String): MobileRecap? {
        val recap = page.recap ?: return null
        if (page.running || draft.isNotBlank()) return null
        val since = cachedAtMs ?: recap.lastActiveMs
        if (page.generatedAtMs - since < AWAY_MS) return null
        if (shownAt != null && recap.userMessages - shownAt < MIN_NEW_USER_MESSAGES) return null
        return recap
    }
}
