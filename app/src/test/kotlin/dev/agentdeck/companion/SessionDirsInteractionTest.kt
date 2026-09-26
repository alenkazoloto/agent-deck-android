package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.SessionDirsSheet
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.SessionDirsDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Chats row's "Working directories…" is offered only where the machine serves it (a phone holding
 * the permission grant), and the dialog lists each directory with a named remove button and a typed
 * path with Add — the machine's refusal sits under the field, the text stays.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionDirsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val typed = mutableListOf<String>()
    private var added = 0
    private val removed = mutableListOf<String>()

    private fun showRows(canDirs: Boolean) {
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
                        canDirs = canDirs,
                        onDirs = { opened += it.key },
                    )
                }
            }
        }
    }

    private fun showDialog(sheet: SessionDirsSheet) {
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                SessionDirsDialog(sheet, onDraft = { typed += it }, onAdd = { added++ }, onRemove = { removed += it }, onDismiss = {})
            }
        }
    }

    @Test
    fun `the row sheet offers working directories only where the machine serves them`() {
        showRows(canDirs = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onAllNodesWithText("Working directories…").assertCountEquals(0)
    }

    @Test
    fun `the row sheet's Working directories asks for that chat's list`() {
        showRows(canDirs = true)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Working directories…").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), opened) }
    }

    @Test
    fun `the dialog lists each directory with a named 48dp remove button`() {
        showDialog(SessionDirsSheet("a", "Fix the parser", dirs = listOf("/work/shared", "/notes/rfcs")))

        compose.onNodeWithText("Working directories").assertIsDisplayed()
        compose.onNodeWithText("Fix the parser").assertIsDisplayed()
        compose.onAllNodesWithTag("session-dirs-row").assertCountEquals(2)
        compose.onNodeWithText("/work/shared").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove /notes/rfcs").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("/notes/rfcs"), removed) }
    }

    @Test
    fun `a typed path is a text field and Add sends it, but not while it is blank`() {
        var sheet by mutableStateOf(SessionDirsSheet("a", "Fix the parser", dirs = emptyList()))
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                SessionDirsDialog(sheet, onDraft = { typed += it; sheet = sheet.copy(draft = it) }, onAdd = { added++ }, onRemove = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("No additional working directories.").assertIsDisplayed()
        compose.onNodeWithTag("session-dirs-add").assertIsNotEnabled()
        compose.onNodeWithTag("session-dirs-field").performTextInput("/work/docs")
        compose.onNodeWithTag("session-dirs-add").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf("/work/docs"), typed)
            assertEquals(1, added)
        }
    }

    @Test
    fun `the machine's refusal shows under the field and the typed text is kept`() {
        showDialog(
            SessionDirsSheet(
                "a", "Fix the parser", dirs = listOf("/work/shared"),
                draft = "/work/typo", refused = "/work/typo is not a directory.",
            ),
        )

        compose.onNodeWithText("/work/typo is not a directory.").assertIsDisplayed()
        compose.onNodeWithText("/work/typo").assertIsDisplayed()
        compose.onNodeWithTag("session-dirs-add").assertIsEnabled()
    }

    @Test
    fun `an unread list says it is being read and offers no field`() {
        showDialog(SessionDirsSheet("a", "Fix the parser", busy = true))
        compose.onNodeWithText("Reading this conversation…").assertIsDisplayed()
        compose.onAllNodesWithTag("session-dirs-field").assertCountEquals(0)
    }

    @Test
    fun `a refusal to read shows the machine's own sentence and no list`() {
        showDialog(SessionDirsSheet("a", "Fix the parser", error = "This machine does not let this phone decide tool permissions or change working directories."))
        compose.onNodeWithText("This machine does not let this phone decide tool permissions or change working directories.").assertIsDisplayed()
        compose.onAllNodesWithTag("session-dirs-field").assertCountEquals(0)
    }

    private fun row(key: String, title: String) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
