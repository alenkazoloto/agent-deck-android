package dev.agentdeck.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileAcpAgent
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ScheduleTargetPickers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** mobile-todo Scheduling: an ACP agent in the Agent pill, driven through the production composables. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AcpAgentPickerInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val base = DeckFixtures.byName("convo-composer-pills")!!.hello!!
    private val hello = base.copy(
        capabilities = base.capabilities + MobileProtocol.Capability.ACP_AGENTS,
        acpAgents = listOf(MobileAcpAgent("gemini", "Gemini CLI")),
    )

    @Test
    fun `an ACP agent hides the run pickers it has no notion of, and a vendor brings them back`() {
        val target = mutableStateOf(NewChatTarget("/Users/dev/Plugin"))
        compose.setContent {
            AgentDeckTheme {
                Column {
                    ScheduleTargetPickers(
                        target = target.value,
                        projects = listOf("/Users/dev/Plugin"),
                        vendors = NewChat.vendorOptions(emptyList(), hello),
                        hello = hello,
                        onTarget = { target.value = it },
                    )
                }
            }
        }
        compose.onNodeWithText("Default effort").assertIsDisplayed()
        compose.onNodeWithText("Agent Claude").performClick()
        compose.onNodeWithText("Gemini CLI").performClick()
        compose.onNodeWithText("Agent Gemini CLI").assertIsDisplayed()
        compose.onNodeWithText("Default effort").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("gemini", NewChat.acpAgentFor(hello, target.value))
            assertNull(NewChat.accountFor(hello, target.value))
        }

        compose.onNodeWithText("Agent Gemini CLI").performClick()
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("Default effort").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(target.value.acpAgentId)
            assertEquals(AgentVendor.CODEX, target.value.vendor)
        }
    }

    @Test
    fun `a machine that lists no ACP agent offers only the vendors`() {
        compose.setContent {
            AgentDeckTheme {
                Column {
                    ScheduleTargetPickers(
                        target = NewChatTarget("/Users/dev/Plugin"),
                        projects = listOf("/Users/dev/Plugin"),
                        vendors = NewChat.vendorOptions(emptyList(), base),
                        hello = base,
                        onTarget = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Agent Claude").performClick()
        compose.onNodeWithText("Gemini CLI").assertDoesNotExist()
    }
}
