package dev.agentdeck.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileContextWarning
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Machine's "Context warning" is the desk's "Warn when a chat context is nearly full": drawn only
 * once the machine has answered with its value, a tap asks for the opposite. A conversation whose page reports
 * 80% or more draws the desk's line above its message box while the switch is on — Compact and Hand off fill
 * the reader's own draft and never send, a draft already there is kept — and the ✕ hides it for the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ContextWarningInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private val drafts = mutableListOf<String>()

    private fun showSettings(contextWarning: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, contextWarning = contextWarning),
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
                onContextWarning = { flips += it },
            )
        }
    }

    private fun showConversation(
        percent: Int?,
        warning: Boolean,
        initialDraft: String = "",
        episodes: MobileContextWarning.Episodes = MobileContextWarning.Episodes(),
    ) {
        val page = requireNotNull(DeckFixtures.byName("convo-idle")?.transcript).copy(running = false, contextPct = percent)
        compose.setContent {
            AgentDeckTheme {
                var draft by remember { mutableStateOf(initialDraft) }
                val target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project")
                ConversationScreen(
                    target, page, false, false, draft, null,
                    { draft = it; drafts += it }, { _, _ -> }, {}, {},
                    contextWarning = warning, contextEpisodes = episodes,
                )
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private val line = MobileContextWarning.line(84)

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(contextWarning = true)

        compose.onNodeWithText("Context warning").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(contextWarning = false)

        compose.onNodeWithText("Context warning").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(contextWarning = null)

        assertEquals(0, count("Context warning"))
    }

    @Test
    fun `a chat past the threshold with the switch on shows the desk's line`() {
        showConversation(percent = 84, warning = true)

        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithText("Compact").assertIsDisplayed()
        compose.onNodeWithText("Hand off").assertIsDisplayed()
    }

    @Test
    fun `an off switch draws no warning at any percentage`() {
        showConversation(percent = 84, warning = false)

        compose.waitForIdle()
        assertEquals(0, count("Context is"))
    }

    @Test
    fun `a chat under the threshold or with no reading draws no warning`() {
        showConversation(percent = 64, warning = true)
        compose.waitForIdle()
        assertEquals("64% is under the line", 0, count("Context is"))
    }

    @Test
    fun `a chat with no measured context draws no warning`() {
        showConversation(percent = null, warning = true)

        compose.waitForIdle()
        assertEquals(0, count("Context is"))
    }

    @Test
    fun `compact puts the slash command in the message box and sends nothing`() {
        showConversation(percent = 84, warning = true)

        compose.onNodeWithText("Compact").performClick()

        assertEquals(listOf(MobileContextWarning.COMPACT_PROMPT), drafts)
    }

    @Test
    fun `hand off puts the desk's handoff request in the message box`() {
        showConversation(percent = 84, warning = true)

        compose.onNodeWithText("Hand off").performClick()

        assertEquals(listOf(MobileContextWarning.HANDOFF_PROMPT), drafts)
    }

    @Test
    fun `a draft already typed is kept and the strip says so`() {
        showConversation(percent = 84, warning = true, initialDraft = "keep me")

        compose.onNodeWithText("Compact").performClick()

        assertEquals("the draft was overwritten", emptyList<String>(), drafts)
        compose.onNodeWithText("Your draft and attachments were kept", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the close button hides the warning for this chat and a return does not bring it back`() {
        val episodes = MobileContextWarning.Episodes()
        showConversation(percent = 84, warning = true, episodes = episodes)

        compose.onNodeWithContentDescription("Hide this warning", substring = true).performClick()

        compose.waitForIdle()
        assertEquals(0, count("Context is"))
        assertEquals("the episode holds the dismissal", null, episodes.observe(DeckFixtures.byName("convo-idle")!!.transcript!!.key, 85, enabled = true))
    }
}
