package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileBoardTask
import com.github.claudeagents.core.mobile.MobileOrchestration
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTeamBoard
import com.github.claudeagents.core.mobile.MobileWorkflowRun
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Agents › "Agent teams and workflow runs" is the desk's Orchestration page, read-only:
 * offered only by a machine that advertises `orchestration`, read again each time it opens, and
 * every state — loading, unreachable, unavailable, empty — has its own sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrchestrationInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var loads = 0

    private val orchestration = MobileOrchestration(
        boards = listOf(
            MobileTeamBoard(
                key = "alpha", title = "Alpha team", members = listOf("lead (planner)", "helper"), idle = listOf("helper"),
                tasks = listOf(
                    MobileBoardTask("1", "Write the parser", "completed", owner = "lead"),
                    MobileBoardTask("2", "Review the parser", "pending", depth = 1, note = "Ready — nobody has picked it up"),
                ),
                total = 2, done = 1,
            ),
        ),
        runs = listOf(
            MobileWorkflowRun(
                "wf_1", "Review changed files", "Completed", startedAtMs = 1, durationMs = 65_000,
                agents = 2, agentsFailed = 1, project = "/work/project",
            ),
        ),
    )

    private fun show(capable: Boolean = true, answer: suspend () -> MobileOrchestration?) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) {
            base.copy(hello = base.hello!!.copy(capabilities = base.hello!!.capabilities + MobileProtocol.Capability.ORCHESTRATION))
        } else {
            base
        }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    state = state, onSettings = {}, onSwitchMachine = {}, onAddMachine = {}, onUnpair = {},
                    onRefreshHello = {}, onRefreshPush = {}, onChoosePush = {}, onCheckUpdate = {},
                    onDownloadUpdate = {}, onInstallUpdate = {}, onReleasePage = {},
                    onLoadOrchestration = { loads++; answer() },
                )
            }
        }
    }

    private fun open() {
        compose.onNodeWithText("Agent teams and workflow runs").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a machine without the capability has no row`() {
        show(capable = false) { orchestration }

        assertEquals(0, compose.onAllNodesWithText("Agent teams and workflow runs").fetchSemanticsNodes().size)
    }

    @Test
    fun `the sheet reads each board task with its status word and the machine's own note`() {
        show { orchestration }
        open()

        compose.onNodeWithText("Alpha team — 1 of 2 done").assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p35/orchestration-sheet.png", RECORD)
        compose.onNodeWithText("Members: lead (planner), helper").assertIsDisplayed()
        compose.onNodeWithText("Idle: helper").assertIsDisplayed()
        compose.onNodeWithText("Done · lead").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Ready — nobody has picked it up").assertIsDisplayed()
        compose.onNodeWithText("Review changed files").assertIsDisplayed()
        compose.onNodeWithText("Completed · 2 agents · 1 failed · 1m 5s").assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }
        open()

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("orchestration-board").fetchSemanticsNodes().size)
    }

    @Test
    fun `an account the machine could not read gets its own sentence, not the empty one`() {
        show { MobileOrchestration(unavailable = "The active account directory is unavailable.") }
        open()

        compose.onNodeWithText("The active account directory is unavailable.").assertIsDisplayed()
    }

    @Test
    fun `a machine with neither teams nor runs says what that means`() {
        show { MobileOrchestration() }
        open()

        compose.onNodeWithText(
            "No agent teams or workflow runs on this machine. Teams are experimental in Claude Code, and runs appear once a workflow has finished.",
        ).assertIsDisplayed()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
