package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileExtension
import com.github.claudeagents.core.mobile.MobileExtensions
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.extensionAdds
import dev.agentdeck.companion.ui.extensionIdentity
import dev.agentdeck.companion.ui.extensionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Resources › "Extensions" lists the extensions installed on the machine, read-only:
 * offered only by a machine that advertises `extensions-list`, read again each time it opens, every
 * state a word, and no control for what runs on the desk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtensionsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var loads = 0

    private val tabs = MobileExtension(
        id = "acme.tabs", name = "Acme Tabs", version = "1.4.0", vendor = "Acme",
        contributions = listOf("Run events", "Tool-window tab"),
    )
    private val sink = MobileExtension(
        id = "beta.sink", name = "Beta Sink", version = "0.2", enabled = false,
        contributions = listOf("Usage export"),
    )
    private val flaky = MobileExtension(
        id = "gamma", name = "Gamma", state = "Paused after repeated errors this session",
    )
    private val installed = MobileExtensions(listOf(tabs, sink, flaky))

    private fun show(capable: Boolean = true, answer: suspend () -> MobileExtensions?) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) {
            base.copy(hello = base.hello!!.copy(capabilities = base.hello!!.capabilities + MobileProtocol.Capability.EXTENSIONS))
        } else {
            base
        }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    state = state, onSettings = {}, onSwitchMachine = {}, onAddMachine = {}, onUnpair = {},
                    onRefreshHello = {}, onRefreshPush = {}, onChoosePush = {}, onCheckUpdate = {},
                    onDownloadUpdate = {}, onInstallUpdate = {}, onReleasePage = {},
                    onLoadExtensions = { loads++; answer() },
                )
            }
        }
    }

    private fun open() {
        compose.onNodeWithText("Extensions").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a machine without the capability has no row`() {
        show(capable = false) { installed }

        assertEquals(0, compose.onAllNodesWithText("Extensions").fetchSemanticsNodes().size)
    }

    @Test
    fun `the sheet names each extension, its state and what it adds on the desk`() {
        show { installed }
        open()

        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p38/extensions-sheet.png", RECORD)
        compose.onNodeWithText("Acme Tabs").assertIsDisplayed()
        compose.onNodeWithText("1.4.0 · Acme").assertIsDisplayed()
        compose.onNodeWithText("Adds on the desk: Run events, Tool-window tab").assertIsDisplayed()
        compose.onNodeWithText("Off on the desk").assertIsDisplayed()
        compose.onNodeWithText("On · Paused after repeated errors this session").assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun `a machine with none installed says so in the desk's words`() {
        show { MobileExtensions() }
        open()

        compose.onNodeWithText("No extensions installed yet.").assertIsDisplayed()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }
        open()

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }

    @Test
    fun `identity, status and additions are words`() {
        assertEquals("1.4.0 · Acme", extensionIdentity(tabs))
        assertEquals("0.2", extensionIdentity(sink))
        assertEquals("gamma", extensionIdentity(flaky))
        assertEquals("On", extensionStatus(tabs))
        assertEquals("Off on the desk", extensionStatus(sink))
        assertEquals("Adds on the desk: Usage export", extensionAdds(sink))
        assertNull(extensionAdds(flaky))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
