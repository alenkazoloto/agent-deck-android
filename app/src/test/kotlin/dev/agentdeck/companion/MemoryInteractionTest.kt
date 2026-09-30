package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.MemorySheet
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.MemoryActions
import dev.agentdeck.companion.ui.MemoryDialog
import dev.agentdeck.companion.ui.capLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.claudeagents.core.mobile.MobileMemoryCap
import com.github.claudeagents.core.mobile.MobileMemoryLoadOrder
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage

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

    private fun actions(
        drafts: Set<String> = emptySet(),
        onEdit: (String) -> Unit = { calls += "edit $it" },
        onLoadOrder: (() -> Unit)? = { calls += "load order" },
    ) = MemoryActions(
        onChooseProject = { calls += "project ${it.name}" },
        onOpenFile = { calls += "open $it" },
        onEdit = onEdit,
        onSave = { calls += "save" },
        onDiscard = { calls += "discard" },
        onKeepMine = { calls += "keep" },
        onLoadTheirs = { calls += "theirs" },
        onDelete = { calls += "delete" },
        onBack = { calls += "back" },
        onDismiss = { calls += "dismiss" },
        hasDraft = { it in drafts },
        onLoadOrder = onLoadOrder,
    )

    private fun show(sheet: MemorySheet, drafts: Set<String> = emptySet(), onLoadOrder: (() -> Unit)? = { calls += "load order" }, fontScale: Float = 1f) =
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    MemoryDialog(sheet, actions(drafts, onLoadOrder = onLoadOrder))
                }
            }
        }

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

    @Test fun `an agent note offers Delete, and nothing is sent until the confirmation is accepted`() {
        show(fixture("settings-memory-note"))

        compose.onNodeWithTag("memory-delete").assertIsEnabled().performClick()
        compose.onNodeWithText("Delete user_role.md?").assertIsDisplayed()
        compose.onNodeWithText("This phone cannot bring it back.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("memory-delete-cancel").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }

        compose.onNodeWithTag("memory-delete").performClick()
        compose.onNodeWithTag("memory-delete-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("delete"), calls) }
    }

    @Test fun `the confirmation says unsaved text goes with the note`() {
        val sheet = fixture("settings-memory-note").let { it.copy(open = it.open!!.copy(text = it.open!!.savedText + "more")) }
        show(sheet)

        compose.onNodeWithTag("memory-delete").performClick()

        compose.onNodeWithText("Your unsaved changes to it go too.", substring = true).assertIsDisplayed()
    }

    @Test fun `instructions are offered no Delete`() {
        show(fixture("settings-memory-editor"))
        compose.onAllNodesWithTag("memory-delete").assertCountEquals(0)
    }

    @Test fun `a note in conflict cannot be deleted blind`() {
        val sheet = fixture("settings-memory-conflict").let { it.copy(open = it.open!!.copy(deletable = true)) }
        show(sheet)
        compose.onNodeWithTag("memory-delete").assertIsNotEnabled()
    }

    @Test fun `a read-only note offers no Delete`() {
        val sheet = fixture("settings-memory-note").let { it.copy(open = it.open!!.copy(writable = false)) }
        show(sheet)
        compose.onAllNodesWithTag("memory-delete").assertCountEquals(0)
    }

    @Test fun `Back is a labelled button and steps out one level`() {
        show(fixture("settings-memory-editor"))
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("back"), calls) }
    }

    // ---- Load order ----

    @Test fun `the file list offers Load order where the machine serves it and only there`() {
        show(fixture("settings-memory-files"))
        compose.onNodeWithTag("memory-load-order").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("load order"), calls) }
    }

    @Test fun `an older machine's list has no Load order button`() {
        show(fixture("settings-memory-files"), onLoadOrder = null)
        compose.onAllNodesWithTag("memory-load-order").assertCountEquals(0)
    }

    @Test fun `the load order is the desk's summary and rows, each with its status word, and never a path`() {
        show(fixture("settings-memory-load-order"))

        compose.onNodeWithText("Load order").assertIsDisplayed()
        compose.onNodeWithTag("memory-load-order-summary").assertIsDisplayed()
        compose.onNodeWithText("6 of 11 load for this project, in this order. 3 @imports, 2 on demand, 1 excluded.").assertIsDisplayed()
        compose.onAllNodesWithTag("memory-load-order-row").assertCountEquals(11)
        compose.onAllNodesWithTag("memory-load-order-row")[0].assertHeightIsAtLeast(48.dp)
        for (word in listOf("not present", "812 B", "3.4 kB", "missing", "2 files", "1 file", "excluded", "on demand · 640 B")) {
            compose.onAllNodesWithText(word).onFirst().assertExists()
        }
        compose.onNodeWithText("@style.md").assertExists()
        compose.onNodeWithText("Skipped by claudeMdExcludes pattern: **/packages/**", substring = true).assertExists()
        compose.onAllNodesWithText("/Users", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag("memory-load-order-row")[3].assertTextContains("@style.md", substring = true)
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("back"), calls) }
    }

    /** Taller than a phone so the whole list, including the excluded and on-demand rows, is in the one frame. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Config(sdk = [34], qualifiers = "w411dp-h1250dp")
    @Test fun `the whole load order in one frame`() {
        show(fixture("settings-memory-load-order"))
        compose.onNodeWithText("Subdirectory · docs/CLAUDE.md").assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(compose.onAllNodes(isRoot()).fetchSemanticsNodes().size - 1).captureRoboImage("build/outputs/p29/memory-load-order.png", RECORD)
    }

    @Test fun `a load order still being read and one that failed say so`() {
        show(fixture("settings-memory-load-order").copy(loadOrder = null, busy = true))
        compose.onNodeWithText("Reading this project's load order…").assertIsDisplayed()
    }

    @Test fun `a load order the machine could not give shows its sentence, with Back still there`() {
        show(fixture("settings-memory-load-order").copy(loadOrder = null, error = "The machine could not be reached."))
        compose.onNodeWithText("The machine could not be reached.").assertIsDisplayed()
        compose.onAllNodesWithTag("memory-load-order-row").assertCountEquals(0)
        compose.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test fun `a project that is not open is a sentence in the load order`() {
        show(fixture("settings-memory-load-order").copy(loadOrder = MobileMemoryLoadOrder("/x", unavailable = "That project is not open in the IDE. Open it there, or pick another.")))
        compose.onNodeWithText("That project is not open in the IDE. Open it there, or pick another.").assertIsDisplayed()
    }

    @Test fun `at twice the text size the load order rows are still whole and reachable`() {
        show(fixture("settings-memory-load-order"), fontScale = 2f)
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onAllNodesWithTag("memory-load-order-row")[0].assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("memory-load-order-summary").assertIsDisplayed()
    }

    // ---- Cap meter and rules line ----

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun `an index past its cap reads as an error in words and says it is as saved once edited`() {
        show(fixture("settings-memory-cap"))

        compose.onNodeWithTag("memory-cap").assertIsDisplayed()
        compose.onNodeWithText("Error · Over the 200-line load limit · as saved").assertIsDisplayed()
        compose.onNodeWithText("201 lines of 200", substring = true).assertIsDisplayed()
        compose.onNodeWithText("keep index entries to one line each", substring = true).assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(compose.onAllNodes(isRoot()).fetchSemanticsNodes().size - 1).captureRoboImage("build/outputs/p29/memory-cap-meter.png", RECORD)
    }

    @Test fun `the meter line names its severity and is plain below the limit`() {
        assertEquals("Warning · 85% of the memory load limit", capLine(MobileMemoryCap("85% of the memory load limit", "", MobileMemoryCap.APPROACHING), false))
        assertEquals("Error · Over the 200-line load limit · as saved", capLine(MobileMemoryCap("Over the 200-line load limit", "", MobileMemoryCap.OVER), true))
        assertEquals("5% of the memory load limit", capLine(MobileMemoryCap("5% of the memory load limit", "", MobileMemoryCap.OK), false))
    }

    @Test fun `a file with no meter shows no meter line`() {
        show(fixture("settings-memory-editor"))
        compose.onAllNodesWithTag("memory-cap").assertCountEquals(0)
        compose.onAllNodesWithTag("memory-rule-paths").assertCountEquals(0)
    }

    @Test fun `a conditional rule lists the globs it loads for`() {
        show(fixture("settings-memory-rule"))
        compose.onNodeWithText("Loads only for files matching:").assertIsDisplayed()
        compose.onNodeWithText("src/test/**").assertIsDisplayed()
        compose.onNodeWithText("**/*Test.kt").assertIsDisplayed()
    }

    @Test fun `an unconditional rule says it always loads`() {
        val sheet = fixture("settings-memory-rule").let {
            it.copy(open = it.open!!.copy(rulePaths = com.github.claudeagents.core.mobile.MobileMemoryRulePaths("Always loaded", "This rule has no paths: front-matter, so Claude loads it for every file.")))
        }
        show(sheet)
        compose.onNodeWithText("Always loaded").assertIsDisplayed()
        compose.onNodeWithText("This rule has no paths: front-matter, so Claude loads it for every file.").assertIsDisplayed()
    }

    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
