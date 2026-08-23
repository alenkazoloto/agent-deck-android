package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.FleetGrouping
import dev.agentdeck.companion.data.FleetSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetGroupingTest {

    private fun row(
        key: String,
        attention: SessionAttentionState?,
        lastActivityMs: Long = 1_000,
        project: String = "/p/one",
        account: String = "default",
        vendor: AgentVendor = AgentVendor.CLAUDE,
        accountLabel: String? = null,
        model: String? = null,
        title: String = key,
        branch: String? = null,
        costUsd: Double = 0.0,
    ) = MobileFleetRow(
        key = key,
        vendor = vendor,
        accountId = account,
        accountLabel = accountLabel,
        projectPath = project,
        projectName = project.substringAfterLast('/'),
        gitBranch = branch,
        title = title,
        attention = attention,
        waitingReason = null,
        lastActivityMs = lastActivityMs,
        costUsd = costUsd,
        costKnown = true,
        contextPct = null,
        messageCount = 1,
        model = model,
    )

    /** No stamp ⇒ no recency is derivable, which is the pre-Recent behaviour exactly. */
    private fun sections(
        rows: List<MobileFleetRow>,
        filter: FleetFilter = FleetFilter(),
        generatedAtMs: Long = 0L,
        sort: FleetSort = FleetSort.ATTENTION,
    ) = FleetGrouping.sections(rows, filter, generatedAtMs, sort)

    @Test
    fun `groups appear in the attention order the desktop implies`() {
        // Deliberately supplied in the reverse of the expected order, so a grouping that
        // simply preserved input order could not pass.
        val rows = listOf(
            row("other", null),
            row("done", SessionAttentionState.DONE_UNREVIEWED),
            row("running", SessionAttentionState.RUNNING),
            row("failed", SessionAttentionState.FAILED),
            row("waiting", SessionAttentionState.WAITING_ON_YOU),
        )

        val sections = sections(rows)

        assertEquals(
            listOf(
                FleetGroup.WAITING,
                FleetGroup.RUNNING,
                FleetGroup.FAILED,
                FleetGroup.DONE_UNREVIEWED,
                FleetGroup.OTHER,
            ),
            sections.map { it.group },
        )
        // `Recent` needs the snapshot stamp these rows do not carry; the enum's own order is
        // asserted by the title test, so this list is deliberately the no-stamp shape.
        assertEquals(listOf("waiting", "running", "failed", "done", "other"), sections.map { it.rows.single().key })
    }

    /**
     * A live run is the one of the two still changing, so it is the one worth reaching first;
     * a failure has already finished failing. Asserted as the pair alone rather than through
     * the five-group list above, so the two ends of this decision — the phone's headings and
     * `MobileFleet.rank`'s transport order, which its KDoc promises will not drift — each fail
     * on their own line.
     */
    @Test
    fun `running is ranked above failed`() {
        val sections = sections(
            listOf(row("failed", SessionAttentionState.FAILED), row("running", SessionAttentionState.RUNNING)),
        )
        assertEquals(listOf(FleetGroup.RUNNING, FleetGroup.FAILED), sections.map { it.group })
        assertTrue(FleetGroup.RUNNING.ordinal < FleetGroup.FAILED.ordinal)
    }

    @Test
    fun `the declared group titles are the ones shown`() {
        assertEquals("Waiting on you", FleetGroup.WAITING.title)
        assertEquals("Failed", FleetGroup.FAILED.title)
        assertEquals("Running", FleetGroup.RUNNING.title)
        assertEquals("Recently active", FleetGroup.RECENT.title)
        assertEquals("Done, unreviewed", FleetGroup.DONE_UNREVIEWED.title)
        assertEquals("Everything else", FleetGroup.OTHER.title)
    }

    @Test
    fun `a group with no members is dropped rather than shown empty`() {
        val sections = sections(
            listOf(row("a", SessionAttentionState.RUNNING), row("b", SessionAttentionState.RUNNING)),
        )
        assertEquals(listOf(FleetGroup.RUNNING), sections.map { it.group })
    }

    @Test
    fun `rows inside a group are newest first`() {
        val sections = sections(
            listOf(
                row("old", SessionAttentionState.WAITING_ON_YOU, lastActivityMs = 10),
                row("newest", SessionAttentionState.WAITING_ON_YOU, lastActivityMs = 300),
                row("middle", SessionAttentionState.WAITING_ON_YOU, lastActivityMs = 200),
            ),
        )
        assertEquals(listOf("newest", "middle", "old"), sections.single().rows.map { it.key })
    }

    @Test
    fun `a session with no attention state lands in everything else`() {
        assertEquals(FleetGroup.OTHER, FleetGrouping.groupOf(row("x", null), 0L))
        assertEquals(FleetGroup.WAITING, FleetGrouping.groupOf(row("x", SessionAttentionState.WAITING_ON_YOU), 0L))
        assertEquals(FleetGroup.FAILED, FleetGrouping.groupOf(row("x", SessionAttentionState.FAILED), 0L))
        assertEquals(FleetGroup.RUNNING, FleetGrouping.groupOf(row("x", SessionAttentionState.RUNNING), 0L))
        assertEquals(
            FleetGroup.DONE_UNREVIEWED,
            FleetGrouping.groupOf(row("x", SessionAttentionState.DONE_UNREVIEWED), 0L),
        )
    }

    /**
     * The conversation the phone just messaged has no attention state — it changed no files
     * and is not running — so before `Recent` it sank under every other opinion-less session
     * on the machine. It has to clear the review backlog as well: `Done, unreviewed` held 167
     * rows on the machine this was measured on, and landing under those is still buried.
     *
     * The control is the same list with the stamp withheld: if a `Recent` heading appears
     * there too, the grouping is reading the reader's clock rather than the machine's.
     */
    @Test
    fun `a recently active session is lifted above the backlog and out of everything else`() {
        val now = 1_700_000_000_000
        val rows = listOf(
            row("stale", null, lastActivityMs = now - 6 * 60 * 60_000),
            row("just-messaged", null, lastActivityMs = now - 60_000),
            row("done", SessionAttentionState.DONE_UNREVIEWED, lastActivityMs = now - 20_000),
            row("waiting", SessionAttentionState.WAITING_ON_YOU, lastActivityMs = now - 30_000),
        )

        val grouped = sections(rows, generatedAtMs = now)
        assertEquals(
            listOf(FleetGroup.WAITING, FleetGroup.RECENT, FleetGroup.DONE_UNREVIEWED, FleetGroup.OTHER),
            grouped.map { it.group },
        )
        assertEquals(listOf("just-messaged"), grouped[1].rows.map { it.key })
        assertEquals(listOf("stale"), grouped[3].rows.map { it.key })

        // Negative control: identical rows, no snapshot stamp, so nothing may be called recent.
        assertEquals(
            listOf(FleetGroup.WAITING, FleetGroup.DONE_UNREVIEWED, FleetGroup.OTHER),
            sections(rows).map { it.group },
        )
    }

    /** An attention state always wins: `Recent` may never quietly demote a waiting row. */
    @Test
    fun `recency never overrides an attention state`() {
        val now = 1_700_000_000_000
        assertEquals(
            FleetGroup.DONE_UNREVIEWED,
            FleetGrouping.groupOf(
                row("x", SessionAttentionState.DONE_UNREVIEWED, lastActivityMs = now),
                now,
            ),
        )
    }

    @Test
    fun `the inline filters scope by project, account and vendor without reordering groups`() {
        val rows = listOf(
            row("a", SessionAttentionState.RUNNING, project = "/p/one", account = "default"),
            row("b", SessionAttentionState.WAITING_ON_YOU, project = "/p/two", account = "work"),
            row("c", SessionAttentionState.RUNNING, project = "/p/two", vendor = AgentVendor.CODEX),
        )

        assertEquals(listOf("b", "c"), sections(rows, FleetFilter(projectPath = "/p/two"))
            .flatMap { it.rows }.map { it.key })
        assertEquals(listOf("b"), sections(rows, FleetFilter(accountId = "work"))
            .flatMap { it.rows }.map { it.key })
        assertEquals(listOf("c"), sections(rows, FleetFilter(vendor = AgentVendor.CODEX))
            .flatMap { it.rows }.map { it.key })
        assertTrue(FleetFilter().isEmpty)
        assertTrue(sections(rows, FleetFilter(projectPath = "/nowhere")).isEmpty())
    }

    @Test
    fun `the filter choices are derived from the snapshot itself`() {
        val rows = listOf(
            row("a", null, project = "/p/zebra", account = "work", vendor = AgentVendor.CODEX),
            row("b", null, project = "/p/alpha", account = "default"),
            row("c", null, project = "/p/alpha", account = "default"),
        )
        assertEquals(listOf("alpha", "zebra"), FleetGrouping.projects(rows).map { it.projectName })
        assertEquals(listOf("default", "work"), FleetGrouping.accounts(rows))
        assertEquals(listOf(AgentVendor.CLAUDE, AgentVendor.CODEX), FleetGrouping.vendors(rows))
    }

    /**
     * The account selector used to render [MobileFleetRow.accountId] itself, which for a
     * secondary Claude account is the raw OAuth UUID: the user saw `267309f5-…` where the IDE
     * shows a name, and could not tell their two accounts apart.
     *
     * The control is the same row with the label withheld — that one may only ever be
     * *shortened*, never expanded into a name the snapshot did not carry.
     */
    @Test
    fun `an account is named by the machine, and an unnamed one is shortened rather than invented`() {
        val named = listOf(row("a", null, account = "267309f5-1b2c-4d5e-8f90-abcdef012345", accountLabel = "Work"))
        assertEquals("Work", FleetGrouping.accountLabel(named, "267309f5-1b2c-4d5e-8f90-abcdef012345"))

        val unnamed = listOf(row("a", null, account = "267309f5-1b2c-4d5e-8f90-abcdef012345"))
        val fallback = FleetGrouping.accountLabel(unnamed, "267309f5-1b2c-4d5e-8f90-abcdef012345")
        assertEquals("267309f5…", fallback)
        assertFalse("a fallback may not invent a name", fallback.contains("Work"))
        // A short id is already readable and is left exactly as it is.
        assertEquals("default", FleetGrouping.accountLabel(unnamed, "default"))
    }

    @Test
    fun `the model filter scopes by the model the snapshot carries`() {
        val rows = listOf(
            row("opus", null, model = "claude-opus-5"),
            row("haiku", null, model = "claude-haiku-4-5"),
            row("unknown", null),
        )
        assertEquals(listOf("claude-haiku-4-5", "claude-opus-5"), FleetGrouping.models(rows))
        assertEquals(
            listOf("opus"),
            sections(rows, FleetFilter(model = "claude-opus-5")).flatMap { it.rows }.map { it.key },
        )
        // A row the machine could not name a model for is scoped out, never silently kept.
        assertTrue(sections(rows, FleetFilter(model = "gpt-5.4")).isEmpty())
    }

    @Test
    fun `search matches title, project and branch and is case insensitive`() {
        val rows = listOf(
            row("a", null, title = "Fix the Login bar"),
            row("b", null, project = "/p/payments", title = "unrelated"),
            row("c", null, branch = "tretikoff/opus", title = "unrelated"),
            row("d", null, title = "nothing here"),
        )
        assertEquals(listOf("a"), sections(rows, FleetFilter(query = "login")).flatMap { it.rows }.map { it.key })
        assertEquals(listOf("b"), sections(rows, FleetFilter(query = "payments")).flatMap { it.rows }.map { it.key })
        assertEquals(listOf("c"), sections(rows, FleetFilter(query = "OPUS")).flatMap { it.rows }.map { it.key })
        // Blank and whitespace-only are "no search", not "match nothing".
        assertEquals(4, sections(rows, FleetFilter(query = "   ")).flatMap { it.rows }.size)
        assertTrue(FleetFilter(query = "  ").isEmpty)
        assertFalse(FleetFilter(query = "login").isEmpty)
    }

    /**
     * An explicit sort replaces the grouping with one flat list, as `ReviewSortOrder` does on
     * the desktop. The control is the identical rows under [FleetSort.ATTENTION]: if the
     * headings survive there and only there, the sort is ranking rather than re-labelling.
     */
    @Test
    fun `an explicit sort ranks one flat list and drops the attention headings`() {
        val rows = listOf(
            row("cheap-old", SessionAttentionState.WAITING_ON_YOU, lastActivityMs = 10, costUsd = 0.10),
            row("dear-new", null, lastActivityMs = 300, costUsd = 9.00),
            row("mid", SessionAttentionState.RUNNING, lastActivityMs = 200, costUsd = 1.00),
        )

        val byCost = sections(rows, sort = FleetSort.COST)
        assertEquals(1, byCost.size)
        assertNull("an explicitly sorted list is not an attention group", byCost.single().group)
        assertEquals("All conversations", byCost.single().title)
        assertEquals(listOf("dear-new", "mid", "cheap-old"), byCost.single().rows.map { it.key })

        assertEquals(
            listOf("dear-new", "mid", "cheap-old"),
            sections(rows, sort = FleetSort.RECENT).single().rows.map { it.key },
        )
        assertEquals(
            listOf("cheap-old", "dear-new", "mid"),
            sections(rows, sort = FleetSort.TITLE).single().rows.map { it.key },
        )

        // Every rank breaks its ties on the key, so a refresh that hands the same rows back in
        // another order cannot reshuffle the list under the user's thumb.
        val tied = listOf(
            row("z", null, title = "same", lastActivityMs = 5, costUsd = 1.0),
            row("a", null, title = "SAME", lastActivityMs = 5, costUsd = 1.0),
        )
        FleetSort.entries.filter { it != FleetSort.ATTENTION }.forEach { order ->
            assertEquals(
                "$order must break its tie on the key",
                listOf("a", "z"),
                sections(tied, sort = order).single().rows.map { it.key },
            )
            assertEquals(
                "$order must not depend on the order the rows arrived in",
                listOf("a", "z"),
                sections(tied.reversed(), sort = order).single().rows.map { it.key },
            )
        }

        // Negative control: the same rows, default sort — the groups are back and the
        // waiting row leads regardless of its cost or its age.
        val grouped = sections(rows)
        assertEquals(
            listOf(FleetGroup.WAITING, FleetGroup.RUNNING, FleetGroup.OTHER),
            grouped.map { it.group },
        )
        assertEquals("cheap-old", grouped.first().rows.single().key)
    }

    /**
     * The opening view is "carry on with what I was just doing", not triage. Asserted through
     * [DeckState]'s own default rather than the enum's first entry, because that default is
     * what the app actually starts on and nothing persists a pick over it.
     */
    @Test
    fun `the app opens sorted by last message`() {
        assertEquals(FleetSort.RECENT, DeckState().sort)
        assertEquals("Last message", FleetSort.RECENT.label)

        val rows = listOf(
            row("older", SessionAttentionState.WAITING_ON_YOU, lastActivityMs = 100),
            row("newest", null, lastActivityMs = 300),
        )
        // A flat list, newest first — an attention state does not lift a row over a newer one.
        val opening = sections(rows, sort = DeckState().sort)
        assertNull("the opening view is one list, not attention groups", opening.single().group)
        assertEquals(listOf("newest", "older"), opening.single().rows.map { it.key })
    }

    @Test
    fun `a sort still applies the filters rather than replacing them`() {
        val rows = listOf(
            row("keep", null, project = "/p/two", costUsd = 1.0),
            row("drop", null, project = "/p/one", costUsd = 9.0),
        )
        assertEquals(
            listOf("keep"),
            sections(rows, FleetFilter(projectPath = "/p/two"), sort = FleetSort.COST)
                .flatMap { it.rows }.map { it.key },
        )
    }
}
