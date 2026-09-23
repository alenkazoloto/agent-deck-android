package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * What a fleet row lets you do without opening it (MU-14).
 *
 * Out here rather than inside the sheet because the *same* set has to reach three surfaces —
 * the long-press sheet, the swipe, and TalkBack's `customActions` — and a set that is rebuilt
 * per surface drifts one surface at a time. That is not hypothetical: the accessible actions
 * and the sheet's buttons were two `if (group == RUNNING)` branches written separately, so
 * either could have gained an action the other never offered, and nothing would have failed.
 */
enum class RowAction(val label: String) {
    OPEN("Open the conversation"),
    STOP("Stop this run"),
    SNOOZE("Snooze until this agent moves"),
    COPY_TITLE("Copy the title"),
    MARK_REVIEWED("Mark every changed file reviewed"),
    PIN("Pin to the top"),
    UNPIN("Unpin"),
    MOVE_PIN_UP("Move pin up"),
    MOVE_PIN_DOWN("Move pin down"),
    DONE("Mark done"),
    REOPEN("Reopen"),
    RENAME("Rename"),
    /** The desk's "Regenerate title": a model turn on the machine writes a new name. */
    RETITLE("Regenerate title"),
    FOLDER("Move to folder…"),
    SHARE("Share the conversation"),
    /** A Claude chat, at one of the reader's messages: the desk's "New chat from here…". */
    FORK("New chat from here…"),
    /** The whole chat, the desk's Branch chat: Codex's `thread/fork`, or a Claude copy on a new id. */
    BRANCH("Branch chat"),
    /** A Claude chat: the desk's `/rewind`, back to before one of the reader's messages. */
    REWIND("Rewind…"),
    /** The desk's Repository row: commit what is staged in the checkout the chat ran in. */
    COMMIT_STAGED("Commit staged changes…"),
    DELETE("Delete the conversation…"),
}

object RowActions {

    /**
     * The actions [group] earns, in the order they are offered.
     *
     * [RowAction.STOP] is the only conditional one, and the condition is the row's own state:
     * a run that has already finished has nothing to stop, and an action that silently no-ops
     * is how a list teaches its user to stop trusting it.
     *
     * [RowAction.MARK_REVIEWED] is conditional on two things rather than one: the row has to be
     * a "Done, unreviewed" one — a tick is meaningless on a chat that changed nothing anyone is
     * waiting to judge — and the machine has to advertise `review`, because an app that offered
     * it against an older plugin would be claiming a route that answers 404. "Open on desktop"
     * is still absent; the bridge serves no focus route.
     *
     * Pin, Done and Rename write the desk's own stores, so they need a machine advertising
     * `session-actions` ([canOrganize]); each offers the one direction [row] can go. Share
     * needs `session-export` ([canShare]): the machine renders the file the desk's `/export` writes.
     * Delete needs `session-delete` ([canDelete]) and is withheld from a running row, which the
     * machine would only refuse: the desk's own delete waits for the run to stop.
     * Move to folder needs `session-folders` ([canFolder]): it files into the desk's Sessions folders.
     * Fork needs `session-fork` ([canFork]) and is withheld from a running row for Delete's reason:
     * the desk refuses to copy a conversation mid-write. Claude forks at a message, Codex whole.
     * A Claude chat's whole-chat Branch also needs `session-branch` ([canBranch]): an older machine
     * refuses it.
     * Move pin up/down needs `pin-order` ([canPinOrder]) and another pin painted beside the row
     * ([paintedPins]); both stay offered at the ends, where the tap says why nothing moved.
     * Regenerate title needs `session-retitle` ([canRetitle]); a running Codex row is left out,
     * because Codex refuses a new name mid-turn.
     * Rewind needs `session-rewind` ([canRewind]), a Claude row (a Codex rollout records no restore
     * point) and one that is not running, which the desk refuses.
     * Commit staged needs `commit-staged` ([canCommitStaged]) and nothing else: the desk offers it
     * mid-run too, because it commits what the reader staged, not what the agent is writing.
     */
    fun of(
        group: FleetGroup,
        canReview: Boolean = false,
        row: MobileFleetRow? = null,
        canOrganize: Boolean = false,
        canShare: Boolean = false,
        canDelete: Boolean = false,
        canFolder: Boolean = false,
        canFork: Boolean = false,
        canPinOrder: Boolean = false,
        paintedPins: Int = 0,
        canRetitle: Boolean = false,
        canBranch: Boolean = false,
        canRewind: Boolean = false,
        canCommitStaged: Boolean = false,
    ): List<RowAction> = buildList {
        add(RowAction.OPEN)
        if (group == FleetGroup.RUNNING) add(RowAction.STOP)
        if (canReview && group == FleetGroup.DONE_UNREVIEWED) add(RowAction.MARK_REVIEWED)
        if (canOrganize && row != null) add(if (row.pinned) RowAction.UNPIN else RowAction.PIN)
        if (canPinOrder && row != null && row.pinned && paintedPins > 1) {
            add(RowAction.MOVE_PIN_UP)
            add(RowAction.MOVE_PIN_DOWN)
        }
        add(RowAction.SNOOZE)
        if (canOrganize && row != null) {
            add(if (row.done) RowAction.REOPEN else RowAction.DONE)
            add(RowAction.RENAME)
        }
        if (canRetitle && row != null &&
            !(group == FleetGroup.RUNNING && row.vendor == com.github.claudeagents.core.AgentVendor.CODEX)
        ) {
            add(RowAction.RETITLE)
        }
        if (canFolder && row != null) add(RowAction.FOLDER)
        if (canShare && row != null) add(RowAction.SHARE)
        if (canFork && row != null && group != FleetGroup.RUNNING) {
            val codex = row.vendor == com.github.claudeagents.core.AgentVendor.CODEX
            if (!codex) add(RowAction.FORK)
            if (codex || canBranch) add(RowAction.BRANCH)
        }
        if (canRewind && row != null && group != FleetGroup.RUNNING && row.vendor == com.github.claudeagents.core.AgentVendor.CLAUDE) {
            add(RowAction.REWIND)
        }
        if (canCommitStaged && row != null) add(RowAction.COMMIT_STAGED)
        add(RowAction.COPY_TITLE)
        // Last, apart from everything a slip of the thumb could undo.
        if (canDelete && row != null && group != FleetGroup.RUNNING) add(RowAction.DELETE)
    }
}
