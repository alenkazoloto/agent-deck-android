package dev.agentdeck.companion

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.mobile.*
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledEditE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private lateinit var bridge: StoryBridge
    private var scenario: ActivityScenario<MainActivity>? = null
    private val detail = MobileScheduleEditDetail(
        "edit-task", "Review the navigation changes", "/work/project", "chat", 1_800_000_000_000,
        3_600_000, null, "sonnet", "CLAUDE", "personal", null, "Europe/Amsterdam", true, true,
        listOf(MobileScheduleAccountOption("personal", "Personal"), MobileScheduleAccountOption("work", "Work")),
        listOf(MobileScheduleDependencySource("source-task", "Build the feature", "pending")),
    )

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
        bridge = StoryBridge()
        bridge.scheduleDetails[detail.id] = detail
        bridge.scheduled = listOf(MobileScheduledRow(detail.id, detail.prompt, detail.projectPath, detail.sessionId,
            detail.dueAtMs, MobileScheduledRow.PAUSED, true))
        store.save(bridge.machine)
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        store.saveScreen(Navigation.toJson(Screen.Scheduled))
    }

    @After fun finish() {
        scenario?.close()
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        bridge.close()
    }

    @Test fun tapEditAndSaveUsesSameTaskAndKeepsUnsentEditsOnFailure() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        awaitText(detail.prompt)
        ui.onNodeWithText(detail.prompt).performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("schedule-edit-prompt").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("schedule-edit-prompt").assertTextContains(detail.prompt)
        screenshot("mobile-schedule-edit-top")
        ui.onNodeWithTag("schedule-edit-prompt").performTextReplacement("Review the schedule editor")
        ui.onNodeWithText("In", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("schedule-edit-delay").performScrollTo().performTextReplacement("12")
        ui.onNodeWithTag("schedule-edit-model").performScrollTo().performTextReplacement("custom-model")
        ui.onNodeWithText("Save").assertIsDisplayed()
        ui.onNodeWithText("Cancel").assertIsDisplayed()
        screenshot("mobile-schedule-edit-keyboard")
        hideKeyboard()
        screenshot("mobile-schedule-edit-fields")
        bridge.refuseScheduleEdit = true
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10_000) { bridge.commands.any { it.first == "/v1/scheduled/${detail.id}" } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Save").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.drafts(bridge.machine.id)[DeckViewModel.scheduleEditDraftKey(detail.id)].orEmpty()
            .contains("Review the schedule editor"))
        ui.onNodeWithText("Cancel").performClick()
        scenario!!.recreate()
        awaitText(detail.prompt)
        ui.onNodeWithText(detail.prompt).performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("schedule-edit-prompt").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("schedule-edit-prompt").assertTextContains("Review the schedule editor")
        ui.onNodeWithTag("schedule-edit-model").performScrollTo().assertTextContains("custom-model")
        bridge.refuseScheduleEdit = false
        ui.onNodeWithText("Save").performClick()
        awaitText("Schedule saved")
        ui.onNodeWithText("Edit scheduled prompt").assertDoesNotExist()
        val sent = MobileScheduleEditRequest.fromJson(bridge.commands.last { it.first == "/v1/scheduled/${detail.id}" }.second)
        assertEquals("Review the schedule editor", sent.prompt)
        assertEquals("in", sent.whenChoice)
        assertEquals(12, sent.delayAmount)
        assertTrue(sent.repeat)
        assertFalse(sent.newChat)
        assertEquals("personal", sent.accountId)
        assertEquals("custom-model", sent.model)
        assertEquals(listOf(detail.id), bridge.scheduled.map { it.id })
        assertEquals("", store.drafts(bridge.machine.id)[DeckViewModel.scheduleEditDraftKey(detail.id)].orEmpty())
    }

    private fun awaitText(text: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun hideKeyboard() {
        shell("input keyevent KEYCODE_BACK")
        ui.waitForIdle()
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(350, 3_000)
        val bitmap = automation.takeScreenshot()
        val folder = java.io.File(app.getExternalFilesDir(null), "story-evidence").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        shell("mkdir -p /data/local/tmp/agent-deck-story-evidence")
        shell("cp ${folder.absolutePath}/$name.png /data/local/tmp/agent-deck-story-evidence/$name.png")
    }

    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
