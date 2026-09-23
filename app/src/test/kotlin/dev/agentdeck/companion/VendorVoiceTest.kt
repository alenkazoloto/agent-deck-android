package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.ui.label
import dev.agentdeck.companion.ui.workingText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The running row speaks in the viewed conversation's own vendor voice.
 *
 * It said "Claude is working…" over every run, Codex included — a hardcoded string three files
 * from the `label()` that already knew the difference, and the same shape as the desktop bug
 * where a Codex chat claimed a Claude account. `Screen.Conversation` has carried `vendor` all
 * along; nothing was reading it.
 */
class VendorVoiceTest {

    @Test
    fun `each vendor names itself in the working row`() {
        assertEquals("Claude is working…", AgentVendor.CLAUDE.workingText())
        assertEquals("Codex is working…", AgentVendor.CODEX.workingText())
    }

    @Test
    fun `every vendor has a voice, so a new one cannot fall back to Claude`() {
        for (vendor in AgentVendor.entries) {
            assertEquals("${vendor.label()} is working…", vendor.workingText())
        }
    }
}
