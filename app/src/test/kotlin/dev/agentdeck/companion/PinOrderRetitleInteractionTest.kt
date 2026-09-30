package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
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
 * The long-press sheet's "Move pin up/down" and "Regenerate title" (M3, P05): a move is offered
 * on a pin with another pin painted beside it and names the pins the list paints, in the desk's
 * order; Regenerate title is offered where the machine can take a new name; an older machine
 * offers neither.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PinOrderRetitleInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val moves = mutableListOf<Triple<String, List<String>, Int>>()
    private val retitled = mutableListOf<String>()

    private fun show(rows: List<MobileFleetRow>, capable: Boolean = true) {
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
                        canOrganize = true,
                        canPinOrder = capable,
                        onMovePin = { row, painted, delta -> moves += Triple(row.key, painted, delta) },
                        canRetitle = capable,
                        onRetitle = { retitled += it.key },
                    )
                }
            }
        }
    }

    @Test
    fun `a pin moves among the pins the list paints, in the desk's order`() {
        show(listOf(row("a", "Alpha", pinRank = 1, activityMs = 9), row("b", "Beta", pinRank = 0, activityMs = 1), row("c", "Gamma")))

        compose.onNodeWithText("Alpha").performTouchInput { longClick() }
        compose.onNodeWithText("Move pin down").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Move pin up").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf(Triple("a", listOf("b", "a"), -1)), moves) }
    }

    @Test
    fun `a lone pin and an unpinned row offer no move`() {
        show(listOf(row("a", "Alpha", pinRank = 0), row("c", "Gamma")))
        compose.onNodeWithText("Alpha").performTouchInput { longClick() }
        compose.onNodeWithText("Unpin").assertIsDisplayed()
        compose.onNodeWithText("Move pin up").assertDoesNotExist()
    }

    @Test
    fun `regenerate title sends the row it was opened on`() {
        show(listOf(row("c", "Gamma")))
        compose.onNodeWithText("Gamma").performTouchInput { longClick() }
        compose.onNodeWithText("Regenerate title").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("c"), retitled) }
    }

    @Test
    fun `a running Codex chat is not offered a new title`() {
        show(listOf(row("x", "Codex thread", vendor = AgentVendor.CODEX, attention = SessionAttentionState.RUNNING)))
        compose.onNodeWithText("Codex thread").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Regenerate title").assertDoesNotExist()
    }

    @Test
    fun `an older machine offers neither`() {
        show(listOf(row("a", "Alpha", pinRank = 0), row("b", "Beta", pinRank = 1)), capable = false)
        compose.onNodeWithText("Alpha").performTouchInput { longClick() }
        compose.onNodeWithText("Unpin").assertIsDisplayed()
        listOf("Move pin up", "Move pin down", "Regenerate title").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    private fun row(
        key: String,
        title: String,
        pinRank: Int? = null,
        activityMs: Long = 5,
        vendor: AgentVendor = AgentVendor.CLAUDE,
        attention: SessionAttentionState? = null,
    ) = MobileFleetRow(
        key = key, vendor = vendor, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = attention, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - activityMs * 60_000, costUsd = 0.0, costKnown = true, contextPct = null,
        messageCount = 4, pinned = pinRank != null, pinRank = pinRank,
    )
}
