package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileProtocol
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
 * Settings is four tabs, and the log rows live under About: sending is offered only to a machine
 * that advertises `phone-logs`, sharing always, and the result of the last send stays on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsTabsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var sent = 0
    private var shared = 0

    private fun show(state: DeckState) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = state,
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
                onSendLogs = { sent++ },
                onShareLogs = { shared++ },
            )
        }
    }

    private fun withLogs(state: DeckState) =
        state.copy(hello = state.hello!!.copy(capabilities = state.hello!!.capabilities + MobileProtocol.Capability.PHONE_LOGS))

    private fun goTo(tab: String) = compose.onNodeWithText(tab).performClick().also { compose.waitForIdle() }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `each section shows under its own tab only`() {
        show(DeckFixtures.byName("settings")!!)
        compose.onNodeWithText("Pair another machine").assertIsDisplayed()
        assertEquals("Reading belongs to the App tab", 0, count("Reading"))
        goTo("App")
        compose.onNodeWithText("Reading").assertIsDisplayed()
        assertEquals("Machine rows must leave with their tab", 0, count("Pair another machine"))
        goTo("Alerts")
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        goTo("About")
        compose.onNodeWithText("Diagnostics").assertIsDisplayed()
        compose.onNodeWithText("Logs").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a machine that cannot take a log is never offered one, but the share sheet still is`() {
        show(DeckFixtures.byName("settings")!!)
        goTo("About")
        assertEquals(0, count("Send log to this machine"))
        compose.onNodeWithText("Share log…").performScrollTo().performClick()
        assertEquals(1, shared)
        assertEquals(0, sent)
    }

    @Test
    fun `a machine that advertises phone-logs is offered the send`() {
        show(withLogs(DeckFixtures.byName("settings")!!))
        goTo("About")
        compose.onNodeWithText("Send log to this machine").performScrollTo().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun `the outcome of the last send stays on the page`() {
        show(withLogs(DeckFixtures.byName("settings")!!).copy(logSend = LogSend.Failed("Could not reach this machine. The IDE has to be running.")))
        goTo("About")
        compose.onNodeWithText("Log not sent").performScrollTo().assertIsDisplayed()
    }
}
