package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileScheduledOutcome
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduledScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A finished run's row: its failure detail in words, and a tap into the chat it wrote. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduledOutcomesInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = DeckFixtures.byName("scheduled")!!
    private val opened = mutableListOf<MobileScheduledOutcome>()

    private fun outcome(task: String, key: String?, failed: Boolean = false, detail: String? = null) =
        MobileScheduledOutcome(
            taskId = task, prompt = "Prompt of $task", projectPath = "/work/project", key = key,
            vendor = AgentVendor.CLAUDE, finishedAtMs = DeckFixtures.NOW - 60_000, failed = failed, detail = detail,
        )

    private fun show(outcomes: List<MobileScheduledOutcome>) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ScheduledScreen(
                        rows = emptyList(), loading = false, canCreate = true,
                        projects = listOf("/work/project"), hello = fixture.hello,
                        draft = "", onDraft = {}, onRefresh = {}, onCreate = { _, _, _, _ -> },
                        onCommand = { _, _, _ -> },
                        outcomes = outcomes, onOpenOutcome = { opened += it },
                    )
                }
            }
        }
    }

    @Test
    fun `a run with a chat opens it and a failed one says why`() {
        show(listOf(outcome("ok", "v2:key"), outcome("bad", null, failed = true, detail = "exit 1: no such file")))

        compose.onNodeWithText("Recent runs").assertIsDisplayed()
        compose.onAllNodesWithTag("scheduled-outcome").assertCountEquals(2)
        compose.onNodeWithText("Failed — exit 1: no such file").assertIsDisplayed()

        compose.onNodeWithText("Prompt of ok").performClick()
        compose.runOnIdle { assertEquals(listOf("ok"), opened.map { it.taskId }) }
    }

    @Test
    fun `a run with no recorded chat offers nothing to open`() {
        show(listOf(outcome("orphan", null)))

        compose.onNodeWithText("Ran — no chat recorded").assertIsDisplayed()
        compose.onNodeWithText("Prompt of orphan").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), opened.map { it.taskId }) }
    }

    @Test
    fun `no finished runs draws no heading`() {
        show(emptyList())

        compose.onAllNodesWithTag("scheduled-outcome").assertCountEquals(0)
        compose.onAllNodesWithText("Recent runs").assertCountEquals(0)
    }
}
