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
 * Settings › Machine's "Keep awake while an agent works" is the desk's switch: drawn only once the
 * machine has answered with its value, asked for on every visit, and a tap asks for the opposite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class KeepAwakeInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private var reads = 0

    private val reviewFlips = mutableListOf<Boolean>()

    private val suggestionFlips = mutableListOf<Boolean>()
    private var suggestionReads = 0
    private val restrictedFlips = mutableListOf<Boolean>()
    private var restrictedReads = 0

    private fun show(keepAwake: Boolean?, holdForReview: Boolean? = null, promptSuggestions: Boolean? = null, restrictedMode: Boolean? = null) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = keepAwake, holdForReview = holdForReview, promptSuggestions = promptSuggestions, restrictedMode = restrictedMode),
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
                onRefreshKeepAwake = { reads++ },
                onKeepAwake = { flips += it },
                onHoldForReview = { reviewFlips += it },
                onRefreshPromptSuggestions = { suggestionReads++ },
                onPromptSuggestions = { suggestionFlips += it },
                onRefreshRestrictedMode = { restrictedReads++ },
                onRestrictedMode = { restrictedFlips += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `a switch on asks for off`() {
        show(keepAwake = true)

        compose.onNodeWithText("Keep awake while an agent works").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `a switch off asks for on`() {
        show(keepAwake = false)

        compose.onNodeWithText("Keep awake while an agent works").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        show(keepAwake = null)

        assertEquals(0, count("Keep awake while an agent works"))
    }

    @Test
    fun `the review switch asks for the opposite and leaves keep-awake alone`() {
        show(keepAwake = true, holdForReview = false)

        compose.onNodeWithText("Also while a session waits for my review").assertIsDisplayed().performClick()

        assertEquals(listOf(true), reviewFlips)
        assertEquals(emptyList<Boolean>(), flips)
    }

    @Test
    fun `a machine that predates the review switch draws no row for it`() {
        show(keepAwake = true, holdForReview = null)

        assertEquals(1, count("Keep awake while an agent works"))
        assertEquals(0, count("Also while a session waits for my review"))
    }

    @Test
    fun `opening the tab asks the machine for its value`() {
        show(keepAwake = null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }

    @Test
    fun `the suggestion switch asks for the opposite and leaves keep-awake alone`() {
        show(keepAwake = true, promptSuggestions = false)

        compose.onNodeWithText("Suggest the next prompt").assertIsDisplayed().performClick()

        assertEquals(listOf(true), suggestionFlips)
        assertEquals(emptyList<Boolean>(), flips)
    }

    @Test
    fun `a phone the machine has not answered draws no suggestion switch`() {
        show(keepAwake = true, promptSuggestions = null)

        assertEquals(0, count("Suggest the next prompt"))
    }

    @Test
    fun `opening the tab asks the machine for the suggestion switch too`() {
        show(keepAwake = null)
        compose.waitForIdle()

        assertEquals(1, suggestionReads)
    }

    @Test
    fun `the restricted switch asks for the opposite and leaves the other switches alone`() {
        show(keepAwake = true, promptSuggestions = false, restrictedMode = false)

        compose.onNodeWithText("Restricted mode for Claude chats").assertIsDisplayed().performClick()

        assertEquals(listOf(true), restrictedFlips)
        assertEquals(emptyList<Boolean>(), suggestionFlips)
        assertEquals(emptyList<Boolean>(), flips)
    }

    @Test
    fun `a phone the machine has not answered draws no restricted switch`() {
        show(keepAwake = true, restrictedMode = null)

        assertEquals(0, count("Restricted mode for Claude chats"))
    }

    @Test
    fun `opening the tab asks the machine for the restricted switch too`() {
        show(keepAwake = null)
        compose.waitForIdle()

        assertEquals(1, restrictedReads)
    }
}
