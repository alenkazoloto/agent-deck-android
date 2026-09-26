package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileAcpAgentAction
import com.github.claudeagents.core.mobile.MobileAcpAgents
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Resources › "ACP agents" is the desk's Connections table for the phone: offered only by a machine that
 * advertises `acp-agent-status`, each agent's state in the desk's words, and Connect / Sign in only where the machine
 * says the desk would offer them and this phone holds the grant (`acp-agent-actions`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AcpAgentsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val connected = MobileAcpAgents.Agent("junie", "Junie", "Connected · ACP 1", "ok", "none")
    private val stopped = MobileAcpAgents.Agent("pi", "Pi", "Not connected", "idle", "connect", "Connect to Pi to check the command works.")
    private val locked = MobileAcpAgents.Agent(
        "opencode", "OpenCode", "Sign-in required", "error", "sign-in", "OpenCode is running and needs an account before it will answer.",
        methods = listOf(MobileAcpAgents.Method("oauth", "Browser", "Opens a page on the machine"), MobileAcpAgents.Method("key", "API key")),
    )
    private val missing = MobileAcpAgents.Agent("nightly", "Nightly build", "Not installed", "error", "edit-command", "The command did not start. Check it is installed and on your PATH.")

    private val acted = mutableListOf<MobileAcpAgentAction>()
    private var reads = 0

    private fun show(
        status: Boolean = true,
        actions: Boolean = true,
        act: suspend (MobileAcpAgentAction) -> String? = { null },
        answer: () -> MobileAcpAgents?,
    ) {
        val base = DeckFixtures.byName("settings")!!
        val extra = setOfNotNull(
            MobileProtocol.Capability.ACP_AGENT_STATUS.takeIf { status },
            MobileProtocol.Capability.ACP_AGENT_ACTIONS.takeIf { actions },
        )
        val state = base.copy(hello = base.hello!!.copy(capabilities = base.hello!!.capabilities + extra))
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    state = state, onSettings = {}, onSwitchMachine = {}, onAddMachine = {}, onUnpair = {},
                    onRefreshHello = {}, onRefreshPush = {}, onChoosePush = {}, onCheckUpdate = {},
                    onDownloadUpdate = {}, onInstallUpdate = {}, onReleasePage = {},
                    onLoadAcpAgents = { reads++; answer() },
                    onActOnAcpAgent = { request -> acted += request; act(request) },
                )
            }
        }
    }

    private fun open() {
        compose.onNodeWithText("ACP agents").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a machine without the capability has no row`() {
        show(status = false) { null }

        assertEquals(0, compose.onAllNodesWithText("ACP agents").fetchSemanticsNodes().size)
    }

    @Test
    fun `each agent's state is the desk's sentence, with its advice`() {
        show { MobileAcpAgents(listOf(connected, stopped, locked, missing)) }
        open()

        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p08/acp-agents.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText("Junie").assertIsDisplayed()
        compose.onNodeWithText("Connected · ACP 1").assertIsDisplayed()
        compose.onNodeWithText("Connect to Pi to check the command works.").assertIsDisplayed()
        compose.onNodeWithText("OpenCode is running and needs an account before it will answer.").assertIsDisplayed()
        compose.onNodeWithText("The command did not start. Check it is installed and on your PATH.").assertIsDisplayed()
        assertEquals("the command is the desk's to edit: no button for it", 0, compose.onAllNodesWithText("Edit command").fetchSemanticsNodes().size)
    }

    @Test
    fun `a phone without the grant reads the list and is offered no button`() {
        show(actions = false) { MobileAcpAgents(listOf(stopped, locked)) }
        open()

        compose.onNodeWithText("Not connected").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Connect").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Sign in with Browser").fetchSemanticsNodes().size)
    }

    @Test
    fun `Connect sends that agent alone and reads the list again`() {
        show { MobileAcpAgents(listOf(connected, stopped)) }
        open()
        assertEquals(1, reads)

        compose.onNodeWithTag("acp-agent-connect-pi").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileAcpAgentAction("pi", MobileAcpAgentAction.CONNECT)), acted)
        assertEquals("the list is what the machine says now", 2, reads)
    }

    @Test
    fun `Sign in names the method the agent offered, one button per method`() {
        show { MobileAcpAgents(listOf(locked)) }
        open()

        compose.onNodeWithText("Opens a page on the machine").assertIsDisplayed()
        compose.onNodeWithTag("acp-agent-signin-opencode-key").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileAcpAgentAction("opencode", MobileAcpAgentAction.SIGN_IN, "key")), acted)
    }

    @Test
    fun `an action the machine did not start says why in its sentence`() {
        show(act = { "That agent is already connected or starting, so it was left alone. Reopen the list." }) { MobileAcpAgents(listOf(stopped)) }
        open()

        compose.onNodeWithTag("acp-agent-connect-pi").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("That agent is already connected or starting, so it was left alone. Reopen the list.").assertIsDisplayed()
    }

    @Test
    fun `a machine that did not answer and one with no agents each say so`() {
        show { null }
        open()
        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }

    @Test
    fun `no agents switched on is said, not an empty sheet`() {
        show { MobileAcpAgents() }
        open()

        compose.onNodeWithText("No ACP agents are switched on in Settings › Connections on this machine.").assertIsDisplayed()
    }
}
