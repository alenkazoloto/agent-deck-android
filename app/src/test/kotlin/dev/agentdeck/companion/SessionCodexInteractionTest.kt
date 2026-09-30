package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.SessionCodexSheet
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.SessionCodexDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Codex row's "Codex settings…" is offered only where the machine serves it (a phone holding the
 * permission grant) and never on a Claude row; the dialog is three groups of radio rows, one 48dp row each,
 * with the machine's note under each group and a "desk only" line on the web modes the phone's replies do not use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionCodexInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val chosen = mutableListOf<Pair<String, String>>()

    private fun showRows(canCodex: Boolean, vendor: AgentVendor = AgentVendor.CODEX) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(listOf(row("a", "Fix the parser", vendor)), 0, emptyList(), null, DeckFixtures.NOW),
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
                        canCodex = canCodex,
                        onCodex = { opened += it.key },
                    )
                }
            }
        }
    }

    private fun showDialog(sheet: SessionCodexSheet, fontScale: Float = 1f) {
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    SessionCodexDialog(sheet, onChoose = { control, value -> chosen += control to value }, onDismiss = {})
                }
            }
        }
    }

    @Test
    fun `the row sheet offers Codex settings only where the machine serves them`() {
        showRows(canCodex = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onAllNodesWithText("Codex settings…").assertCountEquals(0)
    }

    @Test
    fun `a Claude row never offers Codex settings`() {
        showRows(canCodex = true, vendor = AgentVendor.CLAUDE)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onAllNodesWithText("Codex settings…").assertCountEquals(0)
    }

    @Test
    fun `the row sheet's Codex settings asks for that chat's controls`() {
        showRows(canCodex = true)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Codex settings…").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), opened) }
    }

    @Test
    fun `the dialog groups the three controls as radio rows with the chosen one selected`() {
        showDialog(DeckFixtures.sessionCodexSheet("convo-codex-settings")!!)

        compose.onNodeWithText("Codex settings").assertIsDisplayed()
        compose.onAllNodesWithTag("session-codex-group").assertCountEquals(3)
        // Codex default + 3 styles, + 4 modes + inherit, + 2 profiles + none.
        compose.onAllNodesWithTag("session-codex-option").assertCountEquals(4 + 5 + 3)
        compose.onNodeWithText("Pragmatic").assertIsDisplayed()
        compose.onAllNodesWithTag("session-codex-option").also { rows ->
            rows[3].assertIsSelected().assertHeightIsAtLeast(48.dp)
            rows[2].assertIsNotSelected()
        }
    }

    @Test
    fun `a tap on a row sends that control and value`() {
        showDialog(DeckFixtures.sessionCodexSheet("convo-codex-settings-narrow")!!)

        compose.onAllNodesWithTag("session-codex-option")[2].performClick()

        compose.runOnIdle { assertEquals(listOf("personality" to "friendly"), chosen) }
    }

    @Test
    fun `the machine's note shows under each group and the widening web modes say they are desk-only`() {
        showDialog(DeckFixtures.sessionCodexSheet("convo-codex-settings")!!)

        compose.onAllNodesWithTag("session-codex-note").assertCountEquals(3)
        compose.onNodeWithText("Replies sent from the phone use this.").assertIsDisplayed()
        compose.onNodeWithText("Replies sent from the phone run with Codex's default (cached pages); this applies to replies typed at the desk.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("session-codex-desk-only", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `the machine's refusal shows under the groups and a change in flight disables the rows`() {
        showDialog(DeckFixtures.sessionCodexSheet("convo-codex-settings-refused")!!.copy(busy = true))

        compose.onNodeWithText("archive is not a config profile Codex has.").assertIsDisplayed()
        compose.onAllNodesWithTag("session-codex-option")[0].assertIsNotEnabled()
    }

    @Test
    fun `an unread dialog says it is being read`() {
        showDialog(SessionCodexSheet("a", "Fix the parser", busy = true))
        compose.onNodeWithText("Reading this conversation…").assertIsDisplayed()
    }

    @Test
    fun `a refusal to read shows the machine's own sentence and no groups`() {
        showDialog(SessionCodexSheet("a", "Fix the parser", error = "This machine does not let this phone decide tool permissions or change working directories."))
        compose.onNodeWithText("This machine does not let this phone decide tool permissions or change working directories.").assertIsDisplayed()
        compose.onAllNodesWithTag("session-codex-group").assertCountEquals(0)
    }

    @Test
    fun `at twice the text size the rows stay reachable and the Close button stays put`() {
        showDialog(DeckFixtures.sessionCodexSheet("convo-codex-settings")!!, fontScale = 2f)

        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onAllNodesWithTag("session-codex-option")[0].assertHeightIsAtLeast(48.dp)
    }

    private fun row(key: String, title: String, vendor: AgentVendor) = MobileFleetRow(
        key = key, vendor = vendor, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
