package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.ReadingAnchor
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
 * Settings › Machine's "Scroll to the newest message when I send" is the desk's switch: drawn only once
 * the machine has answered with its value, a tap asks for the opposite — and a send from a conversation
 * the reader had scrolled up in takes it to the end when the switch is on, and leaves it where it was when off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class JumpOnSendInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private val sent = mutableListOf<String>()

    private fun showSettings(jumpOnSend: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, jumpOnSend = jumpOnSend),
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
                onJumpOnSend = { flips += it },
            )
        }
    }

    private fun showScrolledUpConversation(jump: Boolean) {
        val page = requireNotNull(DeckFixtures.byName("convo-idle")?.transcript).copy(
            running = false, hasMore = false, turns = (10..40).map(::turn),
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    page, false, false, "Go on", null, {}, { text, _ -> sent += text }, {}, {},
                    readingAnchor = ReadingAnchor("turn-15", 5, 24, false),
                    jumpToEndOnSend = jump,
                )
            }
        }
        compose.onNodeWithText("Passage 15", substring = true).assertIsDisplayed()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(jumpOnSend = true)

        compose.onNodeWithText("Scroll to the newest message when I send").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(jumpOnSend = false)

        compose.onNodeWithText("Scroll to the newest message when I send").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(jumpOnSend = null)

        assertEquals(0, count("Scroll to the newest message when I send"))
    }

    @Test
    fun `a send takes a scrolled-up reader to the newest message when the switch is on`() {
        showScrolledUpConversation(jump = true)

        compose.onNodeWithContentDescription("Send").performClick()

        compose.waitUntil(5_000) { count("Passage 40") == 1 }
        assertEquals(listOf("Go on"), sent)
    }

    @Test
    fun `a send leaves a scrolled-up reader where they are when the switch is off`() {
        showScrolledUpConversation(jump = false)

        compose.onNodeWithContentDescription("Send").performClick()

        compose.waitForIdle()
        assertEquals(listOf("Go on"), sent)
        compose.onNodeWithText("Passage 15", substring = true).assertIsDisplayed()
        assertEquals("the reader was moved", 0, count("Passage 40"))
    }

    private fun turn(index: Int) = MobileTurn("turn-$index", "assistant",
        "Passage $index\n\n" + "Synthetic reading text. ".repeat(10), DeckFixtures.NOW)
}
