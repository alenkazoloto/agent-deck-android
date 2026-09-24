package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileNewChatDefaults
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
 * Settings › Machine's "Start new chats in" is the desk's pin: drawn only once the machine has
 * answered with it, asked for on every visit, and a pick names the word the machine offered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NewChatModeInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val picks = mutableListOf<String>()
    private var reads = 0

    private val defaults = MobileNewChatDefaults(
        MobileNewChatDefaults.LAST_USED,
        listOf(
            MobileModelOption(MobileNewChatDefaults.LAST_USED, "Last used"),
            MobileModelOption("plan", "Plan"),
        ),
    )

    private fun show(newChatDefaults: MobileNewChatDefaults?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(newChatDefaults = newChatDefaults),
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
                onRefreshNewChatDefaults = { reads++ },
                onNewChatMode = { picks += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `the row names the pin and a pick sends the machine's word`() {
        show(defaults)

        compose.onNodeWithText("Start new chats in").assertIsDisplayed()
        compose.onNodeWithText("Last used").performClick()
        compose.onNodeWithText("Plan").performClick()

        assertEquals(listOf("plan"), picks)
    }

    @Test
    fun `a machine that has not answered draws no row`() {
        show(null)

        assertEquals(0, count("Start new chats in"))
    }

    @Test
    fun `opening the tab asks the machine for its pin`() {
        show(null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }
}
