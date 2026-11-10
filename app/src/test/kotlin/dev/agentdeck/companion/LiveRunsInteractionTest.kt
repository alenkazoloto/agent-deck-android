package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileLiveRuns
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
 * Settings › Machine's "Running elsewhere" is the desk's "Show sessions running elsewhere": drawn only once the
 * machine has answered with its value, a tap asks for the opposite — and an open conversation names the other
 * running chats above its message box, three of them and "+N more", each opening on a tap, and names nothing when
 * the switch is off or unread. The open chat, a waiting chat and a finished one are never in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LiveRunsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private val opened = mutableListOf<String>()

    private fun showSettings(on: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, liveRuns = on),
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
                onLiveRuns = { flips += it },
            )
        }
    }

    private fun showConversation(fixture: String, elsewhere: Boolean = true) {
        val state = DeckFixtures.byName(fixture)!!
        val page = requireNotNull(state.transcript)
        val runs: List<MobileFleetRow> = if (elsewhere && state.liveRuns == true) MobileLiveRuns.elsewhere(state.snapshot!!.rows, page.key) else emptyList()
        compose.setContent {
            AgentDeckTheme {
                val target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project")
                ConversationScreen(target, page, false, false, "", null, {}, { _, _ -> }, {}, {}, runningElsewhere = runs, onOpenRun = { opened += it.key })
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(on = true)

        compose.onNodeWithText("Running elsewhere").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(on = false)

        compose.onNodeWithText("Running elsewhere").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(on = null)

        assertEquals(0, count("Running elsewhere"))
    }

    @Test
    fun `an on switch names three running chats and folds the rest`() {
        showConversation("convo-live-runs")

        compose.onNodeWithText("● Running elsewhere:").assertIsDisplayed()
        compose.onNodeWithText("Migrate the settings store").assertIsDisplayed()
        compose.onNodeWithText("Review the usage export").assertIsDisplayed()
        compose.onNodeWithText("Draft release notes").assertIsDisplayed()
        compose.onNodeWithText("+1 more").assertIsDisplayed()
        assertEquals("a fourth name would outgrow the bar", 0, count("Trim the golden fixtures"))
        assertEquals("a waiting chat is not running", 0, count("Pick a log format"))
        assertEquals("a finished chat is not running", 0, count("Rename the probe"))
    }

    @Test
    fun `a name opens that chat`() {
        showConversation("convo-live-runs")

        compose.onNodeWithText("Review the usage export").performClick()

        assertEquals(listOf("run-3"), opened)
    }

    @Test
    fun `an off switch names nothing`() {
        showConversation("convo-live-runs-off")
        compose.waitForIdle()

        assertEquals(0, count("Running elsewhere"))
    }
}
