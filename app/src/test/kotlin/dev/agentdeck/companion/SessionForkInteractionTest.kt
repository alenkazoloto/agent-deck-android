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
import com.github.claudeagents.core.mobile.MobileForkPoint
import com.github.claudeagents.core.mobile.MobileSessionForkPoints
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
 * The sheet's fork (M3, P06): a Claude row offers the desk's "New chat from here…" and its picker
 * forks at the message tapped; a Codex row offers "Branch chat" instead. A running row and an
 * older machine offer neither.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionForkInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val points = mutableStateOf<MobileSessionForkPoints?>(null)
    private val started = mutableListOf<String>()
    private val picked = mutableListOf<String>()
    private val branched = mutableListOf<String>()

    private fun show(rows: List<MobileFleetRow>, canFork: Boolean = true, canBranch: Boolean = false) {
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
                        canFork = canFork,
                        onFork = { started += it.key },
                        forkPoints = points.value,
                        onForkAt = { _, point -> picked += point.id; points.value = null },
                        onDismissFork = { points.value = null },
                        canBranch = canBranch,
                        onBranch = { branched += it.key },
                    )
                }
            }
        }
    }

    @Test
    fun `a Claude row forks at the message picked from the machine's list`() {
        show(listOf(row("a", "Fix the parser")))
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("New chat from here…").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), started) }

        points.value = MobileSessionForkPoints(
            "a",
            // Two of them retyped after a failed send, as a phone can be sent them.
            points = listOf(
                MobileForkPoint("a1", "Read the grammar", 1),
                MobileForkPoint("a2", "Now fix the lexer", 2),
                MobileForkPoint("a3", "Now fix the lexer, please", 3),
            ),
            omitted = 3,
        )
        compose.onNodeWithText("New chat from before…").assertIsDisplayed()
        compose.onNodeWithText("3 older messages are not listed here — fork from them in the IDE.").assertIsDisplayed()
        compose.onNodeWithText("Now fix the lexer").performClick()
        compose.runOnIdle { assertEquals(listOf("a2"), picked) }
    }

    @Test
    fun `a Codex row offers the desk's Branch chat`() {
        show(listOf(row("c", "Codex task", vendor = AgentVendor.CODEX)))
        compose.onNodeWithText("Codex task").performTouchInput { longClick() }
        compose.onNodeWithText("New chat from here…").assertDoesNotExist()
        compose.onNodeWithText("Branch chat").performClick()
        compose.runOnIdle { assertEquals(listOf("c"), branched) }
    }

    @Test
    fun `a Claude row offers Branch chat beside New chat from here on a machine that copies whole`() {
        show(listOf(row("a", "Fix the parser")), canBranch = true)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("New chat from here…").assertIsDisplayed()
        compose.onNodeWithText("Branch chat").performClick()
        compose.runOnIdle {
            assertEquals(listOf("a"), branched)
            assertEquals("Branch is not the message picker", emptyList<String>(), started)
        }
    }

    @Test
    fun `a Claude row on an older machine offers no Branch chat`() {
        show(listOf(row("a", "Fix the parser")))
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("New chat from here…").assertIsDisplayed()
        compose.onNodeWithText("Branch chat").assertDoesNotExist()
    }

    @Test
    fun `a running row and an older machine offer no fork`() {
        show(listOf(row("r", "Busy", attention = SessionAttentionState.RUNNING)))
        compose.onNodeWithText("Busy").performTouchInput { longClick() }
        compose.onNodeWithText("Stop this run").assertIsDisplayed()
        compose.onNodeWithText("New chat from here…").assertDoesNotExist()
    }

    @Test
    fun `a machine without the route offers no fork`() {
        show(listOf(row("a", "Fix the parser")), canFork = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        compose.onNodeWithText("New chat from here…").assertDoesNotExist()
    }

    private fun row(key: String, title: String, attention: SessionAttentionState? = null, vendor: AgentVendor = AgentVendor.CLAUDE) = MobileFleetRow(
        key = key, vendor = vendor, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = attention, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
