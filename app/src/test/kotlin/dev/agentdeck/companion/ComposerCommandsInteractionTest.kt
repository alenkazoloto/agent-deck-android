package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
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
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * P15: the composer's `/` and `$` popup and its "Earlier prompts" recall, against the real
 * `ConversationScreen` — each asked with and without its capability, since a test that only saw
 * the advertising machine would pass identically with the gate deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerCommandsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private var loads = 0
    private var promptLoads = 0
    private var prompts: List<String>? = listOf("run the failing test again", "summarize the diff")
    private var answer: List<MobileCommand>? = listOf(
        MobileCommand("/compact", "Summarize the conversation to free context", argumentHint = "[what to focus on]"),
        MobileCommand("/deploy", "Ship it", argumentHint = "<env>"),
        MobileCommand("\$review", "Review a diff", skill = true),
    )

    @Test
    fun `a slash at the start lists the chat's commands and accepting one writes it with a space`() {
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.COMMANDS))

        compose.onNode(hasSetTextAction()).performTextReplacement("/dep")
        compose.waitUntil(5_000) { loads > 0 }
        compose.onNodeWithText("Ship it").assertExists()
        compose.onNodeWithText("/compact").assertDoesNotExist()

        compose.onNodeWithText("/deploy").performClick()
        compose.runOnIdle { assertEquals("/deploy ", draft.value) }

        // Filtered locally from the one catalogue read: typing on costs no second round trip.
        compose.onNode(hasSetTextAction()).performTextReplacement("/co")
        compose.onNodeWithText("/compact").assertExists()
        assertEquals(1, loads)
    }

    @Test
    fun `a slash mid-sentence is a path and opens nothing`() {
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.COMMANDS))

        compose.onNode(hasSetTextAction()).performTextReplacement("look at /src")
        compose.waitForIdle()

        assertEquals(0, loads)
    }

    @Test
    fun `a Codex chat completes dollar skills, not slash rows`() {
        show(AgentVendor.CODEX, listOf(MobileProtocol.Capability.COMMANDS))

        compose.onNode(hasSetTextAction()).performTextReplacement("please \$re")
        compose.waitUntil(5_000) { loads > 0 }
        compose.onNodeWithText("\$review").performClick()
        compose.runOnIdle { assertEquals("please \$review ", draft.value) }

        compose.onNode(hasSetTextAction()).performTextReplacement("/dep")
        compose.waitForIdle()
        compose.onNodeWithText("/deploy").assertDoesNotExist()
    }

    @Test
    fun `a catalogue that could not be read says so instead of claiming there are no commands`() {
        answer = null
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.COMMANDS))

        compose.onNode(hasSetTextAction()).performTextReplacement("/")
        compose.waitUntil(5_000) { loads > 0 }

        compose.onNodeWithText("Commands could not be read", substring = true).assertExists()
        compose.onNodeWithText("No command or skill matches.").assertDoesNotExist()
    }

    @Test
    fun `a machine that does not serve commands never opens the popup`() {
        show(AgentVendor.CLAUDE, capabilities = emptyList())

        compose.onNode(hasSetTextAction()).performTextReplacement("/dep")
        compose.waitForIdle()

        assertTrue("the popup read a machine that never advertised commands", loads == 0)
        compose.onNodeWithText("Ship it").assertDoesNotExist()
    }

    @Test
    fun `earlier prompts put a past prompt back into the empty composer`() {
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.PROMPT_HISTORY))

        compose.onNodeWithText("Earlier prompts").performClick()
        compose.waitUntil(5_000) { promptLoads > 0 }
        compose.onNodeWithText("Earlier prompts in this project").assertExists()
        compose.onNodeWithText("run the failing test again").performClick()

        compose.runOnIdle { assertEquals("run the failing test again", draft.value) }
    }

    @Test
    fun `an empty history says so rather than showing a blank sheet`() {
        prompts = emptyList()
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.PROMPT_HISTORY))

        compose.onNodeWithText("Earlier prompts").performClick()
        compose.waitUntil(5_000) { promptLoads > 0 }

        compose.onNodeWithText("No earlier prompts yet.").assertExists()
    }

    @Test
    fun `a machine that does not serve prompt history shows no chip`() {
        show(AgentVendor.CLAUDE, capabilities = listOf(MobileProtocol.Capability.COMMANDS))

        compose.onNodeWithText("Earlier prompts").assertDoesNotExist()
    }

    /** Not a golden: the rendered popup and sheet as evidence, written under `build/outputs/p15/`. */
    @Test
    fun `render the command popup`() {
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.COMMANDS, MobileProtocol.Capability.PROMPT_HISTORY))
        compose.onNode(hasSetTextAction()).performTextReplacement("/")
        compose.waitUntil(5_000) { loads > 0 }
        compose.onRoot().captureRoboImage("build/outputs/p15/command-popup.png", RECORD)
    }

    @Test
    fun `render the earlier prompts chip`() {
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.COMMANDS, MobileProtocol.Capability.PROMPT_HISTORY))
        compose.onRoot().captureRoboImage("build/outputs/p15/earlier-prompts-chip.png", RECORD)
    }

    @Test
    fun `render the earlier prompts sheet`() {
        show(AgentVendor.CLAUDE, listOf(MobileProtocol.Capability.COMMANDS, MobileProtocol.Capability.PROMPT_HISTORY))
        compose.onNodeWithText("Earlier prompts").performClick()
        compose.waitUntil(5_000) { promptLoads > 0 }
        compose.waitForIdle()
        // The sheet is its own window, so its root is the second one.
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p15/earlier-prompts-sheet.png", RECORD)
    }

    private fun show(vendor: AgentVendor, capabilities: List<String>) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests", turns = emptyList(), hasMore = false,
            costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
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
                    onSend = { _, _ -> },
                    onStop = {},
                    onDismissNotice = {},
                    hello = MobileHello(
                        protocolVersion = MobileProtocol.VERSION,
                        machineName = "desk",
                        ideName = "IDEA",
                        pluginVersion = "1.0",
                        capabilities = capabilities,
                    ),
                    onLoadCommands = {
                        loads++
                        answer
                    },
                    onLoadPrompts = {
                        promptLoads++
                        prompts
                    },
                )
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
