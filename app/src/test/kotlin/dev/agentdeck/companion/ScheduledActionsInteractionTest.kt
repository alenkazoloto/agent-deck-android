package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduledScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Taps the production icon routes and verifies the command and row identity they dispatch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduledActionsInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = DeckFixtures.byName("scheduled")!!
    private val rows = mutableStateOf(fixture.scheduled)
    private val commands = mutableListOf<Pair<String, List<String>>>()
    private val created = mutableListOf<Triple<String, Long, String?>>()

    private fun show(canCreate: Boolean = true, projects: List<String> = listOf("/Users/dev/Plugin")) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ScheduledScreen(
                        rows = rows.value,
                        loading = false,
                        canCreate = canCreate,
                        projects = projects,
                        hello = fixture.hello,
                        draft = "Run the regression tests tomorrow.",
                        onDraft = {},
                        onRefresh = {},
                        onCreate = { project, due, model -> created.add(Triple(project, due, model)) },
                        onCommand = { action, ids, _ -> commands.add(action to ids) },
                    )
                }
            }
        }
    }

    @Test
    fun `row actions are named icons and dispatch the correct scheduled row`() {
        show()
        listOf("Resume", "Pause", "Run now").forEach { label ->
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }
        compose.onAllNodesWithContentDescription("Pause").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Run now").assertCountEquals(3)
        compose.onAllNodesWithContentDescription("Cancel this prompt").assertCountEquals(3)

        compose.onNodeWithContentDescription("Resume").assertIsDisplayed().performClick()
        compose.onAllNodesWithContentDescription("Pause")[1].performClick()
        compose.onAllNodesWithContentDescription("Run now")[0].performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    MobileScheduledCommand.RESUME to listOf("s2"),
                    MobileScheduledCommand.PAUSE to listOf("s3"),
                    MobileScheduledCommand.RUN_NOW to listOf("s1"),
                ),
                commands,
            )
        }
    }

    @Test
    fun `single cancellation waits for confirmation and keeps the selected row id`() {
        show()
        compose.onAllNodesWithContentDescription("Cancel this prompt")[1].performClick()
        compose.onNodeWithText("Cancel this prompt?").assertIsDisplayed()
        compose.onNodeWithText("· ${fixture.scheduled[1].prompt}").assertIsDisplayed()
        compose.runOnIdle { assertTrue(commands.isEmpty()) }

        compose.onNodeWithText("Cancel them").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileScheduledCommand.CANCEL to listOf("s2")), commands)
        }
    }

    @Test
    fun `keeping a prompt dismisses cancellation without dispatching a command`() {
        show()
        compose.onAllNodesWithContentDescription("Cancel this prompt")[0].performClick()
        compose.onNodeWithText("Keep them").performClick()
        compose.onNodeWithText("Cancel this prompt?").assertDoesNotExist()
        compose.runOnIdle { assertTrue(commands.isEmpty()) }
    }

    @Test
    fun `bulk cancellation captures displayed ids before a new scheduled row arrives`() {
        show()
        compose.onNodeWithContentDescription("Cancel all 3 prompts").performClick()
        compose.runOnIdle {
            assertTrue(commands.isEmpty())
            rows.value = rows.value + rows.value.first().copy(id = "arrived-later", prompt = "Another task")
        }
        compose.onNodeWithText("Cancel 3 prompts?").assertIsDisplayed()
        compose.onNodeWithText("Cancel them").performClick()

        compose.runOnIdle {
            assertEquals(listOf(MobileScheduledCommand.CANCEL to listOf("s1", "s2", "s3")), commands)
        }
    }

    @Test
    fun `header create icon opens the scheduling dialog without submitting work`() {
        show(projects = emptyList())
        compose.onNodeWithText("Schedule a prompt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Schedule a prompt").performClick()
        assertCreateDialog()
    }

    @Test
    fun `empty state exposes one create icon that opens the scheduling dialog`() {
        rows.value = emptyList()
        show(projects = emptyList())
        compose.onAllNodesWithContentDescription("Schedule a prompt").assertCountEquals(1)
        compose.onNodeWithContentDescription("Schedule a prompt").performClick()
        assertCreateDialog()
    }

    @Test
    fun `unsupported scheduling does not expose a create route`() {
        rows.value = emptyList()
        show(canCreate = false)
        compose.onNodeWithContentDescription("Schedule a prompt").assertDoesNotExist()
        compose.runOnIdle { assertTrue(created.isEmpty()) }
    }

    @Test
    fun `long press explains the run icon without running the prompt`() {
        show()
        compose.onAllNodesWithContentDescription("Run now")[0].performTouchInput { longClick() }
        compose.onNodeWithText("Run now").assertIsDisplayed()
        compose.runOnIdle { assertTrue("A tooltip must not execute the action", commands.isEmpty()) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodesWithContentDescription("Run now")[0].performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileScheduledCommand.RUN_NOW to listOf("s1")), commands)
        }
    }

    private fun assertCreateDialog() {
        compose.onNodeWithText("Schedule a prompt").assertIsDisplayed()
        compose.onNodeWithText("No project is open on this machine, so there is nowhere to run a prompt.")
            .assertIsDisplayed()
        compose.onNodeWithText("Schedule").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue("Opening the editor must not create a scheduled task", created.isEmpty())
            assertTrue(commands.isEmpty())
        }
    }
}
