package dev.agentdeck.companion.data

import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * Swipe a row away, and get it back the moment its agent moves.
 *
 * Triage on a phone is a thumb over a list, and every action in this app used to need the
 * conversation opened first. The one thing a thumb can do to a row it does not want to act on
 * is put it aside — but "put aside" must not become the phone quietly deciding a conversation
 * is less important than the desktop said it was, which is the plan's own "do not invent
 * attention" rule.
 *
 * So a snooze is keyed to the **activity stamp it was taken at**. It hides a row exactly as
 * long as nothing happens in it; the next message, tool call or failure moves
 * `lastActivityMs` past the mark and the row comes back on its own, in whatever group the
 * machine has by then put it. Nothing is reclassified and nothing is hidden forever.
 */
object Snooze {

    fun add(current: Map<String, Long>, row: MobileFleetRow): Map<String, Long> =
        current + (row.key to row.lastActivityMs)

    fun hides(snoozed: Map<String, Long>, row: MobileFleetRow): Boolean {
        val at = snoozed[row.key] ?: return false
        return row.lastActivityMs <= at
    }

    /** The list the fleet paints, and the count of what it is not painting. */
    fun apply(snoozed: Map<String, Long>, rows: List<MobileFleetRow>): List<MobileFleetRow> =
        if (snoozed.isEmpty()) rows else rows.filterNot { hides(snoozed, it) }

    fun hidden(snoozed: Map<String, Long>, rows: List<MobileFleetRow>): Int =
        if (snoozed.isEmpty()) 0 else rows.count { hides(snoozed, it) }

    /**
     * The badge, less what the user has set aside.
     *
     * [badgeCount] is the machine's own count and stays the source — the phone does not decide
     * who is waiting. But a badge reading 3 over a list showing 1 is the app arguing with
     * itself, and a snooze is the user saying "I have seen this one". So the subtraction is of
     * *acknowledgement*, not of attention, and it lasts exactly as long as the snooze does.
     */
    fun badge(badgeCount: Int, snoozed: Map<String, Long>, rows: List<MobileFleetRow>): Int {
        if (snoozed.isEmpty()) return badgeCount
        val acknowledged = rows.count {
            it.attention == SessionAttentionState.WAITING_ON_YOU && hides(snoozed, it)
        }
        return (badgeCount - acknowledged).coerceAtLeast(0)
    }
}
