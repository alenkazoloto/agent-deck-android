package dev.agentdeck.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobilePreset
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileSendRequest
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.OutgoingSend
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.PresetSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** mobile-todo "saved agent presets": the desk's picker on New chat, and what a start from one sends. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PresetPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private val reviewer = MobilePreset(
        "Reviewer", AgentVendor.CLAUDE, model = "sonnet", effort = "high", permissionMode = "acceptEdits",
        extras = listOf("Extra instructions"),
    )
    private val codexer = MobilePreset("Codexer", AgentVendor.CODEX, permissionMode = "read-only", accountId = "work")
    private val plan = MobilePreset("Plan first", AgentVendor.CLAUDE, permissionMode = "plan", builtIn = true)
    private val codexPlan = MobilePreset("Codex only built-in", AgentVendor.CODEX, builtIn = true)
    private val hello = MobileHello(
        1, "Mac", "IDEA", "1", listOf(MobileProtocol.Capability.AGENT_PRESETS),
        presets = listOf(reviewer, codexer, plan, codexPlan),
    )

    @Test
    fun `a shipped preset is offered on its own agent only and a saved one on any`() {
        val claude = NewChat.presets(hello, NewChatTarget("/p")).map { it.name }
        assertEquals(listOf("Reviewer", "Codexer", "Plan first"), claude)
        val codex = NewChat.presets(hello, NewChatTarget("/p", AgentVendor.CODEX)).map { it.name }
        assertEquals(listOf("Reviewer", "Codexer", "Codex only built-in"), codex)
    }

    @Test
    fun `no capability and an ACP start offer none`() {
        assertTrue(NewChat.presets(hello.copy(capabilities = emptyList()), NewChatTarget("/p")).isEmpty())
        assertTrue(NewChat.presets(null, NewChatTarget("/p")).isEmpty())
    }

    @Test
    fun `applying a preset fills the agent and cells, and it stays on only while they spell it`() {
        val target = NewChatTarget("/p", AgentVendor.CLAUDE, model = "opus", fastMode = true).withPreset(codexer)
        assertEquals(AgentVendor.CODEX, target.vendor)
        assertNull(target.model)
        assertNull("Fast belongs to the previous agent's pick", target.fastMode)
        assertEquals("work", target.accountId)
        assertEquals(codexer, NewChat.presetFor(hello, target))

        assertNull("moved by hand", NewChat.presetFor(hello, target.copy(effort = "high")))
        assertNull(NewChat.presetFor(hello, target.copy(accountId = "personal")))
        assertNull(NewChat.presetFor(hello, target.withVendor(AgentVendor.CLAUDE)))
    }

    @Test
    fun `a preset with no account is on whichever account is picked`() {
        val target = NewChatTarget("/p").withPreset(reviewer).copy(accountId = "work")
        assertEquals(reviewer, NewChat.presetFor(hello, target))
    }

    @Test
    fun `the request names the preset only while it is on, and only for a new chat`() {
        val on = NewChatTarget("/p").withPreset(reviewer)
        val send = OutgoingSend(
            clientMessageId = "c1", key = null, projectPath = "/p", vendor = on.vendor, label = "p", prompt = "go",
            model = on.model, effort = on.effort, permissionMode = on.permissionMode,
            presetName = NewChat.presetFor(hello, on)?.name,
        )
        assertEquals("Reviewer", send.request().presetName)
        assertEquals("Reviewer", OutgoingSend.fromJson(send.toJson())?.presetName)
        assertNull(send.copy(key = "claude:abc").request().presetName)
        assertNull(NewChat.presetFor(hello, on.copy(model = null)))
        assertNull(MobileSendRequest.fromJson(send.request().toJson().also { it.remove("presetName") }).presetName)
    }

    @Test
    fun `the pill applies a pick, reads No preset after a hand edit, and No preset keeps the cells`() {
        val target = mutableStateOf(NewChatTarget("/p"))
        compose.setContent {
            AgentDeckTheme {
                Column { PresetSelector(hello, target.value, onTarget = { target.value = it }) }
            }
        }
        compose.onNodeWithText("Preset: No preset").assertIsDisplayed()
        compose.onNodeWithText("Preset: No preset").performClick()
        compose.onNodeWithText("Reviewer").performClick()
        compose.onNodeWithText("Preset: Reviewer").assertIsDisplayed()
        compose.onNodeWithText("Extra instructions apply on this machine.").assertIsDisplayed()
        compose.runOnIdle { assertEquals("sonnet", target.value.model) }

        compose.runOnIdle { target.value = target.value.copy(effort = "low") }
        compose.onNodeWithText("Preset: No preset").assertIsDisplayed()

        compose.onNodeWithText("Preset: No preset").performClick()
        compose.onNodeWithText("Reviewer").performClick()
        compose.onNodeWithText("Preset: Reviewer").performClick()
        compose.onNodeWithText("No preset").performClick()
        compose.runOnIdle {
            assertNull(target.value.presetName)
            assertEquals("sonnet", target.value.model)
        }
    }
}
