package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
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
 * The sheet's "Continue on another account…": a Claude row that is not running offers it when the
 * machine serves the route and lists another Claude account, the dialog names the accounts other
 * than the chat's own, and a pick hands the row and the account back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionHandoffInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val handed = mutableListOf<Pair<String, String>>()
    private val choosing = mutableStateOf<String?>(null)

    private fun show(
        rows: List<MobileFleetRow>,
        canHandoff: Boolean = true,
        accounts: List<MobileScheduleAccountOption> = ACCOUNTS,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(rows, 0, emptyList(), null, DeckFixtures.NOW),
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
                        canHandoff = canHandoff,
                        claudeAccounts = accounts,
                        handoffKey = choosing.value,
                        onHandoffStart = { choosing.value = it.key },
                        onDismissHandoff = { choosing.value = null },
                        onHandoff = { row, account -> choosing.value = null; handed += row.key to account.id },
                    )
                }
            }
        }
    }

    @Test
    fun `a Claude row lists the other accounts with a limit's reset and hands the pick back`() {
        show(listOf(row("a", "Fix the parser")))
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Continue on another account…").performClick()

        compose.onNodeWithText("Continue on another account").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Side").assertIsDisplayed()
        compose.onNodeWithText("Default").assertDoesNotExist()
        compose.onNodeWithText("Limit reached", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Work").performClick()

        compose.runOnIdle { assertEquals(listOf("a" to "work"), handed) }
        compose.onNodeWithText("Continue on another account").assertDoesNotExist()
    }

    @Test
    fun `cancelling hands nothing back`() {
        show(listOf(row("a", "Fix the parser")))
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Continue on another account…").performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.runOnIdle { assertEquals(emptyList<Pair<String, String>>(), handed) }
    }

    @Test
    fun `a running row offers no handoff`() {
        show(listOf(row("r", "Busy", attention = SessionAttentionState.RUNNING)))
        compose.onNodeWithText("Busy").performTouchInput { longClick() }
        compose.onNodeWithText("Stop this run").assertIsDisplayed()
        compose.onNodeWithText("Continue on another account…").assertDoesNotExist()
    }

    @Test
    fun `a Codex row offers none`() {
        show(listOf(row("c", "Codex task", vendor = AgentVendor.CODEX)))
        compose.onNodeWithText("Codex task").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        compose.onNodeWithText("Continue on another account…").assertDoesNotExist()
    }

    @Test
    fun `a chat with no other account to copy to offers none`() {
        show(listOf(row("a", "Fix the parser")), accounts = listOf(MobileScheduleAccountOption("default", "Default")))
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        compose.onNodeWithText("Continue on another account…").assertDoesNotExist()
    }

    @Test
    fun `a machine without the route offers none`() {
        show(listOf(row("a", "Fix the parser")), canHandoff = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        compose.onNodeWithText("Continue on another account…").assertDoesNotExist()
    }

    private fun row(key: String, title: String, attention: SessionAttentionState? = null, vendor: AgentVendor = AgentVendor.CLAUDE) = MobileFleetRow(
        key = key, vendor = vendor, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = attention, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )

    private companion object {
        val ACCOUNTS = listOf(
            MobileScheduleAccountOption("default", "Default"),
            MobileScheduleAccountOption("work", "Work", resetAtMs = DeckFixtures.NOW + 90 * 60_000),
            MobileScheduleAccountOption("side", "Side"),
        )
    }
}
