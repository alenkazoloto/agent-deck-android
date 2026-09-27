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
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import com.github.claudeagents.core.mobile.MobileScheduleDependencySource
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledRow
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.AfterSessions
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.ui.ScheduleTargetPickers
import androidx.compose.foundation.layout.Column
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduleDueSelector
import dev.agentdeck.companion.ui.ScheduleDuePick
import dev.agentdeck.companion.ui.ScheduleSourcesOffer
import dev.agentdeck.companion.ui.ScheduleSourcesPicker
import dev.agentdeck.companion.ui.ScheduleSourcesState
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
    private val created = mutableListOf<Pair<NewChatTarget, Long>>()

    private fun show(
        canCreate: Boolean = true,
        projects: List<String> = listOf("/Users/dev/Plugin"),
        hello: MobileHello? = fixture.hello,
        vendors: List<AgentVendor> = listOf(AgentVendor.CLAUDE),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ScheduledScreen(
                        rows = rows.value,
                        loading = false,
                        canCreate = canCreate,
                        projects = projects,
                        hello = hello,
                        draft = "Run the regression tests tomorrow.",
                        onDraft = {},
                        onRefresh = {},
                        onCreate = { target, due, _, _ -> created.add(target to due) },
                        onCommand = { action, ids, _ -> commands.add(action to ids) },
                        vendors = vendors,
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
    fun `a row waiting on a run names the wait and its cadence instead of a due moment`() {
        rows.value = listOf(
            MobileScheduledRow(
                "w1", "Follow up on the migration", "/Users/dev/Plugin", "abc", DeckFixtures.NOW,
                MobileScheduledRow.QUEUED, true,
                waiting = "After the current run", cadence = "Daily at 09:00", agent = "Codex · Work",
            ),
            // An older plugin's row: only the flag, so the phone keeps the words it always had.
            MobileScheduledRow(
                "w2", "Nightly sweep", "/Users/dev/Plugin", null, DeckFixtures.NOW + 24 * 60 * 60 * 1000L,
                MobileScheduledRow.QUEUED, true,
            ),
        )
        show()
        compose.onNodeWithText("Plugin · Daily at 09:00 · After the current run · Codex · Work", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Plugin · repeating · due", substring = true).assertIsDisplayed()
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

    /**
     * mobile-todo Scheduling row 2: the create dialog's "After sessions finish…", driven without the dialog
     * window (a focused text field keeps Robolectric from ever idling it): the pill offers the choice only
     * under `schedule-dependencies`, picking it lists the project's sources, and it stands the repeat down.
     */
    @Test
    fun `the When pill offers waiting for sessions only where the machine can hold them`() {
        val due = ScheduleDuePick()
        val target = NewChatTarget("/Users/dev/Plugin", model = null)
        val picks = mutableStateOf(emptyList<MobileScheduleDependencySelection>())
        val offered = mutableStateOf(true)
        val loads = mutableListOf<String>()
        val sources = ScheduleSourcesOffer(
            ScheduleSourcesState(target.projectPath, loading = false, sources = listOf(
                MobileScheduleDependencySource("s1", "Build the fixture · pending schedule", "queued"),
            )),
            onLoad = { loads += it },
        )
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) { AgentDeckTheme {
                Column {
                    ScheduleDueSelector(due, fixture.hello, target, sessionsOffered = offered.value)
                    if (due.picked(null, sessionsOffered = offered.value) is AfterSessions) {
                        ScheduleSourcesPicker(target.projectPath, sources, picks.value) { picks.value = it }
                    }
                }
            } }
        }
        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("After sessions finish…").performClick()
        compose.runOnIdle { assertEquals(listOf(target.projectPath), loads) }
        compose.onNodeWithText("Build the fixture · pending schedule · queued").performClick()
        compose.onNodeWithText("Include context").performClick()
        compose.runOnIdle {
            assertEquals(listOf(MobileScheduleDependencySelection("s1", inheritContext = true)), picks.value)
            assertTrue(due.picked(null, sessionsOffered = true) is AfterSessions)
            assertTrue("the pick outlived the capability", due.picked(null, sessionsOffered = false) !is AfterSessions)
        }
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

    /**
     * M1: the dialog used to post `vendor = CLAUDE` and no account whatever the machine had. The
     * picks are read back from the target the dialog posts, so a pill that painted but did not
     * reach it fails. Driven without the dialog window: with a project open, Robolectric never
     * lets that window settle (true at the pre-M1 commit); E2E M07 covers the dialog on a device.
     */
    @Test
    fun `the schedule pickers set the agent and account the prompt is created on`() {
        val hello = fixture.hello!!.copy(
            capabilities = fixture.hello!!.capabilities + MobileProtocol.Capability.ACCOUNTS,
            accounts = mapOf(
                AgentVendor.CLAUDE to listOf(MobileScheduleAccountOption("default", "Personal")),
                AgentVendor.CODEX to listOf(
                    MobileScheduleAccountOption("codex-default", "ChatGPT"),
                    MobileScheduleAccountOption("codex-work", "Work ChatGPT"),
                ),
            ),
            activeAccounts = mapOf(AgentVendor.CLAUDE to "default", AgentVendor.CODEX to "codex-default"),
        )
        val target = mutableStateOf(NewChatTarget("/Users/dev/Plugin", model = null))
        compose.setContent {
            AgentDeckTheme {
                Column {
                    ScheduleTargetPickers(
                        target = target.value,
                        projects = listOf("/Users/dev/Plugin"),
                        vendors = listOf(AgentVendor.CLAUDE, AgentVendor.CODEX),
                        hello = hello,
                        onTarget = { target.value = it },
                    )
                }
            }
        }
        compose.onNodeWithText("Agent Claude").assertIsDisplayed()
        compose.onNodeWithText("Account", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Model Default").performClick()
        compose.onNodeWithText("Opus 5").performClick()
        compose.onNodeWithText("Agent Claude").performClick()
        compose.onNodeWithText("Codex").performClick()
        compose.runOnIdle { assertEquals("a Claude model survived the switch to Codex", null, target.value.model) }
        compose.onNodeWithText("Account ChatGPT").assertIsDisplayed().performClick()
        compose.onNodeWithText("Work ChatGPT").performClick()
        compose.onNodeWithText("Account Work ChatGPT").assertIsDisplayed()

        compose.runOnIdle {
            assertEquals(AgentVendor.CODEX, target.value.vendor)
            assertEquals("codex-work", target.value.accountId)
            assertEquals("codex-work", NewChat.accountFor(hello, target.value))
        }

        compose.onNodeWithText("Agent Codex").performClick()
        compose.onNodeWithText("Claude").performClick()
        compose.runOnIdle {
            assertEquals("a Codex account survived the switch to Claude", null, target.value.accountId)
            assertEquals("default", NewChat.accountFor(hello, target.value))
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
