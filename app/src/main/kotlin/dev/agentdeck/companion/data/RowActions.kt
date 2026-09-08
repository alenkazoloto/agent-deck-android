package dev.agentdeck.companion.data

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
}

object RowActions {

    /**
     * The actions [group] earns, in the order they are offered.
     *
     * [RowAction.STOP] is the only conditional one, and the condition is the row's own state:
     * a run that has already finished has nothing to stop, and an action that silently no-ops
     * is how a list teaches its user to stop trusting it.
     *
     * "Mark reviewed" and "Open on desktop" are deliberately absent — `/v1/review/{key}` is
     * `PLAN-MOBILE-COMPANION.md` M7 and there is no focus route — so the app never offers a
     * capability the machine has not advertised.
     */
    fun of(group: FleetGroup): List<RowAction> = buildList {
        add(RowAction.OPEN)
        if (group == FleetGroup.RUNNING) add(RowAction.STOP)
        add(RowAction.SNOOZE)
        add(RowAction.COPY_TITLE)
    }
}
