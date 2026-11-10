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
 * Settings › Machine's "Quick replies" is the desk's "Show quick replies": drawn only once the machine has
 * answered with its value, a tap asks for the opposite — and the message box offers its Summarize / Test
 * changes / Review / Continue chips while it is on (or the machine predates it) and leaves them out when off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class QuickRepliesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()

    private fun showSettings(quickReplies: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, quickReplies = quickReplies),
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
                onQuickReplies = { flips += it },
            )
        }
    }

    private fun showConversation(replies: Boolean?) {
        val page = requireNotNull(DeckFixtures.byName("convo-idle")?.transcript).copy(running = false)
        compose.setContent {
            AgentDeckTheme {
                val target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project")
                if (replies == null) ConversationScreen(target, page, false, false, "", null, {}, { _, _ -> }, {}, {})
                else ConversationScreen(target, page, false, false, "", null, {}, { _, _ -> }, {}, {}, quickReplies = replies)
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(quickReplies = true)

        compose.onNodeWithText("Quick replies").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(quickReplies = false)

        compose.onNodeWithText("Quick replies").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(quickReplies = null)

        assertEquals(0, count("Quick replies"))
    }

    @Test
    fun `an on switch keeps the chips in the message box`() {
        showConversation(replies = true)

        for (label in listOf("Summarize", "Test changes", "Review", "Continue")) compose.onNodeWithText(label).assertIsDisplayed()
    }

    @Test
    fun `a conversation opened without the machine's answer keeps its chips as before`() {
        showConversation(replies = null)

        compose.onNodeWithText("Summarize").assertIsDisplayed()
    }

    @Test
    fun `an off switch leaves the chips out`() {
        showConversation(replies = false)

        compose.waitForIdle()
        for (label in listOf("Summarize", "Test changes", "Continue")) assertEquals("$label is still offered", 0, count(label))
    }
}
