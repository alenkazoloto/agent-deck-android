package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetGrouping
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The long-press sheet's Pin, Done and Rename (M3), and what a Done or pinned row does to the list:
 * a pin leads every ordering, Done leaves the everyday list for its own tab unless the row waits
 * on the reader, and none of it is offered by a machine that has not advertised `session-actions`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionActionsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val filter = mutableStateOf(FleetFilter())
    private val sent = mutableListOf<Triple<String, String, String?>>()

    private fun show(rows: List<MobileFleetRow>, canOrganize: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(rows, 0, emptyList(), null, DeckFixtures.NOW),
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
                        canOrganize = canOrganize,
                        onSessionAction = { row, action, title -> sent += Triple(row.key, action, title) },
                    )
                }
            }
        }
    }

    @Test
    fun `the sheet pins, marks done and renames the row it was opened on`() {
        show(listOf(row("a", "Fix the parser"), row("b", "Write the docs")))

        compose.onNodeWithText("Write the docs").performTouchInput { longClick() }
        compose.onNodeWithText("Pin to the top").performClick()
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Mark done").performClick()

        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Rename chat").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Fix the parser")).performTextReplacement("Parser rewrite")
        compose.onNodeWithText("Rename").performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    Triple("b", MobileSessionActionRequest.PIN, null),
                    Triple("a", MobileSessionActionRequest.DONE, null),
                    Triple("a", MobileSessionActionRequest.RENAME, "Parser rewrite"),
                ),
                sent,
            )
        }
    }

    @Test
    fun `rename starts from the current name, and Codex needs one`() {
        var renamed: String? = null
        val codex = mutableStateOf(false)
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                dev.agentdeck.companion.ui.RenameDialog("Fix the parser", codex.value, {}, { renamed = it })
            }
        }
        compose.onNode(hasSetTextAction()).assertTextContains("Fix the parser")
        compose.onNodeWithText("Leave empty to use the first prompt.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("Rename").assertIsEnabled()

        codex.value = true
        compose.onNodeWithText("Leave empty to use the first prompt.").assertDoesNotExist()
        compose.onNodeWithText("Rename").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Parser rewrite")
        compose.onNodeWithText("Rename").performClick()
        compose.runOnIdle { assertEquals("Parser rewrite", renamed) }
    }

    @Test
    fun `an older machine offers none of it`() {
        show(listOf(row("a", "Fix the parser")), canOrganize = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        listOf("Pin to the top", "Mark done", "Rename").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun `Copy the session ID puts the chat's own id on the clipboard, and a key of another shape offers none`() {
        val id = "0b8d577a-2a82-4a6f-bc0c-fd131a3b3b6c"
        val key = com.github.claudeagents.core.SessionAttentionKey(AgentVendor.CLAUDE, "default", id, "/repo").persistenceKey()
        show(listOf(row(key, "Fix the parser"), row("a", "Write the docs")))

        compose.onNodeWithText("Write the docs").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        compose.onNodeWithText("Copy the session ID").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()

        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Copy the session ID").performClick()
        compose.runOnIdle {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            assertEquals(id, clip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test
    fun `a Done row leaves the list for the Done tab, which offers Reopen`() {
        show(listOf(row("a", "Fix the parser", done = true), row("b", "Write the docs")))
        compose.onNodeWithText("Fix the parser").assertDoesNotExist()

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Fix the parser").assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText("Reopen").performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("a", MobileSessionActionRequest.REOPEN, null)), sent) }
    }

    @Test
    fun `pins lead every ordering, and Done never hides a row that waits on the reader`() {
        val rows = listOf(
            row("old-pinned", "Old", activityMs = 1, pinned = true),
            row("new", "New", activityMs = 9),
            row("waiting-done", "Question", activityMs = 5, done = true,
                attention = com.github.claudeagents.core.SessionAttentionState.WAITING_ON_YOU),
            row("failed-done", "Broke", activityMs = 4, done = true,
                attention = com.github.claudeagents.core.SessionAttentionState.FAILED),
        )
        FleetSort.entries.forEach { sort ->
            val sections = FleetGrouping.sections(rows, FleetFilter(), DeckFixtures.NOW, sort)
            // Attention groups first (a question still outranks a pin), and the pin leads its group.
            val section = sections.first { s -> s.rows.any { it.key == "old-pinned" } }
            assertEquals("$sort", "old-pinned", section.rows.first().key)
            val shown = sections.flatMap { it.rows }.map { it.key }
            assertTrue("$sort hid a waiting or failed row under Done", shown.containsAll(listOf("waiting-done", "failed-done")))
        }
        assertEquals(
            listOf("waiting-done", "failed-done"),
            rows.filter(FleetFilter(scope = FleetScope.DONE)::matches).map { it.key },
        )
    }

    private fun row(
        key: String,
        title: String,
        activityMs: Long = DeckFixtures.NOW - 60_000,
        pinned: Boolean = false,
        done: Boolean = false,
        attention: com.github.claudeagents.core.SessionAttentionState? = null,
    ) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = attention, waitingReason = null,
        lastActivityMs = activityMs, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        pinned = pinned, done = done,
    )
}
