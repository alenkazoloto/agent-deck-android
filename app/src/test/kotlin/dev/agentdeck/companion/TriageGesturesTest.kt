package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.data.RowAction
import dev.agentdeck.companion.data.RowActions
import dev.agentdeck.companion.data.ScheduleWhen
import dev.agentdeck.companion.data.Snooze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The pure decisions behind the triage gestures: what a row offers, what a swipe hides, and
 * until when.
 */
class TriageGesturesTest {

    // ---- MU-14: what a row can do without being opened ------------------------------------

    /**
     * Stop is the one action a row has to earn, and the sheet and TalkBack must agree about it.
     * They agree by construction now — both read [RowActions.of] — so this asserts the rule that
     * construction encodes rather than either surface's copy of it.
     */
    @Test
    fun `only a running row offers Stop`() {
        assertTrue(RowAction.STOP in RowActions.of(FleetGroup.RUNNING))
        FleetGroup.entries.filter { it != FleetGroup.RUNNING }.forEach { group ->
            assertFalse("$group offered Stop", RowAction.STOP in RowActions.of(group))
        }
    }

    /** Everything else is unconditional: a row is always openable, snoozable and copyable. */
    @Test
    fun `every row offers open, snooze and copy`() {
        FleetGroup.entries.forEach { group ->
            val actions = RowActions.of(group)
            assertTrue("$group", actions.containsAll(listOf(RowAction.OPEN, RowAction.SNOOZE, RowAction.COPY_TITLE)))
            assertEquals("$group listed an action twice", actions.size, actions.toSet().size)
        }
    }

    /** Open leads, because it is what the long-press interrupted. */
    @Test
    fun `open is offered first`() {
        FleetGroup.entries.forEach { group ->
            assertEquals("$group", RowAction.OPEN, RowActions.of(group).first())
        }
    }

    private fun row(key: String, activityMs: Long) = MobileFleetRow(
        key = key,
        vendor = AgentVendor.CLAUDE,
        accountId = "default",
        accountLabel = null,
        projectPath = "/Users/dev/Plugin",
        projectName = "Plugin",
        gitBranch = null,
        title = "Approve the migration",
        attention = SessionAttentionState.WAITING_ON_YOU,
        waitingReason = null,
        lastActivityMs = activityMs,
        costUsd = 0.0,
        costKnown = true,
        contextPct = null,
        messageCount = 1,
        model = null,
        liveLine = null,
    )

    @Test
    fun `a snoozed row comes back the moment its agent moves`() {
        val snoozed = Snooze.add(emptyMap(), row("a", activityMs = 100))
        assertTrue(Snooze.hides(snoozed, row("a", activityMs = 100)))
        // One more message and the conversation is news again — the phone never decides a
        // conversation is unimportant for longer than the machine says nothing about it.
        assertFalse(Snooze.hides(snoozed, row("a", activityMs = 101)))
    }

    @Test
    fun `snoozing one row hides one row, and the count says so`() {
        val rows = listOf(row("a", 100), row("b", 100))
        val snoozed = Snooze.add(emptyMap(), rows.first())
        assertEquals(listOf("b"), Snooze.apply(snoozed, rows).map { it.key })
        assertEquals(1, Snooze.hidden(snoozed, rows))
        assertEquals(rows, Snooze.apply(emptyMap(), rows))
    }

    @Test
    fun `the badge stops counting what the user has already swiped aside`() {
        val rows = listOf(row("a", 100), row("b", 100))
        val snoozed = Snooze.add(emptyMap(), rows.first())
        assertEquals(2, Snooze.badge(2, emptyMap(), rows))
        assertEquals(1, Snooze.badge(2, snoozed, rows))
        // The machine's count is still the source: it can only ever be subtracted from, and
        // never below zero when the two disagree.
        assertEquals(0, Snooze.badge(1, Snooze.add(snoozed, rows[1]), rows))
    }

    @Test
    fun `tomorrow morning is tomorrow at nine, not twenty-four hours from now`() {
        val now = at(hour = 22, minute = 40)
        val due = ScheduleWhen.TOMORROW_MORNING.dueAtMs(now)
        assertEquals(9, hourOf(due))
        assertEquals(dayOf(now) + 1, dayOf(due))
    }

    @Test
    fun `this evening at midday is tonight, and at night it is an hour from now`() {
        val midday = at(hour = 12, minute = 0)
        assertEquals(18, hourOf(ScheduleWhen.THIS_EVENING.dueAtMs(midday)))
        assertEquals(dayOf(midday), dayOf(ScheduleWhen.THIS_EVENING.dueAtMs(midday)))

        // 18:00 has already gone. Yesterday evening is not a time anything can run, and
        // tomorrow evening is a day away from what the user asked for.
        val late = at(hour = 21, minute = 30)
        val due = ScheduleWhen.THIS_EVENING.dueAtMs(late)
        assertTrue(due > late)
        assertEquals(late + 60 * 60_000L, due)
    }

    @Test
    fun `the relative choices are simply offsets`() {
        val now = at(hour = 9, minute = 0)
        assertEquals(now + 60 * 60_000L, ScheduleWhen.IN_AN_HOUR.dueAtMs(now))
        assertEquals(now + 4 * 60 * 60_000L, ScheduleWhen.IN_FOUR_HOURS.dueAtMs(now))
    }

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        timeZone = TimeZone.getDefault()
        set(2026, Calendar.AUGUST, 2, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun hourOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)

    private fun dayOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_YEAR)
}
