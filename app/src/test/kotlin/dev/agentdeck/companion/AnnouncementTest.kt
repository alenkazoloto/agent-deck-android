package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import dev.agentdeck.companion.data.FleetGroup
import dev.agentdeck.companion.ui.rowAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What TalkBack says about a fleet row (MU-19).
 *
 * The row is one merged node, so this string is the *entire* announcement — there is no second
 * chance for the vendor, the state or the project to be read from a child. That is why it is
 * pinned here rather than left to the screen: every fact a sighted user gets from the row's
 * layout (the glyph, the corner mark, the project line) reaches a screen-reader user only if it
 * is in this sentence, and a refactor that drops one of them changes nothing a golden can see.
 *
 * Order is part of the contract. It is "what decides whether to open it" first — which agent,
 * what it is called, whether it is blocked on you — because TalkBack users interrupt a long
 * announcement, and the fact that arrives after the interruption was never delivered.
 */
class AnnouncementTest {

    private fun row(
        vendor: AgentVendor = AgentVendor.CLAUDE,
        title: String = "Fix the parser",
        projectName: String = "plugin",
        liveLine: String? = null,
    ) = MobileFleetRow(
        key = "k",
        vendor = vendor,
        accountId = "default",
        accountLabel = null,
        projectPath = "/p/plugin",
        projectName = projectName,
        gitBranch = null,
        title = title,
        attention = SessionAttentionState.WAITING_ON_YOU,
        waitingReason = null,
        lastActivityMs = 1_000,
        costUsd = 0.0,
        costKnown = true,
        contextPct = null,
        messageCount = 1,
        model = null,
        liveLine = liveLine,
    )

    @Test
    fun `a row announces vendor, title, state, and project`() {
        assertEquals(
            "Claude conversation, Fix the parser, Waiting on you, in plugin",
            rowAnnouncement(row(), FleetGroup.WAITING),
        )
    }

    /**
     * The negative control for the vendor half: two rows identical but for the agent must not
     * announce identically. The glyph that distinguishes them on screen ("✳" / "◆") is a
     * character TalkBack reads as a symbol or skips.
     */
    @Test
    fun `the vendor is a word, and differs between vendors`() {
        val claude = rowAnnouncement(row(vendor = AgentVendor.CLAUDE), FleetGroup.WAITING)
        val codex = rowAnnouncement(row(vendor = AgentVendor.CODEX), FleetGroup.WAITING)
        assertTrue(claude.startsWith("Claude conversation, "))
        assertTrue(codex.startsWith("Codex conversation, "))
        assertTrue(claude != codex)
    }

    /**
     * The state is carried by a 17-dp shape in the row's corner and by a heading that is off
     * screen for most of a long list, so the announcement is the only place it is reliably
     * available — and it must differ per group, not just be present.
     */
    @Test
    fun `every group names itself in the announcement`() {
        val said = FleetGroup.entries.map { rowAnnouncement(row(), it) }
        FleetGroup.entries.forEachIndexed { i, group ->
            assertTrue("${group.name} missing from \"${said[i]}\"", said[i].contains(group.title))
        }
        assertEquals("two groups announced alike", FleetGroup.entries.size, said.toSet().size)
    }

    /** An untitled row still has to be distinguishable from one whose title failed to load. */
    @Test
    fun `a blank title says so rather than announcing two commas`() {
        val said = rowAnnouncement(row(title = "   "), FleetGroup.RUNNING)
        assertEquals("Claude conversation, no title, Running, in plugin", said)
    }

    /** What the agent is doing right now, when it is doing anything. */
    @Test
    fun `a live line is announced, and absent when there is none`() {
        assertTrue(
            rowAnnouncement(row(liveLine = "Editing Selector.kt"), FleetGroup.RUNNING)
                .contains(", Editing Selector.kt"),
        )
        assertTrue(
            !rowAnnouncement(row(liveLine = "  "), FleetGroup.RUNNING).contains(",  ,"),
        )
    }
}
