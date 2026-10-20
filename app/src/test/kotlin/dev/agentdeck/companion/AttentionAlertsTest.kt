package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.notify.AttentionAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan's own acceptance for MU-10: *a run that needs the user produces exactly one
 * notification.* A `fleet` frame arrives several times a minute and re-states every waiting
 * row each time, so posting from the snapshot is a buzz per frame for as long as the agent
 * stays blocked — which is the failure mode this whole edge-detector exists to prevent.
 */
class AttentionAlertsTest {

    private val settings = AppSettings(triggers = NotifyTrigger.entries.toSet())

    private fun row(key: String, attention: SessionAttentionState?, activityMs: Long = 1_000) =
        MobileFleetRow(
            key = key,
            vendor = AgentVendor.CLAUDE,
            accountId = "default",
            accountLabel = null,
            projectPath = "/Users/dev/Plugin",
            projectName = "Plugin",
            gitBranch = "main",
            title = "Approve the migration",
            attention = attention,
            waitingReason = null,
            lastActivityMs = activityMs,
            costUsd = 0.0,
            costKnown = true,
            contextPct = null,
            messageCount = 3,
            model = null,
            liveLine = "Waiting on your answer",
        )

    private fun snapshot(vararg rows: MobileFleetRow) =
        MobileFleetSnapshot(rows.toList(), rows.size, emptyList(), null, 1_000)

    @Test
    fun `a blocked agent is announced once, however many frames restate it`() {
        val first = AttentionAlerts.plan(snapshot(row("a", SessionAttentionState.WAITING_ON_YOU)), settings, emptyMap())
        assertEquals(listOf("a"), first.post.map { it.key })

        val second = AttentionAlerts.plan(
            snapshot(row("a", SessionAttentionState.WAITING_ON_YOU)),
            settings,
            first.seen,
        )
        assertTrue("the second frame re-states the same row and must be silent", second.post.isEmpty())
        assertTrue(second.cancel.isEmpty())
    }

    @Test
    fun `changing state is news again, and the old claim comes down`() {
        val waiting = AttentionAlerts.plan(snapshot(row("a", SessionAttentionState.WAITING_ON_YOU)), settings, emptyMap())
        val failed = AttentionAlerts.plan(snapshot(row("a", SessionAttentionState.FAILED)), settings, waiting.seen)
        assertEquals(listOf(NotifyTrigger.FAILED), failed.post.map { it.trigger })

        // Answered: the row leaves every notifiable state, so the shade must stop claiming it.
        val answered = AttentionAlerts.plan(snapshot(row("a", SessionAttentionState.RUNNING)), settings, failed.seen)
        assertEquals(listOf("a"), answered.cancel)
        assertTrue(answered.post.isEmpty())
    }

    @Test
    fun `the conversation on screen is not announced, and is not announced later either`() {
        val plan = AttentionAlerts.plan(
            snapshot(row("a", SessionAttentionState.WAITING_ON_YOU)),
            settings,
            emptyMap(),
            suppressKey = "a",
        )
        assertTrue("telling someone about what they are reading is the buzz nobody wants", plan.post.isEmpty())
        // Remembered anyway: leaving the conversation must not then buzz about the row the
        // reader just closed.
        assertEquals(NotifyTrigger.NEEDS_YOU.id, plan.seen["a"])
        val next = AttentionAlerts.plan(snapshot(row("a", SessionAttentionState.WAITING_ON_YOU)), settings, plan.seen)
        assertTrue(next.post.isEmpty())
    }

    @Test
    fun `a trigger the user turned off is neither posted nor remembered`() {
        val only = AppSettings(triggers = setOf(NotifyTrigger.NEEDS_YOU))
        val plan = AttentionAlerts.plan(
            snapshot(
                row("a", SessionAttentionState.WAITING_ON_YOU),
                row("b", SessionAttentionState.DONE_UNREVIEWED),
            ),
            only,
            emptyMap(),
        )
        assertEquals(listOf("a"), plan.post.map { it.key })
        assertNull(plan.seen["b"])
    }

    @Test
    fun `several waiting agents collapse into one summary line`() {
        val plan = AttentionAlerts.plan(
            snapshot(
                row("a", SessionAttentionState.WAITING_ON_YOU),
                row("b", SessionAttentionState.WAITING_ON_YOU),
                row("c", SessionAttentionState.FAILED),
            ),
            settings,
            emptyMap(),
        )
        assertEquals("2 needs you · 1 failed", AttentionAlerts.summary(plan.seen))
        // One row is not a group; Android would draw a summary over a single notification.
        assertNull(AttentionAlerts.summary(mapOf("a" to NotifyTrigger.NEEDS_YOU.id)))
    }

    @Test
    fun `a row that left the fleet takes its notification with it`() {
        val seen = mapOf("gone" to NotifyTrigger.NEEDS_YOU.id)
        val plan = AttentionAlerts.plan(snapshot(), settings, seen)
        assertEquals(listOf("gone"), plan.cancel)
        assertTrue(plan.seen.isEmpty())
    }
}
