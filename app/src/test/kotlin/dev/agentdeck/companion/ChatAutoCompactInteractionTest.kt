package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Machine's "Compact chats idle for" is the desk's number, typed in place: drawn only once a
 * phone holding the owner's grant has had the machine's answer, asked for on every visit, and sent on
 * Done only when the machine would accept it — anything else snaps back to what the machine has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChatAutoCompactInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val writes = mutableListOf<Int>()
    private var reads = 0

    private fun show(days: Int?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(chatAutoCompactDays = days),
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
                onKeepAwake = {},
                onRefreshChatAutoCompact = { reads++ },
                onChatAutoCompact = { writes += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun type(text: String) {
        compose.onNodeWithTag("chat-auto-compact-days").performTextClearance()
        compose.onNodeWithTag("chat-auto-compact-days").performTextInput(text)
        compose.onNodeWithTag("chat-auto-compact-days").performImeAction()
    }

    @Test
    fun `the row shows the desk's number and Done sends a new one`() {
        show(days = 14)

        compose.onNodeWithText("Compact chats idle for").assertIsDisplayed()
        compose.onNodeWithTag("chat-auto-compact-days").assertTextContains("14")

        type("30")

        assertEquals(listOf(30), writes)
    }

    @Test
    fun `zero is a number the machine takes and its subtitle says off`() {
        show(days = 14)

        type("0")

        assertEquals(listOf(0), writes)
    }

    @Test
    fun `the same number, an empty field and a number past the desk's range send nothing`() {
        show(days = 14)

        type("14")
        type("")
        type("366")
        type("999")

        assertEquals(emptyList<Int>(), writes)
        compose.onNodeWithTag("chat-auto-compact-days").assertTextContains("14")
    }

    @Test
    fun `a phone the machine has not answered, for want of the grant, draws no row`() {
        show(days = null)

        assertEquals(0, count("Compact chats idle for"))
    }

    @Test
    fun `opening the tab asks the machine for the number`() {
        show(days = null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }
}
