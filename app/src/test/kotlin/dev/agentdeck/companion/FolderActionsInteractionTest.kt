package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileFolder
import com.github.claudeagents.core.mobile.MobileFolderActionRequest
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
 * Editing a desk folder from the Chats filter (M3, P05): the pencil appears only beside one chosen
 * folder, Save sends only what changed, a clashing name cannot be sent, and Delete asks first,
 * naming the chats it releases.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class FolderActionsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val filter = mutableStateOf(FleetFilter())
    private val sent = mutableListOf<MobileFolderActionRequest>()
    private val folders = mutableStateOf(listOf(
        MobileFolder("rel", "Release 1.4", note = "before the tag", count = 2),
        MobileFolder("old", "Old work", done = true),
    ))

    private fun show(canEdit: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(listOf(row("a", "rel"), row("b", "rel")), 0, emptyList(), null, DeckFixtures.NOW, folders = folders.value),
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
                        canFolder = true,
                        canEditFolders = canEdit,
                        onFolderAction = { sent += it },
                    )
                }
            }
        }
    }

    private val edit = "Edit folder “Release 1.4”"

    @Test
    fun `the pencil appears only beside one chosen folder`() {
        show()
        compose.onNodeWithContentDescription(edit).assertDoesNotExist()
        filter.value = FleetFilter(folderId = FleetFilter.UNFILED)
        compose.onNodeWithContentDescription(edit).assertDoesNotExist()
        filter.value = FleetFilter(folderId = "rel")
        compose.onNodeWithContentDescription(edit).assertIsDisplayed()
    }

    @Test
    fun `an older machine offers no folder editing`() {
        filter.value = FleetFilter(folderId = "rel")
        show(canEdit = false)
        compose.onNodeWithContentDescription(edit).assertDoesNotExist()
    }

    @Test
    fun `save sends only the changed fields and refuses a taken name`() {
        filter.value = FleetFilter(folderId = "rel")
        show()
        compose.onNodeWithContentDescription(edit).performClick()
        compose.onNodeWithText("Edit folder").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNode(hasSetTextAction() and hasText("Release 1.4")).performTextReplacement("old WORK")
        compose.onNodeWithText("A folder called \"old WORK\" already exists.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNode(hasSetTextAction() and hasText("old WORK")).performTextReplacement("Release 1.5")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf(MobileFolderActionRequest("rel", MobileFolderActionRequest.EDIT, name = "Release 1.5", done = true)), sent)
        }
        compose.onNodeWithText("Edit folder").assertDoesNotExist()
    }

    @Test
    fun `clearing the note sends a blank note`() {
        filter.value = FleetFilter(folderId = "rel")
        show()
        compose.onNodeWithContentDescription(edit).performClick()
        compose.onNode(hasSetTextAction() and hasText("before the tag")).performTextReplacement("")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(listOf(MobileFolderActionRequest("rel", MobileFolderActionRequest.EDIT, note = "")), sent) }
    }

    @Test
    fun `delete asks first and names the chats it releases`() {
        filter.value = FleetFilter(folderId = "rel")
        show()
        compose.onNodeWithContentDescription(edit).performClick()
        compose.onNodeWithText("Delete…").performClick()
        compose.onNodeWithText("Delete the folder “Release 1.4”? Its 2 chats stay in the list, in no folder.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<MobileFolderActionRequest>(), sent) }

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Edit folder").assertIsDisplayed()
        compose.onNodeWithText("Delete…").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(listOf(MobileFolderActionRequest("rel", MobileFolderActionRequest.DELETE)), sent) }
    }

    @Test
    fun `a desk edit arriving while the form is open is not sent back`() {
        filter.value = FleetFilter(folderId = "rel")
        show()
        compose.onNodeWithContentDescription(edit).performClick()
        folders.value = folders.value.map { if (it.id == "rel") it.copy(name = "Release 1.5", done = true) else it }
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNode(hasSetTextAction() and hasText("before the tag")).performTextReplacement("after the tag")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(listOf(MobileFolderActionRequest("rel", MobileFolderActionRequest.EDIT, note = "after the tag")), sent) }
    }

    private fun row(key: String, folderId: String?) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = "Chat $key", attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        folderId = folderId,
    )
}
