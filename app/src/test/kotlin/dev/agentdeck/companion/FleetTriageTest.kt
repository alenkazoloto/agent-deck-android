package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.FleetGrouping
import dev.agentdeck.companion.data.FleetSection
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.ui.markOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triage at scale: what a 167-row backlog does to the rest of the list, and what a row can
 * still say about itself once its section heading is far off screen.
 *
 * The fixture is deliberately the shape of the machine this was measured on — a huge
 * `DONE_UNREVIEWED` beside small groups — because with ten rows in every group the capped and
 * uncapped lists are identical and the test proves nothing.
 */
class FleetTriageTest {

    private fun row(key: String, attention: SessionAttentionState?, lastActivityMs: Long = 1_000) =
        MobileFleetRow(
            key = key,
            vendor = AgentVendor.CLAUDE,
            accountId = "default",
            accountLabel = null,
            projectPath = "/p/one",
            projectName = "one",
            gitBranch = null,
            title = key,
            attention = attention,
            waitingReason = null,
            lastActivityMs = lastActivityMs,
            costUsd = 0.0,
            costKnown = true,
            contextPct = null,
            messageCount = 1,
            model = null,
            liveLine = null,
        )

    /** 167 done-unreviewed rows, 2 waiting, 1 running — the backlog that buries everything. */
    private fun backlog(): List<MobileFleetRow> =
        (1..167).map { row("done-$it", SessionAttentionState.DONE_UNREVIEWED) } +
            listOf(
                row("wait-1", SessionAttentionState.WAITING_ON_YOU),
                row("wait-2", SessionAttentionState.WAITING_ON_YOU),
                row("run-1", SessionAttentionState.RUNNING),
            )

    private fun sections(rows: List<MobileFleetRow>, sort: FleetSort = FleetSort.ATTENTION) =
        FleetGrouping.sections(rows, FleetFilter(), generatedAtMs = 10_000, sort = sort)

    // ---- MU-16: one group may not bury the rest -------------------------------------------

    @Test
    fun `a huge group is capped and says how many it is holding`() {
        val done = sections(backlog()).single { it.group == FleetGroup.DONE_UNREVIEWED }
        val view = FleetGrouping.view(done, sectionCount = 3, expanded = false)

        assertEquals(FleetGrouping.SECTION_CAP, view.rows.size)
        assertEquals(167 - FleetGrouping.SECTION_CAP, view.hidden)
        // The expander's label is built from this: "Show all 167", never a silent truncation.
        assertEquals(167, view.total)
    }

    @Test
    fun `capping never hides a group's first rows`() {
        val done = sections(backlog()).single { it.group == FleetGroup.DONE_UNREVIEWED }
        val view = FleetGrouping.view(done, sectionCount = 3, expanded = false)

        assertEquals(done.rows.take(FleetGrouping.SECTION_CAP).map { it.key }, view.rows.map { it.key })
    }

    @Test
    fun `every group stays reachable above the fold`() {
        // The harm in one number: uncapped, "Running" sits 169 rows down. Capped, the whole
        // list is short enough that no group is below a scroll nobody performs.
        val all = sections(backlog())
        val painted = all.sumOf { FleetGrouping.view(it, all.size, expanded = false).rows.size }

        assertEquals(listOf(FleetGroup.WAITING, FleetGroup.RUNNING, FleetGroup.DONE_UNREVIEWED), all.map { it.group })
        assertTrue("a capped fleet must fit in a few screens, not 170 rows: $painted", painted <= 40)
    }

    @Test
    fun `an expanded group shows everything`() {
        val done = sections(backlog()).single { it.group == FleetGroup.DONE_UNREVIEWED }
        val view = FleetGrouping.view(done, sectionCount = 3, expanded = true)

        assertEquals(167, view.rows.size)
        assertEquals(0, view.hidden)
    }

    @Test
    fun `a lone group is never capped — there is nothing under it to bury`() {
        val rows = (1..40).map { row("done-$it", SessionAttentionState.DONE_UNREVIEWED) }
        val only = sections(rows).single()
        val view = FleetGrouping.view(only, sectionCount = 1, expanded = false)

        assertEquals(40, view.rows.size)
        assertEquals(0, view.hidden)
    }

    @Test
    fun `an explicitly sorted flat list is never capped`() {
        // Sorting by cost is the user asking for one ranked list of everything; showing them
        // the ten most expensive and hiding the rest answers a question they did not ask.
        val flat = sections(backlog(), FleetSort.COST).single()
        val view = FleetGrouping.view(flat, sectionCount = 1, expanded = false)

        assertEquals(170, view.rows.size)
        assertEquals(0, view.hidden)
    }

    // ---- MU-15: the row carries its own state ---------------------------------------------

    @Test
    fun `no two groups paint the same mark`() {
        val marks = FleetGroup.entries.map(::markOf)
        assertEquals(
            "each group needs a distinguishable mark, got $marks",
            FleetGroup.entries.size,
            marks.toSet().size,
        )
    }

    @Test
    fun `no two groups are told apart by colour alone`() {
        // The assertion the first screenshot earned: Waiting and Failed were a circle and a
        // 25%-cut square, both `error` — and at 10 dp the cut corners disappeared, leaving two
        // identical red dots for the two states this app exists to distinguish. A silhouette
        // is what a greyscale screenshot and a red-green colour-blind reader are left with.
        val silhouettes = FleetGroup.entries.map { markOf(it).silhouette }
        assertEquals(
            "each group needs a shape a reader can tell apart without colour, got $silhouettes",
            FleetGroup.entries.size,
            silhouettes.toSet().size,
        )
    }

    @Test
    fun `a row knows its own group without its heading`() {
        // What makes the mid-list row readable: the group is derived from the row, so it is
        // just as available under a flat sort, where there is no heading at all.
        assertEquals(
            FleetGroup.WAITING,
            FleetGrouping.groupOf(row("wait-1", SessionAttentionState.WAITING_ON_YOU), 10_000),
        )
        assertEquals(
            FleetGroup.DONE_UNREVIEWED,
            FleetGrouping.groupOf(row("done-1", SessionAttentionState.DONE_UNREVIEWED), 10_000),
        )
    }

    @Test
    fun `a section view of an empty section is empty rather than absent`() {
        val view = FleetGrouping.view(FleetSection(FleetGroup.FAILED, emptyList()), 3, expanded = false)
        assertEquals(0, view.total)
    }
}
