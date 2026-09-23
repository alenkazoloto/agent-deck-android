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
    DONE("Mark done"),
    REOPEN("Reopen"),
    RENAME("Rename"),
    SHARE("Share the conversation"),
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
     */
    fun of(
        group: FleetGroup,
        canReview: Boolean = false,
        row: MobileFleetRow? = null,
        canOrganize: Boolean = false,
        canShare: Boolean = false,
    ): List<RowAction> = buildList {
        add(RowAction.OPEN)
        if (group == FleetGroup.RUNNING) add(RowAction.STOP)
        if (canReview && group == FleetGroup.DONE_UNREVIEWED) add(RowAction.MARK_REVIEWED)
        if (canOrganize && row != null) add(if (row.pinned) RowAction.UNPIN else RowAction.PIN)
        add(RowAction.SNOOZE)
        if (canOrganize && row != null) {
            add(if (row.done) RowAction.REOPEN else RowAction.DONE)
            add(RowAction.RENAME)
        }
        if (canShare && row != null) add(RowAction.SHARE)
        add(RowAction.COPY_TITLE)
    }
}
