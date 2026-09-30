package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalRenderMarkup
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Machine's "Formatting in messages" is the desk's switch: drawn only once the machine has
 * answered with its value, asked for on every visit, a tap asks for the opposite — and a conversation
 * draws an answer as the plain text the agent wrote when it is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChatMarkupInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private var reads = 0

    private fun showSettings(chatMarkup: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, chatMarkup = chatMarkup),
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
                onChatMarkup = { flips += it },
            )
        }
    }

    private fun showAnswer(renderMarkup: Boolean) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(MobileTurn("t1", "assistant", "It fails at a **day boundary**.", DeckFixtures.NOW)),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                CompositionLocalProvider(LocalRenderMarkup provides renderMarkup) {
                    ConversationScreen(
                        target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                        page = page,
                        loading = false,
                        cached = false,
                        draft = "",
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        stopping = false,
                        onDismissNotice = {},
                        queued = emptyList(),
                        delivering = null,
                    )
                }
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(chatMarkup = true)

        compose.onNodeWithText("Formatting in messages").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(chatMarkup = false)

        compose.onNodeWithText("Formatting in messages").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(chatMarkup = null)

        assertEquals(0, count("Formatting in messages"))
    }

    @Test
    fun `an answer keeps its asterisks when formatting is off`() {
        showAnswer(renderMarkup = false)

        compose.onNodeWithText("It fails at a **day boundary**.").assertIsDisplayed()
    }

    @Test
    fun `an answer is formatted when formatting is on`() {
        showAnswer(renderMarkup = true)

        assertEquals("the markers were drawn as text", 0, count("**day boundary**"))
        assertEquals("the answer was not drawn", 1, count("day boundary"))
    }
}
