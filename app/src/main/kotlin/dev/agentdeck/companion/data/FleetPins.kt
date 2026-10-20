package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * The recency a list is *ordered* by, held still while the reader is looking at it.
 *
 * A fleet snapshot arrives every few seconds, and every write an agent makes moves its row's
 * `lastActivityMs`. Under a last-message ranking that is a re-sort under the reader's thumb:
 * on a machine with three live runs the row being aimed at slides away between the press and
 * the release, and the tap lands on whichever conversation took its place. The list is
 * *correct* the whole time, which is why nothing has ever failed over it.
 *
 * So a row's sort key is pinned the first time the list sees it and stays pinned while the
 * reader stays: the order the thumb is aiming at is the order the tap lands in. Three things
 * this deliberately does not do:
 *
 *  - **It does not pin a row's content.** Titles, states, costs and live lines are the live
 *    snapshot's, every frame. This is a ranking hold, not a freeze.
 *  - **It does not hold back a row it has never seen.** An unpinned row ranks on its own real
 *    recency, so a conversation that starts while the list is open still arrives at the top.
 *  - **It does not decide group membership.** Which group a row is in is the user's data
 *    (`memory/rendering.md`); only the order *inside* one is this object's business.
 *
 * Released — [released] — whenever the reader asks for a fresh answer or leaves the screen,
 * because at that moment nothing is being aimed at and the true order is what they want.
 */
class FleetPins private constructor(private val pinned: Map<String, Long>) {

    /** What [row] sorts on: the recency it was first seen at, or its own when it is new. */
    fun keyFor(row: MobileFleetRow): Long = pinned[row.key] ?: row.lastActivityMs

    /** Rows this holds a pin for — a test's whole question, and nothing else asks. */
    val size: Int get() = pinned.size

    /**
     * The pins after [rows] have been seen.
     *
     * Pins for rows no longer in the snapshot are dropped, which is what bounds this: the map
     * can never outgrow the machine's conversation count, and a row that comes back is simply
     * new again.
     */
    fun seeing(rows: List<MobileFleetRow>): FleetPins {
        if (rows.isEmpty()) return this
        val next = HashMap<String, Long>(rows.size)
        rows.forEach { row -> next[row.key] = pinned[row.key] ?: row.lastActivityMs }
        return if (next == pinned) this else FleetPins(next)
    }

    /** Forget everything, so the next snapshot ranks on the truth. */
    fun released(): FleetPins = if (pinned.isEmpty()) this else NONE

    /**
     * The pins after a snapshot arrived, given whether the reader is [holding] the list — on
     * the fleet screen, thumb over it.
     *
     * The release is expressed *here*, against the snapshot, rather than in the navigation
     * primitives, because there are four of them (`go`, `push`, `back`, a deep link) and a hold
     * that survives one of them survives the process: the first version released only on an
     * explicit refresh and a sort change, so a reader who opened a chat and came back an hour
     * later still saw the order the app had started with.
     */
    fun afterSnapshot(rows: List<MobileFleetRow>, holding: Boolean): FleetPins =
        if (holding) seeing(rows) else released()

    companion object {
        val NONE = FleetPins(emptyMap())
    }
}
