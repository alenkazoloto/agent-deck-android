package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.claudeagents.core.mobile.*
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ScheduledScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduleEditInteractionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun `row opens editor and keeps draft through failed save and reopen`() {
        val detail = MobileScheduleEditDetail("task", "Review patch", "/work/project", "chat", 1_800_000_000_000,
            900_000, null, "sonnet", "CLAUDE", "personal", null, "Europe/Amsterdam", true, true,
            listOf(MobileScheduleAccountOption("personal", "Personal"), MobileScheduleAccountOption("work", "Work")))
        val editing = mutableStateOf(false)
        val draft = mutableStateOf("")
        val error = mutableStateOf<String?>(null)
        val saved = mutableListOf<MobileScheduleEditRequest>()
        ui.setContent {
            AgentDeckTheme {
                ScheduledScreen(listOf(MobileScheduledRow("task", detail.prompt, detail.projectPath, "chat",
                    detail.dueAtMs, MobileScheduledRow.PAUSED, true)), false, true, emptyList(), null, "", {}, {},
                    { _, _, _ -> }, { _, _, _ -> },
                    onEdit = { assertEquals("task", it); editing.value = true }, editId = "task".takeIf { editing.value },
                    editDetail = detail.takeIf { editing.value }, editDraft = draft.value, editError = error.value,
                    onEditDraft = { draft.value = it }, onEditDismiss = { editing.value = false },
                    onEditSave = { saved += it; error.value = "Try saving again." })
            }
        }
        ui.onNodeWithContentDescription("Resume").performClick()
        ui.onNodeWithText("Edit scheduled prompt").assertDoesNotExist()
        ui.onNodeWithText("Review patch").performClick()
        ui.onNodeWithTag("schedule-edit-prompt").assertTextContains("Review patch").performTextReplacement("Revised task")
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {
            assertEquals("Revised task", saved.single().prompt)
            assertEquals("keep", saved.single().whenChoice)
            assertTrue(saved.single().repeat)
            assertTrue(editing.value)
        }
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Review patch").performClick()
        ui.onNodeWithTag("schedule-edit-prompt").assertTextContains("Revised task")
        ui.onNodeWithContentDescription("This chat").performScrollTo().performClick()
        ui.onNodeWithText("New Claude chat").performClick()
        ui.onNodeWithContentDescription("Account: Personal").performScrollTo().performClick()
        ui.onNodeWithText("Work").performClick()
        ui.onNodeWithContentDescription("New Claude chat").performScrollTo().performClick()
        ui.onNodeWithText("This chat").performClick()
        ui.onNodeWithContentDescription("This chat").performScrollTo().performClick()
        ui.onNodeWithText("New Claude chat").performClick()
        ui.onNodeWithContentDescription("Account: Work").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle { assertTrue(saved.last().newChat); assertEquals("work", saved.last().accountId) }
    }
}
