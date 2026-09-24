package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.claudeagents.core.AgentVendor
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
                    { _, _, _, _ -> }, { _, _, _ -> },
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

    @Test fun `effort and mode picks ride the save only once touched, and only from a host that applies them`() {
        val hello = MobileHello(
            protocolVersion = MobileProtocol.VERSION, machineName = "m", ideName = "IDE", pluginVersion = "1",
            capabilities = listOf(MobileProtocol.Capability.EFFORT, MobileProtocol.Capability.PERMISSION_MODES),
            effort = mapOf(AgentVendor.CLAUDE to listOf(MobileModelOption("high", "High effort"))),
            permissionModes = mapOf(AgentVendor.CLAUDE to listOf(MobileModelOption("plan", "Plan mode"))),
        )
        fun detail(editable: Boolean) = MobileScheduleEditDetail("task", "Review patch", "/work/project", "chat", 1_800_000_000_000,
            0, null, null, "CLAUDE", "personal", null, "Europe/Amsterdam", true, false,
            effort = "high", runOptionsEditable = editable)
        val saved = mutableListOf<MobileScheduleEditRequest>()
        val shown = mutableStateOf(detail(true))
        ui.setContent {
            AgentDeckTheme {
                ScheduledScreen(listOf(MobileScheduledRow("task", "Review patch", "/work/project", "chat",
                    1_800_000_000_000, MobileScheduledRow.PAUSED, true)), false, true, emptyList(), hello, "", {}, {},
                    { _, _, _, _ -> }, { _, _, _ -> }, onEdit = {}, editId = "task", editDetail = shown.value,
                    onEditSave = { saved += it })
            }
        }
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {
            assertFalse("an untouched form rewrote the row's run options", saved.single().editRunOptions)
            assertEquals("high", saved.single().effort)
        }
        ui.onNodeWithContentDescription("High effort").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Mode: Default").performScrollTo().performClick()
        ui.onNodeWithText("Plan mode").performClick()
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {
            assertTrue(saved.last().editRunOptions)
            assertEquals("plan", saved.last().permissionMode)
            assertEquals("high", saved.last().effort)
        }
        shown.value = detail(false)
        ui.onNodeWithTag("schedule-edit-effort").assertDoesNotExist()
        ui.onNodeWithTag("schedule-edit-mode").assertDoesNotExist()
    }
}
