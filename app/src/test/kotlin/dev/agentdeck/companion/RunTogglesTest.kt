package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileSendRequest
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.OutgoingSend
import dev.agentdeck.companion.ui.RunOptionRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fast and Thinking pills: a pick reaches the wire only under `run-toggles`, and survives the queue. */
class RunTogglesTest {

    private fun hello(vararg caps: String) = MobileHello(
        protocolVersion = MobileProtocol.VERSION, machineName = "m", ideName = "IDE", pluginVersion = "1",
        capabilities = caps.toList(),
    )

    @Test fun `a pick is sent only to a machine that advertised run-toggles`() {
        val picks = MobileRunSelection(null, null, null, fastMode = true, thinking = false)

        assertEquals(picks, NewChat.forWire(hello(MobileProtocol.Capability.RUN_TOGGLES), picks))
        assertEquals(MobileRunSelection(null, null, null), NewChat.forWire(hello(MobileProtocol.Capability.EFFORT), picks))
    }

    @Test fun `the pills are offered for Claude on a capable machine only`() {
        assertTrue(RunOptionRows.togglesOffered(hello(MobileProtocol.Capability.RUN_TOGGLES)))
        assertFalse(RunOptionRows.togglesOffered(hello(MobileProtocol.Capability.EFFORT)))
        assertFalse(RunOptionRows.togglesOffered(null))
        assertEquals(listOf(null, true, false), RunOptionRows.toggle().map { it.value })
    }

    @Test fun `Fast is offered for both agents and Thinking for Claude alone`() {
        assertEquals(setOf(AgentVendor.CLAUDE, AgentVendor.CODEX), RunOptionRows.FAST_VENDORS)
    }

    @Test fun `a composer pick lays over the desk and a picked Default stays a pick`() {
        val desk = MobileRunSelection("opus", null, null, fastMode = true, thinking = null)
        val picks = ComposerPicks().with(ComposerPicks.Field.FAST, "false").with(ComposerPicks.Field.THINKING, null)

        assertEquals(MobileRunSelection("opus", null, null, fastMode = false, thinking = null), picks.over(desk))
        assertEquals(setOf(MobileRunSelection.Field.MODEL, MobileRunSelection.Field.EFFORT, MobileRunSelection.Field.MODE), picks.unpicked())
    }

    @Test fun `a landed send releases the toggles it carried and keeps a re-made pick`() {
        val picks = ComposerPicks().with(ComposerPicks.Field.FAST, "true").with(ComposerPicks.Field.THINKING, "false")

        val left = picks.without(MobileRunSelection(null, null, null, fastMode = true, thinking = true))

        assertEquals(mapOf<ComposerPicks.Field, String?>(ComposerPicks.Field.THINKING to "false"), left.picked)
    }

    @Test fun `the queued send keeps its toggles across a restart and puts them on the request`() {
        val item = OutgoingSend(
            clientMessageId = "c1", key = "k", projectPath = "/p", vendor = AgentVendor.CLAUDE, label = "chat",
            prompt = "go", fastMode = true, thinking = false,
        )

        val back = OutgoingSend.fromJson(item.toJson())!!

        assertEquals(true, back.fastMode)
        assertEquals(false, back.thinking)
        val request = MobileSendRequest.fromJson(back.request().toJson())
        assertEquals(true, request.fastMode)
        assertEquals(false, request.thinking)
        assertNull(MobileSendRequest.fromJson(item.copy(fastMode = null, thinking = null).request().toJson()).fastMode)
    }

    @Test fun `changing agent drops the toggles`() {
        assertNull(NewChatTarget("/p", AgentVendor.CLAUDE, fastMode = true, thinking = false).withVendor(AgentVendor.CODEX).fastMode)
    }
}
