package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.data.ScheduleRepeat

import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduleIntoChatOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * mobile-todo Scheduling row 1: the desk's "This chat" schedule (⋯ › Schedule message, `/schedule`)
 * from the phone's conversation composer, against the real `ConversationScreen`. Before, a phone
 * could only schedule a new chat, and `/schedule` reached the agent as prose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleIntoChatInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val sent = mutableListOf<String>()
    private val scheduled = mutableListOf<Pair<Long, ScheduleRepeat?>>()
    private val afterRuns = mutableListOf<Boolean>()

    @Test
    fun `the composer schedules its text into this chat, repeating when asked`() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("Rerun the nightly suite")
        compose.onNodeWithContentDescription("Schedule this message").performClick()

        compose.onNodeWithTag("schedule-into-chat-prompt").assertTextContains("Rerun the nightly suite")
        compose.onNodeWithTag("schedule-into-chat-repeat").performClick()
        val before = System.currentTimeMillis()
        compose.onNodeWithText("Schedule").performClick()

        compose.runOnIdle {
            val (due, repeat) = scheduled.single()
            assertTrue("due in an hour: $due", due - before in (HOUR - 60_000)..(HOUR + 60_000))
            assertEquals(ScheduleRepeat(everyMs = HOUR), repeat)
            // The view model consumes it once the machine accepts; a failed schedule must find it here.
            assertEquals("Rerun the nightly suite", draft.value)
            assertTrue(sent.isEmpty())
        }
        compose.onNodeWithTag("schedule-into-chat-prompt").assertDoesNotExist()
    }

    /** mobile-todo Scheduling: the desk's "When the current run in this chat finishes", offered only while the chat runs. */
    @Test
    fun `a running chat offers to wait for its run, which drops the repeat and posts the flag`() {
        show(canAfterRun = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("Then rerun the suite")
        compose.onNodeWithContentDescription("Schedule this message").performClick()

        compose.onNodeWithTag("schedule-into-chat-repeat").assertExists()
        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("When this run finishes").performClick()
        compose.onNodeWithTag("schedule-into-chat-repeat").assertDoesNotExist()
        compose.onNodeWithText("Schedule").performClick()

        compose.runOnIdle {
            assertEquals(listOf(true), afterRuns)
            assertEquals(null, scheduled.single().second)
        }
    }

    @Test
    fun `an idle chat does not offer to wait for a run`() {
        show(canAfterRun = false)
        compose.onNode(hasSetTextAction()).performTextReplacement("Then rerun the suite")
        compose.onNodeWithContentDescription("Schedule this message").performClick()

        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("When this run finishes").assertDoesNotExist()
    }

    @Test
    fun `a bare schedule command opens the dialog instead of messaging the agent`() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("/schedule")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").performClick()

        compose.onNodeWithTag("schedule-into-chat-prompt").assertExists()
        compose.runOnIdle {
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `schedule with text opens the dialog on that text, as the desk's does`() {
        show()
        // Typed as a thumb types it: the name opens the popup, which reads the catalogue that
        // Send asks whether `/scdl` is the user's own; then the words.
        compose.onNode(hasSetTextAction()).performTextReplacement("/scdl")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Schedule a message into this chat").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("/scdl rerun the migration tests")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").performClick()

        compose.onNodeWithTag("schedule-into-chat-prompt").assertTextContains("rerun the migration tests")
        compose.runOnIdle {
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("rerun the migration tests", draft.value)
        }
    }

    @Test
    fun `a machine that cannot queue a prompt offers no schedule and sends the command`() {
        show(offer = false)
        compose.onNode(hasSetTextAction()).performTextReplacement("/schedule")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Schedule this message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle { assertEquals(listOf("/schedule"), sent) }
    }

    /** Not a golden: the composer's button and the dialog, written under `build/outputs/schedule-into-chat/`. */
    @Test
    fun `render the button and the dialog`() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("Rerun the nightly suite")
        compose.waitForIdle()
        compose.onAllNodes(isRoot())[0].captureRoboImage("build/outputs/schedule-into-chat/composer.png", RECORD)
        compose.onNodeWithContentDescription("Schedule this message").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.last().let { last ->
            compose.onAllNodes(isRoot())[last].captureRoboImage("build/outputs/schedule-into-chat/dialog.png", RECORD)
        }
    }

    /** Not a golden: the dialog on a running chat with "When this run finishes" picked — no repeat row, its own hint. */
    @Test
    fun `render the dialog waiting for the run`() {
        show(canAfterRun = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("Then rerun the nightly suite")
        compose.onNodeWithContentDescription("Schedule this message").performClick()
        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("When this run finishes").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.last().let { last ->
            compose.onAllNodes(isRoot())[last].captureRoboImage("build/outputs/schedule-into-chat/dialog-after-run.png", RECORD)
        }
    }

    private fun show(offer: Boolean = true, canAfterRun: Boolean = false) {
        val pills = DeckFixtures.byName("convo-composer-pills")!!
        val hello = pills.hello!!.let {
            it.copy(capabilities = it.capabilities + MobileProtocol.Capability.COMMANDS + MobileProtocol.Capability.SCHEDULE_REPEAT)
        }
        val page = MobileTranscriptPage(
            key = "conversation", title = "Nightly",
            turns = listOf(
                MobileTurn("t1", "user", "Run the tests", DeckFixtures.NOW - 60_000),
                MobileTurn("t2", "assistant", "All 42 tests pass.", DeckFixtures.NOW - 30_000),
            ),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            // A pinned clock: the live one ticks the When pill forever, and Compose never idles.
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) { AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    page = page,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = null,
                    onDraft = { draft.value = it },
                    onSend = { text, _ -> sent += text },
                    onStop = {},
                    onDismissNotice = {},
                    hello = hello,
                    onLoadCommands = { listOf(MobileCommand("/deploy", "Ship it")) },
                    scheduleIntoChat = ScheduleIntoChatOffer(
                        accountId = null,
                        canRepeat = true,
                        canAfterRun = canAfterRun,
                        onSchedule = { due, repeat, afterRun -> scheduled += due to repeat; afterRuns += afterRun },
                    ).takeIf { offer },
                )
            } }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
        const val HOUR = 60 * 60_000L
    }
}
