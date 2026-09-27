package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileFolder
import dev.agentdeck.companion.data.FleetBrowsing
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.ReadingPositions
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
 * The row sheet's Move to folder (M3, P05) and the Chats folder filter: the dialog starts on the
 * row's folder, a pick or a new name is one request, a name the desk would refuse cannot be sent,
 * and the list narrows to a folder or to chats in none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionFoldersInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val filter = mutableStateOf(FleetFilter())
    private val filed = mutableListOf<Triple<String, String?, String?>>()
    private val folders = listOf(MobileFolder("rel", "Release 1.4"), MobileFolder("old", "Old work", done = true))

    private fun show(rows: List<MobileFleetRow>, canFolder: Boolean = true, folders: List<MobileFolder> = this.folders) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(rows, 0, emptyList(), null, DeckFixtures.NOW, folders = folders),
                        filter = filter.value,
                        sort = FleetSort.RECENT,
                        refreshing = false,
                        snoozed = emptyMap(),
                        openKey = null,
                        onFilter = { filter.value = it },
                        onSort = {},
                        onRefresh = {},
                        onOpen = {},
                        onSnooze = {},
                        onStop = {},
                        canOrganize = true,
                        canFolder = canFolder,
                        onFile = { row, id, name -> filed += Triple(row.key, id, name) },
                    )
                }
            }
        }
    }

    @Test
    fun `the sheet names the folder and the dialog moves the chat`() {
        show(listOf(row("a", "Fix the parser", folderId = "rel")))

        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        // The sheet's Folder line, below its actions; the list's filter still reads "All folders".
        compose.onNodeWithText("Release 1.4").assertExists()
        compose.onNodeWithText("Move to folder…").performClick()

        compose.onNodeWithText("Move to folder").assertIsDisplayed()
        compose.onNodeWithText("Release 1.4").assertIsSelected()
        compose.onNodeWithText("Old work (done)").performClick()

        compose.runOnIdle { assertEquals(listOf(Triple("a", "old", null)), filed) }
    }

    @Test
    fun `No folder takes the chat out, and a new folder needs a free name`() {
        show(listOf(row("a", "Fix the parser", folderId = "rel")))
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Move to folder…").performClick()

        compose.onNodeWithText("Create and move").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("release  1.4")
        compose.onNodeWithText("A folder called \"release 1.4\" already exists.").assertIsDisplayed()
        compose.onNodeWithText("Create and move").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Bug bash")
        compose.onNodeWithText("Create and move").assertIsEnabled().performClick()

        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Move to folder…").performClick()
        compose.onNodeWithText("No folder").performClick()

        compose.runOnIdle { assertEquals(listOf(Triple("a", null, "Bug bash"), Triple("a", "", null)), filed) }
    }

    @Test
    fun `the folder filter narrows to a folder or to chats in none`() {
        show(listOf(row("a", "Fix the parser", folderId = "rel"), row("b", "Write the docs")))

        compose.onNodeWithText("All folders").performClick()
        compose.onNodeWithText("Release 1.4").performClick()
        compose.onNodeWithText("Fix the parser").assertIsDisplayed()
        compose.onNodeWithText("Write the docs").assertDoesNotExist()

        filter.value = filter.value.copy(folderId = FleetFilter.UNFILED)
        compose.onNodeWithText("Write the docs").assertIsDisplayed()
        compose.onNodeWithText("Fix the parser").assertDoesNotExist()
    }

    @Test
    fun `a folder deleted at the desk stops filtering instead of emptying the list`() {
        filter.value = FleetFilter(folderId = "deleted")
        show(listOf(row("a", "Fix the parser"), row("b", "Write the docs")))
        compose.onNodeWithText("Fix the parser").assertIsDisplayed()
        compose.onNodeWithText("Write the docs").assertIsDisplayed()
    }

    @Test
    fun `an older machine offers no folders`() {
        show(listOf(row("a", "Fix the parser")), canFolder = false, folders = emptyList())
        compose.onNodeWithText("All folders").assertDoesNotExist()
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Move to folder…").assertDoesNotExist()
    }

    @Test
    fun `the folder filter survives a restart`() {
        val saved = ReadingPositions(fleet = FleetBrowsing(filter = FleetFilter(folderId = FleetFilter.UNFILED)))
        assertEquals(FleetFilter.UNFILED, ReadingPositions.fromJson(saved.toJson()).fleet.filter.folderId)
    }

    private fun row(key: String, title: String, folderId: String? = null) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        folderId = folderId,
    )
}
