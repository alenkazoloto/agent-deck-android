package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.SessionSpendSheet
import dev.agentdeck.companion.data.SpendForm
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.SessionSpendDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Chats row's "Spend limits…" is offered only where the machine serves it (a phone holding the
 * permission grant), and the dialog is the desk's form: the defaults switch, the four limits as text
 * fields where blank is off, the handoff prompt and the new-chat switch — Save only for a changed form,
 * the machine's refusal under the fields with the typed text kept.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionSpendInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val forms = mutableListOf<SpendForm>()
    private var saved = 0

    private fun showRows(canSpend: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(listOf(row("a", "Fix the parser")), 0, emptyList(), null, DeckFixtures.NOW),
                        filter = FleetFilter(),
                        sort = FleetSort.RECENT,
                        refreshing = false,
                        snoozed = emptyMap(),
                        openKey = null,
                        onFilter = {},
                        onSort = {},
                        onRefresh = {},
                        onOpen = {},
                        onSnooze = {},
                        onStop = {},
                        canSpend = canSpend,
                        onSpend = { opened += it.key },
                    )
                }
            }
        }
    }

    /** A dialog whose sheet follows the reader's edits, as the flow's does. */
    private fun showDialog(initial: SessionSpendSheet) {
        compose.setContent {
            var sheet by remember { mutableStateOf(initial) }
            AgentDeckTheme(dynamic = false) {
                SessionSpendDialog(sheet, onForm = { forms += it; sheet = sheet.copy(form = it, refused = null) }, onSave = { saved++ }, onDismiss = {})
            }
        }
    }

    private val own = SpendForm(useDefaults = false, soft = "2.5", hard = "10", session = "90", prompt = "Stop and hand off.")

    @Test
    fun `the row sheet offers spend limits only where the machine serves them`() {
        showRows(canSpend = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onAllNodesWithText("Spend limits…").assertCountEquals(0)
    }

    @Test
    fun `the row sheet's Spend limits asks for that chat's limits`() {
        showRows(canSpend = true)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Spend limits…").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), opened) }
    }

    @Test
    fun `a chat with limits of its own shows them in editable fields and offers Save only once changed`() {
        showDialog(SessionSpendSheet("a", "Fix the parser", saved = own, form = own))

        compose.onNodeWithText("Chat spend limits").assertIsDisplayed()
        compose.onNodeWithTag("session-spend-soft").assertIsEnabled().assertTextContains("2.5")
        compose.onNodeWithTag("session-spend-hard").assertTextContains("10")
        compose.onNodeWithTag("session-spend-save").assertIsNotEnabled()
        compose.onNodeWithTag("session-spend-hard").performTextClearance()
        compose.onNodeWithTag("session-spend-hard").performTextInput("12")
        compose.runOnIdle { assertEquals("12", forms.last().hard) }
    }

    @Test
    fun `with the defaults switch on the fields show the inherited values and cannot be edited`() {
        val defaults = own.copy(useDefaults = true)
        showDialog(SessionSpendSheet("a", "Fix the parser", saved = defaults, form = defaults))

        compose.onNodeWithTag("session-spend-soft").assertIsNotEnabled().assertTextContains("2.5")
        compose.onNodeWithTag("session-spend-prompt").assertIsNotEnabled()
        compose.onNodeWithTag("session-spend-defaults").performClick()
        compose.runOnIdle { assertEquals(false, forms.last().useDefaults) }
    }

    @Test
    fun `the defaults sheet names whom the values are for, has no defaults switch and edits its fields`() {
        val held = own.copy(useDefaults = false)
        showDialog(SessionSpendSheet("", "", saved = held, form = held, defaults = true, revision = 4))

        compose.onNodeWithText("Spend defaults").assertIsDisplayed()
        compose.onNodeWithText("For every chat without limits of its own").assertIsDisplayed()
        compose.onAllNodesWithText("Chat spend limits").assertCountEquals(0)
        compose.onAllNodesWithText("Use global defaults").assertCountEquals(0)
        compose.onNodeWithTag("session-spend-soft").assertIsEnabled().assertTextContains("2.5")
        compose.onNodeWithTag("session-spend-hard").performTextClearance()
        compose.onNodeWithTag("session-spend-hard").performTextInput("12")
        compose.runOnIdle { assertEquals("12", forms.last().hard) }
    }

    @Test
    fun `Save is offered for a changed form and sends`() {
        showDialog(SessionSpendSheet("a", "Fix the parser", saved = own, form = own.copy(hard = "12")))

        compose.onNodeWithTag("session-spend-save").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test
    fun `the machine's refusal shows under the fields and the typed text is kept`() {
        showDialog(
            SessionSpendSheet(
                "a", "Fix the parser", saved = own, form = own.copy(soft = "12"),
                refused = "Soft limit must be lower than hard limit.",
            ),
        )

        compose.onNodeWithText("Soft limit must be lower than hard limit.").assertIsDisplayed()
        compose.onNodeWithTag("session-spend-soft").assertTextContains("12")
        compose.onNodeWithTag("session-spend-save").assertIsEnabled()
    }

    @Test
    fun `an unread sheet says it is being read and offers no fields`() {
        showDialog(SessionSpendSheet("a", "Fix the parser", busy = true))
        compose.onNodeWithText("Reading this conversation…").assertIsDisplayed()
        compose.onAllNodesWithText("Soft limit (USD)").assertCountEquals(0)
        compose.onNodeWithTag("session-spend-save").assertIsNotEnabled()
    }

    @Test
    fun `a refusal to read shows the machine's own sentence and no form`() {
        showDialog(SessionSpendSheet("a", "Fix the parser", error = "This machine does not let this phone decide tool permissions, change working directories or spend limits."))
        compose.onNodeWithText("This machine does not let this phone decide tool permissions, change working directories or spend limits.").assertIsDisplayed()
        compose.onAllNodesWithText("Soft limit (USD)").assertCountEquals(0)
    }

    private fun row(key: String, title: String) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
