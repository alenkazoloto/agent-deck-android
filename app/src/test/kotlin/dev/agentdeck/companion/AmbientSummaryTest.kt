package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.ambient.RUNNING_TRUST_MS
import dev.agentdeck.companion.ambient.ambientCounts
import dev.agentdeck.companion.ambient.ambientSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** The two lines the widget and the quick-settings tile both paint. */
class AmbientSummaryTest {

    private val stamp = 1_700_000_000_000L

    private fun row(key: String, attention: SessionAttentionState?, lastActivityMs: Long = stamp) =
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
            costKnown = false,
            contextPct = null,
            messageCount = 0,
            liveLine = null,
            model = null,
        )

    private fun snapshot(vararg rows: MobileFleetRow, generatedAtMs: Long = stamp) =
        MobileFleetSnapshot(
            rows = rows.toList(),
            badgeCount = rows.count { it.attention == SessionAttentionState.WAITING_ON_YOU },
            openProjects = listOf("/p/one"),
            usageLine = null,
            generatedAtMs = generatedAtMs,
        )

    @Test
    fun `nothing paired is its own pair of lines`() {
        val summary = ambientSummary(snapshot(), stamp, machineName = null)
        assertEquals("Not paired", summary.headline)
        assertEquals("Tap to pair", summary.detail)
    }

    @Test
    fun `waiting leads and the row names itself`() {
        val summary = ambientSummary(
            snapshot(
                row("review me", SessionAttentionState.WAITING_ON_YOU),
                row("busy", SessionAttentionState.RUNNING),
            ),
            stamp,
            "mac",
        )
        assertEquals("1 waiting on you", summary.headline)
        assertEquals("review me", summary.detail)
    }

    @Test
    fun `running is the headline when nothing waits, and used to read Nothing waiting`() {
        val summary = ambientSummary(
            snapshot(row("a", SessionAttentionState.RUNNING), row("b", SessionAttentionState.RUNNING)),
            stamp,
            "mac",
        )
        assertEquals("2 running", summary.headline)
        assertEquals("mac", summary.detail)
    }

    @Test
    fun `a failure outranks work in flight and the leftover count takes the second line`() {
        val summary = ambientSummary(
            snapshot(
                row("broke", SessionAttentionState.FAILED),
                row("busy", SessionAttentionState.RUNNING),
                row("read me", SessionAttentionState.DONE_UNREVIEWED),
            ),
            stamp,
            "mac",
        )
        assertEquals("1 failed", summary.headline)
        assertEquals("1 running · 1 to review", summary.detail)
    }

    @Test
    fun `a stale snapshot drops running and keeps what already happened`() {
        val old = snapshot(
            row("busy", SessionAttentionState.RUNNING),
            row("read me", SessionAttentionState.DONE_UNREVIEWED),
            generatedAtMs = stamp,
        )
        val counts = ambientCounts(old, stamp + RUNNING_TRUST_MS + 1)
        assertEquals("a running claim survived the trust window", 0, counts.running)
        assertEquals("a finished run stopped having finished", 1, counts.done)
        assertEquals(
            "1 to review",
            ambientSummary(old, stamp + RUNNING_TRUST_MS + 1, "mac").headline,
        )
        // On the boundary itself the snapshot is still trusted.
        assertEquals(1, ambientCounts(old, stamp + RUNNING_TRUST_MS).running)
    }

    @Test
    fun `a phone whose clock runs behind the machine is not treated as stale`() {
        val counts = ambientCounts(snapshot(row("busy", SessionAttentionState.RUNNING)), stamp - 60_000)
        assertEquals(1, counts.running)
    }

    @Test
    fun `an unstamped snapshot is trusted rather than assumed dead`() {
        val counts = ambientCounts(
            snapshot(row("busy", SessionAttentionState.RUNNING), generatedAtMs = 0L),
            stamp,
        )
        assertEquals(1, counts.running)
    }

    @Test
    fun `a paired machine with no cached snapshot still says nothing waiting`() {
        val summary = ambientSummary(null, stamp, "mac")
        assertEquals("Nothing waiting", summary.headline)
        assertEquals("mac", summary.detail)
    }
}
