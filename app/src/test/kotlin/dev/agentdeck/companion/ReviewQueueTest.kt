package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.ReviewQueue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewQueueTest {

    private fun row(key: String, attention: SessionAttentionState?, at: Long, project: String = "/p/one") = MobileFleetRow(
        key = key,
        vendor = AgentVendor.CLAUDE,
        accountId = "default",
        projectPath = project,
        projectName = project.substringAfterLast('/'),
        gitBranch = null,
        title = key,
        attention = attention,
        waitingReason = null,
        lastActivityMs = at,
        costUsd = 0.0,
        costKnown = true,
        contextPct = null,
        messageCount = 1,
    )

    @Test
    fun `only finished-unreviewed work is listed, by project, newest first`() {
        val rows = listOf(
            row("old", SessionAttentionState.DONE_UNREVIEWED, 1),
            row("running", SessionAttentionState.RUNNING, 9),
            row("waiting", SessionAttentionState.WAITING_ON_YOU, 8),
            row("other", SessionAttentionState.DONE_UNREVIEWED, 5, project = "/p/two"),
            row("new", SessionAttentionState.DONE_UNREVIEWED, 7),
            row("quiet", null, 6),
        )
        val projects = ReviewQueue.projects(rows)
        assertEquals(listOf("one", "two"), projects.map { it.name })
        assertEquals(listOf("new", "old"), projects[0].rows.map { it.key })
        assertEquals(listOf("other"), projects[1].rows.map { it.key })
        assertEquals(3, ReviewQueue.count(rows))
    }

    @Test
    fun `nothing to review is an empty list, not a heading`() {
        assertEquals(emptyList<ReviewQueue.Project>(), ReviewQueue.projects(listOf(row("r", SessionAttentionState.RUNNING, 1))))
    }
}
