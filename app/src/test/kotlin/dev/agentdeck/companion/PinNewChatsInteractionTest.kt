package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Settings › Machine's "Pin new chats" is the desk's switch: drawn only once the machine
 * has answered with its value, asked for on every visit, and a tap asks for the opposite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PinNewChatsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private var reads = 0
    private val keepAwakeFlips = mutableListOf<Boolean>()

    private fun show(pinNewChats: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, pinNewChats = pinNewChats),
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
                onKeepAwake = { keepAwakeFlips += it },
                onRefreshPinNewChats = { reads++ },
                onPinNewChats = { flips += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite and leaves keep awake alone`() {
        show(pinNewChats = true)

        compose.onNodeWithText("Pin new chats").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
        assertEquals(emptyList<Boolean>(), keepAwakeFlips)
    }

    @Test
    fun `an off switch asks for on`() {
        show(pinNewChats = false)

        compose.onNodeWithText("Pin new chats").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        show(pinNewChats = null)

        assertEquals(0, count("Pin new chats"))
    }

    @Test
    fun `opening the tab asks the machine for the value`() {
        show(pinNewChats = null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }
}
