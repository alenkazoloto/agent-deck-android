package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileSessionDeletePreview
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.deleteConsequences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet's Delete (M3, P06): it asks for the machine's preview, the dialog says what that
 * preview says, and only its destructive button confirms. A running row and an older machine
 * offer no Delete at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionDeleteInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val preview = mutableStateOf<MobileSessionDeletePreview?>(null)
    private val previewed = mutableListOf<String>()
    private val confirmed = mutableListOf<String>()

    private fun show(rows: List<MobileFleetRow>, canDelete: Boolean = true) {
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
                        canDelete = canDelete,
                        onDelete = { previewed += it.key },
                        deletePreview = preview.value,
                        onConfirmDelete = { confirmed += it.previewToken; preview.value = null },
                        onDismissDelete = { preview.value = null },
                    )
                }
            }
        }
    }

    @Test
    fun `Delete asks the machine first and confirms only its own preview`() {
        show(listOf(row("a", "Fix the parser")))
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Delete the conversation…").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), previewed) }

        preview.value = MobileSessionDeletePreview("a", "Fix the parser", true, 1, 2, "tok")
        compose.onNodeWithText("Move chat to Trash?").assertIsDisplayed()
        compose.onNode(hasText("scheduled prompts", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("Cancel confirms nothing", emptyList<String>(), confirmed) }

        preview.value = MobileSessionDeletePreview("a", "Fix the parser", true, 1, 0, "tok")
        compose.onNode(hasText("Move to Trash") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("tok"), confirmed) }
    }

    @Test
    fun `a running row and an older machine offer no Delete`() {
        show(listOf(row("r", "Busy", attention = SessionAttentionState.RUNNING)))
        compose.onNodeWithText("Busy").performTouchInput { longClick() }
        compose.onNodeWithText("Stop this run").assertIsDisplayed()
        compose.onNodeWithText("Delete the conversation…").assertDoesNotExist()
    }

    @Test
    fun `a machine without the route offers no Delete`() {
        show(listOf(row("a", "Fix the parser")), canDelete = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        compose.onNodeWithText("Delete the conversation…").assertDoesNotExist()
    }

    @Test
    fun `the consequences name where the chat goes and whether it comes back`() {
        val trash = deleteConsequences(MobileSessionDeletePreview("a", "Parser", true, 2, 1, "t"))
        assertTrue(trash, trash.startsWith("All 2 copies of “Parser” move to the Trash on the machine, where it can be restored."))
        assertTrue(trash, trash.endsWith("The scheduled prompt for this chat is also canceled."))
        val codex = deleteConsequences(MobileSessionDeletePreview("a", "Parser", false, 1, 0, "t"))
        assertTrue(codex, codex.contains("This cannot be undone."))
    }

    private fun row(key: String, title: String, attention: SessionAttentionState? = null) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = attention, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
