package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileSkillSuggestAnswer
import com.github.claudeagents.core.mobile.MobileSkillSuggestions
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Machine's "Suggest matching skills" is the desk's switch, and a Claude chat's message box offers the
 * skill the machine scored the draft against: drawn only once the machine has answered, one Insert chip that types
 * `/name ` before the draft and runs nothing, ✕ leaving that skill out of the chat, and no round trip at all for
 * a draft the desk's scorer would not read (too short, already a command), an ACP or Codex chat, or a switch that is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkillSuggestionInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private val draft = mutableStateOf("")
    private val asked = mutableListOf<Pair<String, List<String>>>()
    private var answer: MobileSkillSuggestAnswer? = MobileSkillSuggestAnswer("deploy-preview", "/deploy-preview", "Deploy the preview build")

    private fun showSettings(on: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, skillSuggestions = on),
                onSettings = {},
                onSwitchMachine = {},
                onAddMachine = {},
                onUnpair = {},
                onRefreshHello = {},
                onRefreshPush = {},
                onChoosePush = {},
                onCheckUpdate = {},
                onDownloadUpdate = {},
                onInstallUpdate = {},
                onReleasePage = {},
                onSkillSuggestions = { flips += it },
            )
        }
    }

    private fun showChat(
        vendor: AgentVendor = AgentVendor.CLAUDE,
        key: String = "conversation",
        on: Boolean = true,
        capability: Boolean = true,
    ) {
        val pills = DeckFixtures.byName("convo-composer-pills")!!
        val hello = pills.hello!!.let {
            it.copy(capabilities = it.capabilities + listOfNotNull(MobileSkillSuggestions.CAPABILITY.takeIf { capability }))
        }
        val page = MobileTranscriptPage(
            key = key, title = "Fix the tests",
            turns = listOf(
                MobileTurn("t1", "user", "Run the tests", DeckFixtures.NOW - 60_000),
                MobileTurn("t2", "assistant", "All 42 tests pass.", DeckFixtures.NOW - 30_000),
            ),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
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
                    hello = hello,
                    skillSuggestions = on,
                    onSuggestSkill = { text, dismissed ->
                        asked += text to dismissed
                        answer?.takeIf { it.name !in dismissed }
                    },
                )
            }
        }
    }

    private fun type(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun awaitChip() = compose.waitUntil(5_000) { count("Suggested: /deploy-preview") > 0 }

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(on = true)

        compose.onNodeWithText("Suggest matching skills").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(on = false)

        compose.onNodeWithText("Suggest matching skills").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(on = null)

        assertEquals(0, count("Suggest matching skills"))
    }

    @Test
    fun `a matching draft shows the chip and Insert types the command before it without sending`() {
        showChat()

        type("please deploy the preview build for this branch")
        awaitChip()
        compose.onNodeWithContentDescription("Insert /deploy-preview before your message").performClick()
        compose.waitForIdle()

        assertEquals("/deploy-preview please deploy the preview build for this branch", draft.value)
        assertEquals("a command-led line is not scored again", 0, count("Suggested:"))
        assertEquals(listOf("please deploy the preview build for this branch" to emptyList<String>()), asked)
    }

    @Test
    fun `the cross leaves the skill out of this chat and the machine is told`() {
        showChat()
        type("please deploy the preview build for this branch")
        awaitChip()

        compose.onNodeWithContentDescription("Don't suggest /deploy-preview in this chat", substring = true).performClick()
        compose.waitUntil(5_000) { asked.last().second == listOf("deploy-preview") }
        type("please deploy the preview build for this other branch")
        compose.waitUntil(5_000) { asked.last().first.endsWith("other branch") }

        compose.runOnIdle {
            assertEquals("the machine is told which skill was left out", listOf("deploy-preview"), asked.last().second)
            assertEquals("the cross clears the chip, for the next draft too", 0, count("Suggested:"))
        }
    }

    @Test
    fun `a draft under the desk's word floor or already a command asks nothing`() {
        showChat()

        type("deploy preview")
        type("/deploy-preview the preview build now")
        type("")

        compose.waitForIdle()
        assertTrue("asked for a draft the scorer would ignore: $asked", asked.isEmpty())
        assertEquals(0, count("Suggested:"))
    }

    private fun assertNeverAsks(on: Boolean = true, capability: Boolean = true, vendor: AgentVendor = AgentVendor.CLAUDE, key: String = "conversation") {
        showChat(vendor, key, on, capability)

        type("please deploy the preview build for this branch")
        compose.waitForIdle()

        assertTrue("asked with on=$on capability=$capability $vendor $key: $asked", asked.isEmpty())
        assertEquals(0, count("Suggested:"))
    }

    @Test
    fun `a switch that is off asks nothing`() = assertNeverAsks(on = false)

    @Test
    fun `a machine without the capability asks nothing`() = assertNeverAsks(capability = false)

    @Test
    fun `a Codex chat asks nothing`() = assertNeverAsks(vendor = AgentVendor.CODEX)

    @Test
    fun `an ACP chat asks nothing`() = assertNeverAsks(key = "acp:agent:session")

    @Test
    fun `render the chip above the message box`() {
        showChat()

        type("please deploy the preview build for this branch")
        awaitChip()
        compose.waitForIdle()

        compose.onAllNodes(isRoot())[0].captureRoboImage("build/outputs/skill-suggestion/chip.png", RECORD)
    }

    @Test
    fun `no match draws no chip`() {
        answer = MobileSkillSuggestAnswer()
        showChat()

        type("please deploy the preview build for this branch")
        compose.waitUntil(5_000) { asked.isNotEmpty() }
        compose.waitForIdle()

        assertEquals(0, count("Suggested:"))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
