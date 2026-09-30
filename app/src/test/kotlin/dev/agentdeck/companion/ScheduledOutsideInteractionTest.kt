package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.mobile.MobileScheduledOutside
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduledScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Schedules another tool owns are listed with whether they fire, and offer nothing to change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduledOutsideInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = DeckFixtures.byName("scheduled")!!

    private fun show(outside: List<MobileScheduledOutside>) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ScheduledScreen(
                        rows = emptyList(), loading = false, canCreate = true,
                        projects = listOf("/work/project"), hello = fixture.hello,
                        draft = "", onDraft = {}, onRefresh = {}, onCreate = { _, _, _, _ -> },
                        onCommand = { _, _, _ -> },
                        outside = outside,
                    )
                }
            }
        }
    }

    @Test
    fun `a paused automation leads with its state and names its source`() {
        show(
            listOf(
                MobileScheduledOutside("codex:funda", "Codex", "Paused — Daily at 09:00", "User", "Search listings", "memory.md"),
                MobileScheduledOutside("j1", "Claude Code", "Every 5 min", "Project", "Poll the build"),
            ),
        )

        compose.onNodeWithText("Created outside the IDE").assertIsDisplayed()
        compose.onAllNodesWithTag("scheduled-outside").assertCountEquals(2)
        compose.onNodeWithText("Paused — Daily at 09:00").assertIsDisplayed()
        compose.onNodeWithText("Codex · User · memory.md").assertIsDisplayed()
        compose.onNodeWithText("Claude Code · Project").assertIsDisplayed()
    }

    @Test
    fun `nothing outside draws no heading`() {
        show(emptyList())

        compose.onAllNodesWithTag("scheduled-outside").assertCountEquals(0)
        compose.onAllNodesWithText("Created outside the IDE").assertCountEquals(0)
    }
}
