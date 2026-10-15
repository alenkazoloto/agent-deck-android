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
 * Settings › Machine's "Continue an interrupted step" is the desk's switch: drawn only once the machine
 * has answered with its value, asked for on every visit, and a tap asks for the opposite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ContinueInterruptedInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private var reads = 0
    private val keepAwakeFlips = mutableListOf<Boolean>()

    private fun show(continueInterrupted: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, continueInterrupted = continueInterrupted),
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
                onRefreshContinueInterrupted = { reads++ },
                onContinueInterrupted = { flips += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite and leaves keep awake alone`() {
        show(continueInterrupted = true)

        compose.onNodeWithText("Continue an interrupted step").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
        assertEquals(emptyList<Boolean>(), keepAwakeFlips)
    }

    @Test
    fun `an off switch asks for on`() {
        show(continueInterrupted = false)

        compose.onNodeWithText("Continue an interrupted step").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        show(continueInterrupted = null)

        assertEquals(0, count("Continue an interrupted step"))
    }

    @Test
    fun `opening the tab asks the machine for the value`() {
        show(continueInterrupted = null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }
}
