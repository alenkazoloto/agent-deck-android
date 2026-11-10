package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileContextBreakdown
import com.github.claudeagents.core.mobile.MobileContextFile
import com.github.claudeagents.core.mobile.MobileContextQuery
import com.github.claudeagents.core.mobile.MobileContextRow
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.ContextSheet
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ContextDialog
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The context grid (M2 P15): a Chats row's sheet offers "Context window…" only on a machine that
 * serves it, and the dialog prints the machine's own words — headline, each row with its estimate
 * mark, the instruction files and how the estimate is made — or says why there is nothing to show.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ContextInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun showRows(canContext: Boolean) {
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
                        canContext = canContext,
                        onContext = { opened += it.key },
                    )
                }
            }
        }
    }

    private fun showDialog(sheet: ContextSheet) {
        compose.setContent { AgentDeckTheme(dynamic = false) { ContextDialog(sheet, onDismiss = {}) } }
    }

    @Test
    fun `the row sheet offers the context window only where the machine serves it`() {
        showRows(canContext = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onAllNodesWithText("Context window…").assertCountEquals(0)
    }

    @Test
    fun `the row sheet's Context window asks for that chat's grid`() {
        showRows(canContext = true)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Context window…").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), opened) }
    }

    @Test
    fun `the dialog prints the machine's headline, rows, files and estimate note`() {
        showDialog(
            ContextSheet(
                "a", "Fix the parser",
                MobileContextBreakdown(
                    key = "a",
                    measured = true,
                    headline = "30% full · 60.0K of 200.0K tokens",
                    rows = listOf(
                        MobileContextRow("System prompt, tools, and memory", "12.0K · 6%", 6, "Everything sent before the first message."),
                        MobileContextRow("Memory files", "≈2.0K · 1%", 1, nested = true),
                        MobileContextRow("Free space", "127.0K · 64%", 64),
                    ),
                    files = listOf(MobileContextFile("Project · CLAUDE.md", "≈2.0K · 8.0 KB")),
                    moreFiles = 3,
                    estimateNote = "Approximate — about 4 characters per token.",
                ),
            ),
        )

        compose.onNodeWithText("Context").assertIsDisplayed()
        compose.onNodeWithText("Fix the parser").assertIsDisplayed()
        compose.onNodeWithText("30% full · 60.0K of 200.0K tokens").assertIsDisplayed()
        compose.onNodeWithText("≈2.0K · 1%").assertIsDisplayed()
        compose.onAllNodesWithTag("context-row").assertCountEquals(3)
        compose.onNodeWithText("Project · CLAUDE.md").assertIsDisplayed()
        compose.onNodeWithText("and 3 more").assertIsDisplayed()
        compose.onNodeWithText("Approximate — about 4 characters per token.").assertIsDisplayed()
    }

    @Test
    fun `a chat with no reply yet says so instead of drawing an empty grid`() {
        showDialog(ContextSheet("a", "Fix the parser", MobileContextBreakdown("a", measured = false)))
        compose.onNodeWithText(MobileContextQuery.NOT_MEASURED).assertIsDisplayed()
    }

    @Test
    fun `an unread grid says it is being read`() {
        showDialog(ContextSheet("a", "Fix the parser"))
        compose.onNodeWithText("Reading this conversation…").assertIsDisplayed()
    }

    @Test
    fun `a refusal shows the machine's own sentence`() {
        showDialog(ContextSheet("a", "Fix the parser", error = "This IDE does not have that project open, so its context cannot be read."))
        compose.onNodeWithText("This IDE does not have that project open, so its context cannot be read.").assertIsDisplayed()
    }

    private fun row(key: String, title: String) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
