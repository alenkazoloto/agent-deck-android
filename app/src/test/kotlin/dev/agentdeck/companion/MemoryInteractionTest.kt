package dev.agentdeck.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.MemorySheet
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.MemoryActions
import dev.agentdeck.companion.ui.MemoryDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Resources › Memory and instructions: the projects, the files with every state as a word, and the
 * editor whose Save waits for an edit and for a phone that may save. The states are the debug
 * fixtures', so the frames the screenshots paint are the ones asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MemoryInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun actions(drafts: Set<String> = emptySet(), onEdit: (String) -> Unit = { calls += "edit $it" }) = MemoryActions(
        onChooseProject = { calls += "project ${it.name}" },
        onOpenFile = { calls += "open $it" },
        onEdit = onEdit,
        onSave = { calls += "save" },
        onDiscard = { calls += "discard" },
        onKeepMine = { calls += "keep" },
        onLoadTheirs = { calls += "theirs" },
        onBack = { calls += "back" },
        onDismiss = { calls += "dismiss" },
        hasDraft = { it in drafts },
    )

    private fun show(sheet: MemorySheet, drafts: Set<String> = emptySet()) =
        compose.setContent { AgentDeckTheme(dynamic = false) { MemoryDialog(sheet, actions(drafts)) } }

    private fun fixture(name: String) = checkNotNull(DeckFixtures.memorySheet(name))

    @Test fun `the projects are named with their roots and a tap chooses one`() {
        val project = fixture("settings-memory-files").project!!
        show(MemorySheet(projects = listOf(project, project.copy(name = "notes", path = "/work/notes"))))

        compose.onAllNodesWithTag("memory-project-row").assertCountEquals(2)
        compose.onNodeWithText("/work/notes").assertIsDisplayed()
        compose.onNodeWithText("notes").performClick()
        compose.runOnIdle { assertEquals(listOf("project notes"), calls) }
    }

    @Test fun `no open project says so instead of an empty list`() {
        show(MemorySheet(projects = emptyList()))
        compose.onNodeWithText("No project is open in the IDE on this machine. Open one there to read its memory.").assertIsDisplayed()
    }

    @Test fun `the files are grouped and every state is a word`() {
        show(fixture("settings-memory-files"), drafts = setOf("Instructions:Project (CLAUDE.md)"))

        for (group in listOf("Agent memory", "Instructions", "Rules")) compose.onAllNodesWithText(group).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Instructions · Project · Unsaved changes").assertIsDisplayed()
        compose.onNodeWithText("Instructions · Project local · Not written yet").assertIsDisplayed()
        compose.onNodeWithText("Rules · .claude/rules · Only for matching paths").assertIsDisplayed()
        compose.onNodeWithText("The reader is a plugin maintainer").assertIsDisplayed()
        compose.onAllNodesWithText("Read only:", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag("memory-entry-row")[0].assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Project (CLAUDE.md)").performClick()
        compose.runOnIdle { assertEquals(listOf("open Instructions:Project (CLAUDE.md)"), calls) }
    }

    @Test fun `a phone that may not save is told so and where to change it`() {
        show(fixture("settings-memory-readonly"))
        compose.onNodeWithText("Read only:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Settings › Connections › Mobile › Devices", substring = true).assertIsDisplayed()
    }

    @Test fun `Save waits for an edit, and typing reports the whole text`() {
        var sheet by mutableStateOf(fixture("settings-memory-editor").let { it.copy(open = it.open!!.copy(text = it.open!!.savedText)) })
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                MemoryDialog(sheet, actions(onEdit = { text -> calls += "edit"; sheet = sheet.copy(open = sheet.open!!.copy(text = text)) }))
            }
        }

        compose.onNodeWithTag("memory-save").assertIsNotEnabled()
        compose.onNodeWithTag("memory-discard").assertIsNotEnabled()
        compose.onNodeWithTag("memory-field").performTextInput("- another rule")
        compose.onNodeWithTag("memory-save").assertIsEnabled().performClick()
        compose.onNodeWithText("Unsaved changes are kept on this phone.").assertIsDisplayed()

        compose.runOnIdle { assertEquals(listOf("edit", "save"), calls) }
    }

    @Test fun `a conflict shows both ways out and holds Save until one is chosen`() {
        show(fixture("settings-memory-conflict"))

        compose.onNodeWithText("This file changed on the machine after you opened it.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("memory-save").assertIsNotEnabled()
        compose.onNodeWithText("Load the machine's").assertIsDisplayed().performClick()
        compose.onNodeWithText("Save mine over it").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("theirs", "keep"), calls) }
    }

    @Test fun `a read-only file offers no Save and no way to type`() {
        val sheet = fixture("settings-memory-editor").let { it.copy(open = it.open!!.copy(writable = false, text = it.open!!.savedText)) }
        show(sheet)

        compose.onAllNodesWithTag("memory-save").assertCountEquals(0)
        compose.onNodeWithText("Read only:", substring = true).assertIsDisplayed()
    }

    @Test fun `Back is a labelled button and steps out one level`() {
        show(fixture("settings-memory-editor"))
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("back"), calls) }
    }
}
