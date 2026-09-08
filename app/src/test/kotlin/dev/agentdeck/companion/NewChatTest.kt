package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewChatTest {

    private fun row(
        key: String,
        project: String,
        lastActivityMs: Long,
        vendor: AgentVendor = AgentVendor.CLAUDE,
    ) = MobileFleetRow(
        key = key,
        vendor = vendor,
        accountId = "default",
        projectPath = project,
        projectName = project.substringAfterLast('/'),
        gitBranch = null,
        title = key,
        attention = null,
        waitingReason = null,
        lastActivityMs = lastActivityMs,
        costUsd = 0.0,
        costKnown = true,
        contextPct = null,
        messageCount = 1,
    )

    /**
     * The machine refuses a prompt for a project it does not have open (`NO_OPEN_PROJECT`),
     * so a composer with no open project has no destination it could offer — and inventing
     * one from the fleet's own rows would produce a Start button that can only be refused.
     */
    @Test
    fun `no open project means no target at all`() {
        assertNull(NewChat.defaultTarget(emptyList(), listOf(row("a", "/p/one", 10))))
    }

    @Test
    fun `the composer opens on the project the user was most recently working in`() {
        val target = NewChat.defaultTarget(
            openProjects = listOf("/p/one", "/p/two"),
            rows = listOf(
                row("old", "/p/one", 10),
                row("newest", "/p/two", 300, AgentVendor.CODEX),
            ),
        )
        assertEquals(NewChatTarget("/p/two", AgentVendor.CODEX), target)
    }

    /** A closed project's rows are not a destination — the pick has to be open right now. */
    @Test
    fun `a recent project the IDE has since closed is not offered`() {
        val target = NewChat.defaultTarget(
            openProjects = listOf("/p/one"),
            rows = listOf(row("newest", "/p/closed", 300), row("older", "/p/one", 10)),
        )
        assertEquals("/p/one", target?.projectPath)
    }

    @Test
    fun `a previous pick is kept while it is still open and dropped when it is not`() {
        val rows = listOf(row("newest", "/p/two", 300))
        assertEquals(
            NewChatTarget("/p/one", AgentVendor.CODEX),
            NewChat.defaultTarget(
                openProjects = listOf("/p/one", "/p/two"),
                rows = rows,
                previous = NewChatTarget("/p/one", AgentVendor.CODEX),
            ),
        )
        // The same previous pick, against a machine that closed it: the composer moves rather
        // than staying aimed at a destination that can only be refused.
        assertEquals(
            "/p/two",
            NewChat.defaultTarget(
                openProjects = listOf("/p/two"),
                rows = rows,
                previous = NewChatTarget("/p/one", AgentVendor.CODEX),
            )?.projectPath,
        )
    }

    @Test
    fun `an empty machine still offers an agent to start with`() {
        assertEquals(listOf(AgentVendor.CLAUDE), NewChat.vendorOptions(emptyList()))
        assertEquals(
            listOf(AgentVendor.CLAUDE, AgentVendor.CODEX),
            NewChat.vendorOptions(
                listOf(row("a", "/p/one", 1, AgentVendor.CODEX), row("b", "/p/one", 2)),
            ),
        )
    }

    /** Falls back only when nothing else is known — never to a project that is not open. */
    @Test
    fun `an open project with no conversations yet is still a valid destination`() {
        assertEquals(
            NewChatTarget("/p/fresh", AgentVendor.CLAUDE),
            NewChat.defaultTarget(openProjects = listOf("/p/fresh"), rows = emptyList()),
        )
    }
}
