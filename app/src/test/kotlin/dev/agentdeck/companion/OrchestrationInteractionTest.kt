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
import com.github.claudeagents.core.mobile.MobileWorkflowAgent
import com.github.claudeagents.core.mobile.MobileWorkflowPhase
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
    private var opened: MobileWorkflowRun? = null

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
                agents = 2, agentsFailed = 1, project = "/work/project", key = "v2:chat",
                phases = listOf(
                    MobileWorkflowPhase(
                        "Scan", "2 agents · 1 failed", failed = true, detail = "read every module", counters = "2k tokens · 4s",
                        agents = listOf(
                            MobileWorkflowAgent("reader", "Done", result = "all clear"),
                            MobileWorkflowAgent("checker", "died", failed = true, result = "Never returned"),
                        ),
                    ),
                    MobileWorkflowPhase("Ship", "Never ran"),
                ),
                log = listOf("scanning 2 modules"),
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
                    onOpenWorkflowChat = { opened = it },
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
    fun `a run opens onto the desk's phase table and narrator`() {
        show { orchestration }
        open()

        assertEquals(0, compose.onAllNodesWithTag("orchestration-phase").fetchSemanticsNodes().size)
        compose.onNodeWithText("Show phases").performScrollTo().performClick()

        compose.onNodeWithText("Scan").assertIsDisplayed()
        compose.onNodeWithText("2 agents · 1 failed · 2k tokens · 4s").assertIsDisplayed()
        compose.onNodeWithText("read every module").assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("all clear").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("died").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Never returned").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Never ran").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("scanning 2 modules").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p35/orchestration-run-detail.png", RECORD)
        compose.onNodeWithText("Hide phases").performScrollTo().performClick()
        assertEquals(0, compose.onAllNodesWithTag("orchestration-phase").fetchSemanticsNodes().size)
    }

    @Test
    fun `Open chat closes the sheet and hands the run to the navigator`() {
        show { orchestration }
        open()

        compose.onNodeWithText("Open chat").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("wf_1", opened?.runId)
        assertEquals("v2:chat", opened?.key)
        assertEquals(0, compose.onAllNodesWithTag("orchestration-list").fetchSemanticsNodes().size)
    }

    @Test
    fun `a run without a chat the machine can open offers none`() {
        show { MobileOrchestration(runs = listOf(MobileWorkflowRun("wf_9", "Old run", "Completed"))) }
        open()

        compose.onNodeWithText("Old run").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Open chat").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Show phases").fetchSemanticsNodes().size)
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
