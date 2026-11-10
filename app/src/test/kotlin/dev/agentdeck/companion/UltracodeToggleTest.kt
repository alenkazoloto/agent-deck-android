package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileSendRequest
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.OutgoingSend
import dev.agentdeck.companion.ui.RunChoiceSummary
import dev.agentdeck.companion.ui.RunOptionRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ultracode pill (Claude Code 2.1.284's switch): sent only under `ultracode-toggle`, where the
 * effort ladder also drops the retired `ultracode` rung that was the phone's only Ultracode before.
 */
class UltracodeToggleTest {

    private val ladder = listOf(MobileModelOption("low", "Low effort"), MobileModelOption("xhigh", "Extra-high effort"), MobileModelOption("ultracode", "Ultracode"))

    private fun hello(vararg caps: String) = MobileHello(
        protocolVersion = MobileProtocol.VERSION, machineName = "m", ideName = "IDE", pluginVersion = "1",
        capabilities = caps.toList(), effort = mapOf(AgentVendor.CLAUDE to ladder),
    )

    @Test fun `the switch is sent only to a machine that advertised it`() {
        val picks = MobileRunSelection(null, null, null, ultracode = true)

        assertEquals(picks, NewChat.forWire(hello(MobileProtocol.Capability.ULTRACODE_TOGGLE), picks))
        assertNull("a run-toggles-only machine would drop it unread", NewChat.forWire(hello(MobileProtocol.Capability.RUN_TOGGLES), picks).ultracode)
    }

    @Test fun `the retired rung leaves the ladder only where the switch replaces it`() {
        fun rungs(vararg caps: String) = RunOptionRows.effort(hello(MobileProtocol.Capability.EFFORT, *caps), AgentVendor.CLAUDE, null).map { it.value }

        assertEquals(listOf(null, "low", "xhigh"), rungs(MobileProtocol.Capability.ULTRACODE_TOGGLE))
        assertEquals(listOf(null, "low", "xhigh", "ultracode"), rungs())
        assertTrue(RunOptionRows.ultracodeOffered(hello(MobileProtocol.Capability.ULTRACODE_TOGGLE)))
        assertFalse(RunOptionRows.ultracodeOffered(hello(MobileProtocol.Capability.RUN_TOGGLES)))
    }

    @Test fun `a composer pick lays over the desk, is released once sent, and the summary names it`() {
        val desk = MobileRunSelection(null, "low", null, ultracode = null)
        val picks = ComposerPicks().with(ComposerPicks.Field.ULTRACODE, "true")

        val run = picks.over(desk)
        assertEquals(true, run.ultracode)
        assertFalse(MobileRunSelection.Field.ULTRACODE in picks.unpicked())
        assertTrue(picks.without(run).picked.isEmpty())
        val caps = arrayOf(MobileProtocol.Capability.EFFORT, MobileProtocol.Capability.ULTRACODE_TOGGLE)
        assertEquals("Low effort · Ultracode", RunChoiceSummary.of(hello(*caps), AgentVendor.CLAUDE, run))
        assertFalse("Codex has no Ultracode", "Ultracode" in RunChoiceSummary.of(hello(*caps), AgentVendor.CODEX, run))
    }

    @Test fun `the queued send keeps the switch across a restart and a vendor change drops it`() {
        val item = OutgoingSend(
            clientMessageId = "c1", key = "k", projectPath = "/p", vendor = AgentVendor.CLAUDE, label = "chat",
            prompt = "go", ultracode = true,
        )

        val back = OutgoingSend.fromJson(item.toJson())!!

        assertEquals(true, back.ultracode)
        assertEquals(true, MobileSendRequest.fromJson(back.request().toJson()).ultracode)
        assertNull(NewChatTarget("/p", AgentVendor.CLAUDE, ultracode = true).withVendor(AgentVendor.CODEX).ultracode)
    }
}
