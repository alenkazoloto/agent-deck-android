package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileDeskCommands.Action
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.ui.acpAgentVoice
import dev.agentdeck.companion.ui.acpDeskActions
import dev.agentdeck.companion.ui.label
import dev.agentdeck.companion.ui.offersChanges
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

    @Test
    fun `an ACP chat's header carries the agent's own cost and context, and stays unknown when it reported none`() {
        val silent = page(costText = null, contextPct = null)
        assertEquals("cost unknown", conversationSubtitle(silent))

        val reported = page(costText = "1.30 EUR", contextPct = 42)
        assertEquals("1.30 EUR · 42% context", conversationSubtitle(reported))
    }

    private fun page(costText: String?, contextPct: Int?) = MobileTranscriptPage(
        key = "acp:gemini/s1", title = "t", turns = emptyList(), hasMore = false, costUsd = 0.0, costKnown = false,
        contextPct = contextPct, model = null, liveLine = null, running = false, generatedAtMs = 0L, reportedCost = costText,
    )

    @Test
    fun `an ACP key speaks as its agent and a native key as neither`() {
        assertEquals("Gemini CLI", acpAgentVoice("acp:gemini/s1", "Gemini CLI"))
        assertEquals("ACP agent", acpAgentVoice("acp:gemini/s1", " "))
        assertEquals("ACP agent", acpAgentVoice("acp:gemini/s1", null))
        assertEquals(null, acpAgentVoice("3f2c-uuid", "Gemini CLI"))
    }

    @Test
    fun `an ACP chat keeps only the desk rows that work on any chat, a native chat keeps all`() {
        val all = Action.entries.toSet()
        assertEquals(all, acpDeskActions("3f2c-uuid", all))
        val acp = acpDeskActions("acp:gemini/s1", all)
        assertEquals(setOf(Action.STOP, Action.NEW_CHAT, Action.COPY_LAST_RESPONSE, Action.SETTINGS, Action.CHATS, Action.USAGE, Action.SCHEDULE, Action.CHANGES), acp)
        // A row the phone never offered stays absent rather than appearing because the agent is ACP.
        assertEquals(setOf(Action.STOP), acpDeskActions("acp:gemini/s1", setOf(Action.STOP, Action.REWIND)))
    }

    @Test
    fun `an ACP chat opens Changes only on a machine that reads its agent's edits`() {
        val review = listOf(MobileProtocol.Capability.REVIEW)
        assertEquals(true, offersChanges("3f2c-uuid", review))
        assertEquals(false, offersChanges("acp:gemini/s1", review))
        assertEquals(true, offersChanges("acp:gemini/s1", review + MobileProtocol.Capability.ACP_REVIEW))
        // The ACP capability alone reviews nothing: the machine has to review at all.
        assertEquals(false, offersChanges("acp:gemini/s1", listOf(MobileProtocol.Capability.ACP_REVIEW)))
    }
}
