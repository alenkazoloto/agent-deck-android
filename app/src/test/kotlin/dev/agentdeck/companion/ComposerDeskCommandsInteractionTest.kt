package dev.agentdeck.companion

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
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobileDeskCommands
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P15 re-audit: the desk composer's own commands (`/model`, `/stop`, `/copy`…) typed on the phone
 * run the phone's control instead of reaching the agent as prose, against the real
 * `ConversationScreen`. Before, `/v1/commands` left them out and a sent `/stop` was a message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerDeskCommandsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val sent = mutableListOf<String>()
    private var stops = 0
    private val notices = mutableListOf<String>()
    private val navigations = mutableListOf<MobileDeskCommands.Action>()
    private var catalogue: List<MobileCommand>? = listOf(MobileCommand("/deploy", "Ship it"))
    private var pendingCatalogue: kotlinx.coroutines.CompletableDeferred<List<MobileCommand>?>? = null

    @Test
    fun `the popup offers a desk command and picking it opens the phone's control`() {
        show(running = false)

        compose.onNode(hasSetTextAction()).performTextReplacement("/mo")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Choose the model in Run settings").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Choose the model in Run settings").performClick()

        compose.onNodeWithTag("run-settings-sheet").assertExists()
        compose.runOnIdle {
            assertEquals("", draft.value)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `a sent stop stops the running turn instead of messaging the agent`() {
        show(running = true)

        type("/stop")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(1, stops)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `stop with nothing running says so and keeps the line`() {
        show(running = false)

        type("/stop")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(0, stops)
            assertTrue(sent.isEmpty())
            assertEquals("/stop", draft.value)
            assertEquals(listOf("No running turn to stop."), notices)
        }
    }

    @Test
    fun `copy puts the last response on the clipboard`() {
        show(running = false)

        type("/copy")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("All 42 tests pass.", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            assertEquals(listOf("Copied the last response."), notices)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `new chat leaves through the view model`() {
        show(running = false)

        type("/clear")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle { assertEquals(listOf(MobileDeskCommands.Action.NEW_CHAT), navigations) }
    }

    @Test
    fun `words after the command, a user's own command and Codex's diff are still messages`() {
        catalogue = listOf(MobileCommand("/stop", "Stop the dev server"))
        show(running = true)
        type("/stop")
        compose.onNodeWithContentDescription("Send").performClick()
        type("/model opus")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(0, stops)
            assertEquals(listOf("/stop", "/model opus"), sent)
        }
    }

    @Test
    fun `a send before the catalogue arrives goes to the agent`() {
        // The user's own `/stop` may be in the list still on its way; the phone cannot know.
        val gate = kotlinx.coroutines.CompletableDeferred<List<MobileCommand>?>()
        pendingCatalogue = gate
        show(running = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("/stop")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(0, stops)
            assertEquals(listOf("/stop"), sent)
        }
        gate.complete(null)
    }

    @Test
    fun `a Codex chat sends diff to Codex`() {
        show(running = false, vendor = AgentVendor.CODEX)

        type("/diff")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle { assertEquals(listOf("/diff"), sent) }
    }

    @Test
    fun `a bare personality in a Codex chat opens Codex settings instead of spending a turn`() {
        show(running = false, vendor = AgentVendor.CODEX, extra = MobileProtocol.Capability.SESSION_CODEX)

        type("/personality")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.runOnIdle {
            assertEquals(listOf(MobileDeskCommands.Action.CODEX_SETTINGS), navigations)
            assertTrue("sent as prose: $sent", sent.isEmpty())
            assertEquals("", draft.value)
        }
    }

    @Test
    fun `personality is still a message on a Claude chat`() {
        show(running = false, vendor = AgentVendor.CLAUDE, extra = MobileProtocol.Capability.SESSION_CODEX)
        type("/personality")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { assertEquals(listOf("/personality"), sent) }
    }

    @Test
    fun `personality without the machine's Codex settings goes to Codex as before`() {
        show(running = false, vendor = AgentVendor.CODEX)
        type("/personality")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals(listOf("/personality"), sent)
        }
    }

    /** Not a golden: the popup with a desk row, written under `build/outputs/p15/`. */
    @Test
    fun `render the desk command rows`() {
        show(running = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("/s")
        compose.waitForIdle()
        compose.onAllNodes(isRoot())[0].captureRoboImage("build/outputs/p15/desk-command-rows.png", RECORD)
    }

    /** Types and waits for the one catalogue read, so Send can ask it whether the name is the user's. */
    private fun type(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun show(running: Boolean, vendor: AgentVendor = AgentVendor.CLAUDE, extra: String? = null) {
        val pills = DeckFixtures.byName("convo-composer-pills")!!
        val hello = pills.hello!!.let { it.copy(capabilities = it.capabilities + MobileProtocol.Capability.COMMANDS + listOfNotNull(extra)) }
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(
                MobileTurn("t1", "user", "Run the tests", DeckFixtures.NOW - 60_000),
                MobileTurn("t2", "assistant", "All 42 tests pass.", DeckFixtures.NOW - 30_000),
            ),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = running, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, vendor, "/project"),
                    page = page,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = null,
                    onDraft = { draft.value = it },
                    onSend = { text, _ -> sent += text },
                    onStop = { stops++ },
                    onDismissNotice = {},
                    hello = hello,
                    onLoadCommands = { pendingCatalogue?.await() ?: catalogue },
                    onDeskNavigation = { navigations += it },
                    onCommandNotice = { notices += it },
                )
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
